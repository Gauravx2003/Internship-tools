# Web Session Manager — Modules and API Architecture

*Author: Gaurav Daware · November 2026 · Draft v4, one engine process per session over a stdio pipe · Companion to the PRD*

## 1. Purpose

This document lists every module in the system, what each one is responsible for, how the modules talk to each other, and how the API is structured. It is written to be reviewed before implementation starts.

## 2. System Overview

```
BROWSER (Vue + terminal)
   |  HTTPS for pages and data
   |  WebSocket for the live terminal (one per tab)
   v
+----------------------------------------------------------------+
| VERT.X SERVICE                                                 |
|  middleware, auth                                              |
|  hosts, credentials (vault), keys                              |
|  history*, logs*, export-import*                               |
|  terminal-gateway, engine-manager, engine-channel              |
+----------------------------------------------------------------+
   |  PostgreSQL            |  one Go process per terminal,
   v                        |  started by Vert.x, talking over
                            |  its stdin / stdout pipe
                            v
+----------------------------------------------------------------+
| GO SESSION ENGINE  (one process = one session)                 |
|  stdio-server, session                                         |
|  ssh-client, pty-pump                                          |
|  hostkey-verifier*, session-logger*, key-deployer*             |
+----------------------------------------------------------------+
   |  SSH (direct for now, through an agent later)
   v
REMOTE SERVER

* = extended or feature-specific module
```

**Rules that shape everything below**

1. The browser talks only to Vert.x. The engine is never exposed to the browser, and it has no network port at all.
2. Vert.x owns all state (users, vault, history, known hosts). The engine is stateless and lives for exactly one session.
3. Every Vert.x-to-engine call goes through one transport-neutral interface, `EngineChannel`. Today it is a pipe.
4. Every engine-to-target connection goes through one interface, `SessionDialer`.
5. Every database query is scoped by `user_id`.
6. One terminal = one engine process. When the session ends, the process ends.

## 3. Module Catalog

### 3.1 Frontend (Vue 3)

| Module | Responsibility |
| --- | --- |
| **api-client** | One typed wrapper over `fetch`: base URL, cookie credentials, CSRF header, error normalization, 401 handling (redirect to login). Types generated from the OpenAPI spec. |
| **auth** | Login page, logout (with an option to close all open terminals), session-expiry handling, route guards (Vue Router). |
| **host-manager** | List, create, edit, duplicate, delete saved sessions; folders/tags, color, favorite toggle; search and pagination. |
| **credential-manager** | Create/edit/delete credentials (password or private key upload); write-only secret fields; shows "used by N hosts". |
| **key-manager** | Generate key pair, show public key and fingerprint, one-time private key download, deploy-key wizard. |
| **quick-access** | Ctrl+K command palette, favorites list, recent list, quick-connect form. |
| **terminal** | xterm.js wrapper (fit, web-links, search, unicode addons), keyboard handling, clipboard, resize observer, flow-control acknowledgements. One instance per tab. |
| **session-tabs** | Tab bar and tab state (Pinia): open, close, rename, reorder, per-tab connection state (connecting, host-key prompt, connected, closed), `v-show` visibility so scrollback survives. Closing a tab closes only that terminal. A "Close all" action closes every open terminal without logging out. |
| **ws-client** | WebSocket connection per tab: login cookie and Origin check on connect, message envelope encode/decode, heartbeat, reconnect action, backpressure acks. |
| **host-key-dialog** | Shows fingerprint on first connect (trust / cancel) and mismatch warning. |
| **history-logs** | Session history table with filters; list, download, delete session logs. |
| **settings** | Terminal preferences (font, size, theme, scrollback), logging default, idle timeout display. |
| **shell/layout** | App frame, navigation, theme, toasts. Packaged so it can mount inside a larger host app later. |

### 3.2 Vert.x service (Java)

Organized in layers: **handler** (HTTP/WS, validation, no business logic) → **service** (business rules, ownership checks, transactions) → **repository** (SQL only). Modules talk through service interfaces; long-running modules run as verticles.

| Module | What it does | Type |
| --- | --- | --- |
| **middleware** | Every request passes through this first. It tags the request, checks it comes from our own site, confirms the user is logged in, limits repeated requests, and turns any error into a clear, consistent message. | Core |
| **auth** | Handles login and logout. Each login lasts a fixed time (30 minutes by default) and is never extended by activity. Logout ends only the login, or, with the close-all option, also closes the user's open terminals. It is built so a company login (SSO) can replace it later. | Core |
| **hosts** | Saves and manages the servers a user connects to: add, edit, delete and search. It also keeps each server's startup script and the trusted fingerprint of the server. Favourites, tags and last used are extended features. | Core, with extended parts |
| **credentials (vault)** | Saves usernames, passwords and private keys. It checks that an uploaded key is valid, encrypts secrets with standard encryption before saving, and never sends a saved password or key back to the browser. Only the service itself can read them when a connection starts. | Core |
| **keys** | Creates a new public and private key pair. The private key is saved in the vault and the user gets the public key. Useful when the user wants to log in with a key instead of a password. | Core |
| **terminal-gateway** | The link between the browser terminal and that terminal's own engine process. When the browser opens a terminal connection, it checks the user is logged in and the request comes from our own site. Then it passes what the user types to the engine and what the server prints back to the browser. It limits how many sessions one user can open and closes sessions left unused for too long. It keeps an in-memory list of each user's open terminals (one entry per terminal: the browser connection and its engine process). The session limit, "close one terminal", "close all terminals", "logout and close all" and shutdown clean-up all use this list. | Core |
| **engine-manager** | Starts one Go engine process for every terminal, and one short-lived process for each one-off job such as deploying a key. It watches each process, collects it when it exits, and force-stops it if it does not stop in a few seconds after being asked. It enforces an overall limit on running processes and counts them. Starting a process is slow work, so it never happens on the event loop. There is no shared engine and no idle timer any more. | Core |
| **engine-channel** | The road between the Vert.x service and one engine process: that process's stdin and stdout. Every terminal has its own pipe and nothing is shared. It reads and writes on dedicated threads (never the event loop) and collects the engine's log output from stderr into Vert.x's own logs. It sits behind the `EngineChannel` interface, so a message queue or a remote agent can replace the pipe later. | Core |
| **history** | Keeps a record of every connection: which server, when it started, when it ended and how it ended (closed normally, closed by timeout, interrupted, or engine crashed). Also feeds the recent and most used lists. | Extended |
| **logs** | Keeps the saved text logs of sessions. Users can see the list, download a log or delete it. Old logs are removed automatically and log files stay private to their owner. | Extended |
| **export-import** | Backs up saved servers into a protected file and restores them later or on another system. It checks the file before importing, and imported items are always added as new, so nothing existing is overwritten. | Extended |

Shared plumbing such as database access and settings is not a separate module. It is code every module above uses.

### 3.3 Go session engine

One engine process serves exactly one session (or one one-off job). It reads its instructions from stdin, writes its answers to stdout, and exits when the session is over.

| Module | What it does | Type |
| --- | --- | --- |
| **stdio-server** | The engine's front door. It reads messages from stdin and writes messages to stdout. It has no network port and no secret token: the only one who can talk to it is the Vert.x service that started it. It announces `ready` when it starts, and exits as soon as stdin closes. Nothing except protocol messages is ever written to stdout; its own log lines go to stderr. | Core |
| **session** | Holds the one session this process was started for: its SSH connection, its terminal pump and its log. It has one close routine that tidies everything and ends the process. That routine runs when Vert.x asks to close, when the SSH connection ends, or when stdin closes. The limit on how many sessions a user can have is not enforced here; Vert.x does that. | Core |
| **ssh-client** | Does the actual SSH work. It connects to the remote server (directly for now, through an agent later), logs in with a password, a key, or a key with a passphrase, opens a terminal on the remote side, and keeps the connection alive. | Core |
| **pty-pump** | Moves data between the pipe and the remote server. It carries what the user types to the server and what the server prints back, slows down if the other side cannot keep up, and passes on window resizes so full-screen apps redraw properly. | Core |
| **hostkey-verifier** | Checks that the server is really the one you think it is. If the server's fingerprint is already known, it compares and blocks the connection on a mismatch. For a first-time server it pauses and asks the user to trust it or not. Kept as a separate module in case Go's SSH library does not handle this the way we need. | Alternative |
| **session-logger** | If logging is turned on for a session, it saves everything shown in the terminal to a plain text file, optionally without colour and cursor codes. The file is private to its owner and is saved when the session ends. | Extended |
| **key-deployer** | A one-time helper for "deploy my public key". Runs as its own short-lived process: it logs in with the password, creates the .ssh folder with safe permissions if missing, adds the public key if it is not already there, reports back and exits. | Feature |
| **debug-ws** | A test-only switch that exposes the same message handling on a local WebSocket so the engine can be tried from Postman. It is not compiled into production builds. | Dev only |

## 4. Internal Communication

### 4.1 Vert.x ↔ Engine (MVP: stdio pipe)

Vert.x starts the engine as a child process and talks to it over the process's standard streams. There are no ports, no tokens and no Origin checks on this leg, because only the parent holds the other end of the pipe.

**How the engine is started**

- Command line carries only harmless settings, for example `--session-id <uuid>` and `--log-level`. **Never** put a password, key or host in the command line or the environment, because other users on the machine can see them in the process list.
- The environment is kept minimal. Secrets travel only inside the `open` message, over stdin.

**The three streams**

| Stream | Direction | Carries |
| --- | --- | --- |
| stdin | Vert.x → engine | Messages and the user's typing |
| stdout | engine → Vert.x | Messages and the server's output. Nothing else may ever be printed here, or the stream is corrupted. |
| stderr | engine → Vert.x | Log lines only (no session content, no secrets). Vert.x reads it into its own JSON logs, tagged with the session ID, so the pipe never fills up. |

**Frame format (both directions)**

A pipe is a plain stream of bytes with no message boundaries, so every message is wrapped in a frame:

```
[ 4 bytes: length, big-endian ][ 1 byte: kind ][ payload ]
   length counts the kind byte plus the payload
   kind 0x01 = control message (JSON envelope)
   kind 0x02 = input bytes  (Vert.x → engine, raw, no base64)
   kind 0x03 = output bytes (engine → Vert.x, raw, no base64)
   maximum frame size: 1 MiB (configurable); a bigger frame is a protocol error
```

Control messages use the same JSON envelope as before. Raw input and output skip the envelope, which saves the base64 overhead and keeps typing fast.

```
{ "v": 1, "type": "...", "sessionId": "uuid", "seq": 123,
  "ts": "2026-11-03T10:00:00Z", "payload": { ... } }
```

The envelope has only `sessionId`. Extra fields such as `session.id` and a duplicate `raw` copy of the output (seen in debug builds) are not part of the protocol and are removed.

| Direction | Type | Frame | Payload |
| --- | --- | --- | --- |
| Engine → Vert.x | `ready` | control | protocol version. Sent once, right after the process starts. Vert.x waits for it (with a timeout) before sending `open`. |
| Vert.x → Engine | `open` | control | host, port, username, authType, secret (password or key + passphrase), expectedFingerprint (nullable), term, cols, rows, logging {enabled, stripControl, path} |
| Vert.x → Engine | `input` | binary (0x02) | the user's typing, raw bytes |
| Vert.x → Engine | `resize` | control | cols, rows |
| Vert.x → Engine | `ack` | control | bytes consumed (flow-control credit) |
| Vert.x → Engine | `hostkey_decision` | control | accept / reject |
| Vert.x → Engine | `close` | control | reason |
| Vert.x → Engine | `deploy_key` | control | host, port, username, password, publicKey. Sent instead of `open` when the process is a one-off job. |
| Engine → Vert.x | `state` | control | connecting / authenticating / connected |
| Engine → Vert.x | `hostkey_challenge` | control | keyType, fingerprint (SHA256) |
| Engine → Vert.x | `output` | binary (0x03) | the server's output, raw bytes |
| Engine → Vert.x | `error` | control | code, message |
| Engine → Vert.x | `closed` | control | reason, exitStatus |
| Engine → Vert.x | `result` | control | outcome of a one-off job |

**Error codes (engine):** `DNS_FAILED`, `CONNECT_TIMEOUT`, `CONNECTION_REFUSED`, `AUTH_FAILED`, `HOSTKEY_MISMATCH`, `HOSTKEY_REJECTED`, `PTY_FAILED`, `PROTOCOL_ERROR`, `INTERNAL`.

**Error codes (Vert.x side, for the browser):** `SESSION_LIMIT` (user or server limit reached), `ENGINE_START_FAILED` (process did not start or never sent `ready`), `ENGINE_CRASHED` (process ended without sending `closed`).

**Rules for one process per session**

- **Flow control:** there is one credit counter, for the one session in the process. When the credit runs out, the engine stops reading from SSH. The pipe's own buffer (about 64 KB on most systems) adds natural back-pressure, but the browser `ack` still decides how much output is in flight, because the slow link is usually the browser.
- **Either side dies, the other cleans up:**
    - If the engine stops reading or the pipe closes on the Vert.x side (Vert.x crash or restart), the engine sees end-of-input on stdin, closes its SSH connection and exits. No SSH connection is left running.
    - If the engine process exits or crashes, Vert.x sees the end of its output stream, tells the browser the terminal closed, and records the session as ended abnormally.
    - As a safety net the engine also exits if its parent process disappears.
- **Isolation:** each browser terminal WebSocket maps to exactly one engine process held in terminal-gateway, and the process knows nothing about users, only its own session. A crash or memory fault in one engine process cannot affect any other session. All engine processes run as the same operating-system user as the service, so this is crash and memory isolation, not a security sandbox.
- **Secrets:** the credential is sent once, inside `open`, over the pipe. The engine uses it to connect, then wipes it. It never appears in command-line arguments, environment variables or logs, and it disappears from memory for good when the process exits.

### 4.2 Keeping the event loop free

The Vert.x event loop serves every user's connections. One blocking call freezes all of them, so the pipe work follows these rules:

| Rule | Why |
| --- | --- |
| Starting the process (`ProcessBuilder.start()`) runs on a worker thread | It can take 50 to 200 ms, especially on Windows where the antivirus scans new programs |
| Reading the engine's stdout is done on a dedicated reader thread per session (a virtual thread on Java 21), never the event loop | A pipe read blocks until data arrives |
| Writing to the engine's stdin goes through a small queue and a dedicated writer thread per session | A write blocks if the engine is slow and the pipe is full |
| Every queue has a limit. If the limit is hit, the terminal is closed with an error instead of growing memory | A slow consumer must not eat the server's memory |
| Reader threads hand frames back to the event loop context the terminal started on, so one terminal's messages stay in order without locks | Keeps ordering simple |
| stderr is always read, or sent to a file | If nobody reads it and it fills up, the engine freezes |
| Waiting for a process to end uses `Process.onExit()`, not `waitFor()` on the event loop | Waiting blocks |
| Vert.x's blocked-thread warning is switched on with a low limit (100 to 200 ms), and any warning seen during the load test is treated as a bug | Catches mistakes early |

### 4.3 Terminal process lifecycle

```
(no process) --connect--> SPAWNING --ready--> OPENING --connected--> ACTIVE
                              |                  |                      |
                       start fails or      auth, host-key         close one, close all,
                       no `ready` in time  or network error       idle, max lifetime,
                              |                  |               SSH ends, engine crash
                              v                  v                      v
                           FAILED ----------> CLOSING ----------------> EXITED
```

- A process is started only when someone connects. Nothing is started in advance and nothing waits around idle.
- **The one close routine.** Every way a terminal can end goes through the same steps, in terminal-gateway:
    1. Mark the terminal as closing and ignore further typing.
    2. Send `close` to the engine.
    3. Wait up to 3 seconds (configurable) for `closed` and for the process to exit.
    4. If it is still running, force-stop it.
    5. Collect the process, remove the terminal from the user's list, write the history end record, tell the browser `closed` and close its WebSocket.
- On Vert.x shutdown, every terminal goes through this routine, so end records are written before the service stops.

### 4.4 Why one process per session

- **No multiplexing.** The engine handles one session, so there is no session routing, no per-session sequence counters and no per-session flow-control table.
- **Isolation.** A crash or a stuck session cannot affect other users' terminals.
- **Simple clean-up.** When the process exits, the SSH connection and the credential in memory are gone with it. The engine has no idle timer, no status endpoint and no restart logic.
- **Known precedent.** OpenSSH's `sshd` also starts a separate process for every connection.
- **Cost.** Measured on Linux with 100 idle engine processes: about 9 MB RSS each, but the real total is only about 142 MB (PSS), because the processes share the program's code pages. Each process has about 7 threads (721 in total). An open SSH session adds roughly 1.5 MB per process, so 100 sessions should stay around 300 MB (to be confirmed). Still to measure in the load test (step 10): memory with 100 real SSH sessions and under heavy output, start time (p50 and p95), and the same numbers on Windows with and without Defender.
- **Fallback.** If measurements are bad, a spare process can be started ahead of time, or a shared engine can be put back behind the same `EngineChannel` interface without changing the rest of Vert.x.
- **Latency.** The pipe adds only a few microseconds per message, far less than the network on either side. Typing feel depends on the network, flushing every frame immediately, and not batching input.

### 4.5 Making queues and agents possible later

- Vert.x modules never see the pipe; they use `EngineChannel`.
- A message queue or a remote agent can implement the same interface. With one process per session the channel is always one-to-one, so a remote agent simply provides the other end of that conversation.
- Messages that start work (`open`, `deploy_key`) could move to a queue first (request buffering, several engines). Interactive typing and output are latency-sensitive and may stay on a direct channel. This will be decided by measurement.
- The engine's message handling is separate from how bytes arrive (stdio-server is only the transport), so another transport can call the same handlers.

## 5. API Architecture

### 5.1 Conventions

| Topic | Rule |
| --- | --- |
| Base path | `/api/v1` |
| Format | JSON, UTF-8, camelCase fields, ISO-8601 UTC timestamps |
| Auth | HttpOnly, Secure, SameSite=Strict session cookie; CSRF token header on state-changing requests |
| Ownership | Every resource is looked up by `id` AND `user_id`; foreign IDs return 404 |
| Pagination | `?page=1&size=25` → `{ items, page, size, total }` |
| Sorting/filter | `?sort=name,-lastUsedAt&q=text&tag=prod&favorite=true` |
| Errors | `{ "error": { "code": "HOST_NOT_FOUND", "message": "...", "details": [...], "requestId": "..." } }` with proper HTTP status |
| Secrets | Write-only: accepted in POST/PUT, never returned |
| Limits | Body size limit, per-user rate limits on login and connect |
| Spec | OpenAPI 3 file is the contract; frontend types are generated from it |

### 5.2 REST endpoints

**Auth**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/auth/login` | Username + password → sets session cookie, returns user |
| POST | `/auth/logout` | Ends the login; open terminals keep running. With ?closeAll=true it also closes every open terminal of the user. |
| GET | `/auth/me` | Current user and CSRF token |

**Hosts (saved sessions)**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/hosts` | List with search, tag, favorite filter, pagination |
| POST | `/hosts` | Create (name, address, port, credentialId, color, tags, loggingEnabled, postConnectScript) |
| GET | `/hosts/{id}` | Detail |
| PUT | `/hosts/{id}` | Update |
| DELETE | `/hosts/{id}` | Delete |
| POST | `/hosts/{id}/favorite` | Set favorite `{ "favorite": true }` |
| GET | `/hosts/recent` | Most recently used |
| GET | `/hosts/frequent` | Most used |

**Credentials**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/credentials` | List metadata only (name, username, type, fingerprint, usedBy count) |
| POST | `/credentials` | Create with password or private key (+ passphrase) |
| PUT | `/credentials/{id}` | Update name/username; secret replaced only if provided |
| DELETE | `/credentials/{id}` | Delete; `409 CREDENTIAL_IN_USE` unless `?force=true` |
| GET | `/credentials/{id}/hosts` | Hosts using this credential |

**Keys**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/keys/generate` | `{ type }` (ed25519, rsa3072 or rsa4096) → creates the pair, stores the private key in the vault, returns the public key, fingerprint and a download token |
| GET | `/keys/download/{token}` | One-time private-key download (expires, single use) |
| POST | `/hosts/{id}/deploy-key` | `{ publicKeyCredentialId, password }` → Vert.x starts a one-off engine process, which appends the key and exits; returns result |

**Connecting**

| Method | Path | Purpose |
| --- | --- | --- |
| WS | `/ws/terminal` | Opens a live terminal (see 5.3). There is no separate connect call. |
| DELETE | `/hosts/{id}/fingerprint` | Forgets the saved server fingerprint, for example after a server was rebuilt. The next connect asks the user to trust it again. |

**Open terminals (close one, close all)**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/terminals` | The user's open terminals (sessionId, host, startedAt). Useful after a fresh login, when the UI has no tabs for terminals opened under an earlier login. |
| DELETE | `/terminals/{sessionId}` | Close one terminal. Only that terminal's engine process is stopped. Another user's ID returns 404. |
| DELETE | `/terminals` | Close all of the user's open terminals, without logging out. |

Closing a tab in the browser does the first one over the terminal's own WebSocket (a `close` message), so the REST calls are needed mainly for terminals the browser no longer has a tab for.

**History and logs**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/history` | Filter by host, status, date; paginated |
| GET | `/history/{id}` | Detail |
| DELETE | `/history/{id}` | Delete record (and its log, if any) |
| GET | `/logs` | List logs |
| GET | `/logs/{id}/download` | Download log |
| DELETE | `/logs/{id}` | Delete log |

**Settings, import/export, system**

| Method | Path | Purpose |
| --- | --- | --- |
| GET / PUT | `/settings` | Terminal and logging preferences |
| POST | `/export` | Passphrase + options → encrypted file |
| POST | `/import` | Encrypted file + passphrase → creates new records |
| GET | `/health` | Liveness and readiness (DB, engine binary found and startable) |
| GET | `/metrics` | Prometheus metrics (internal) |
| GET | `/engine/status` | Running engine processes by state, and recent start times (diagnostic) |

### 5.3 Browser WebSocket API

`WS /ws/terminal` (no ticket and no query string)

- The browser opens the connection. The login cookie and the Origin (which website the request came from) go along automatically. Vert.x accepts it only if the user is logged in and the Origin is our own site; otherwise it closes the connection.
- The first message from the browser must be `connect`. It carries the host ID and the terminal size. Quick Connect also carries the host, port, username and secret, used for that session only and never stored.
- Each browser WebSocket is one terminal and one engine process. Closing the WebSocket (tab closed, network lost) closes that terminal. There is no reattach in the MVP.

| Direction | Type | What it carries |
| --- | --- | --- |
| Browser to server | `connect` | hostId, cols, rows (Quick Connect adds host, port, username, secret) |
| Browser to server | `input` | what the user types |
| Browser to server | `resize` | cols, rows |
| Browser to server | `ack` | how much output was drawn, so fast output cannot overwhelm the browser |
| Browser to server | `hostkey_decision` | trust or reject |
| Browser to server | `close` | nothing. Closes this terminal only. |
| Browser to server | `ping` | nothing |
| Server to browser | `state` | engineStarting (the engine process is being started), connecting, authenticating, connected |
| Server to browser | `hostkey_prompt` | key type, fingerprint, and whether it changed from the saved one |
| Server to browser | `output` | terminal output |
| Server to browser | `error` | code and message |
| Server to browser | `closed` | reason |
| Server to browser | `pong` | nothing |

The `input` and `output` payloads are binary WebSocket frames in practice, with a small header carrying the type, to avoid base64 overhead on the browser leg. The JSON envelope is used for control messages. The engine leg (4.1) uses the same idea, so Vert.x can pass bytes through almost unchanged.

### 5.4 Login, session and terminal lifetime

The MVP uses one login cookie and no separate tokens. A terminal opens only if the user is logged in and the request comes from our own website.

| Topic | Rule |
| --- | --- |
| Login cookie | Set at login. Scripts cannot read it (HttpOnly), it travels only over HTTPS (Secure), and the browser sends it only on requests that start from our own site (SameSite=Strict). |
| Where the login lives | In memory on the Vert.x service (no auth\_sessions table), so logout takes effect immediately. A new random token ID is created at every login. |
| Login expiry | Fixed at 30 minutes after login (configurable). It is never extended: using the app or typing in a terminal does not count as activity for it. |
| After the cookie expires | New terminals and new API calls need a fresh login. Terminals already open keep running until their own idle timeout. |
| Close one terminal | The user closes a tab (browser `close`), or calls `DELETE /terminals/{sessionId}`. Only that terminal's engine process is stopped. Every other terminal is untouched. |
| Close all terminals | The user presses "Close all" (`DELETE /terminals`) or logs out with close-all. Vert.x runs the close routine (4.3) for every terminal in that user's list, in parallel. The login stays valid unless the user also logged out. |
| Terminal idle timeout | A terminal closes by itself after a period with no typing and no output (default 30 minutes), with a warning 1 to 2 minutes before. It closes only that terminal, through the same close routine. |
| Maximum terminal lifetime | 12 hours, so no terminal runs forever. |
| Logout | Ends the login only. Open terminals keep running until their own idle timeout or maximum lifetime. |
| Logout and close all | Ends the login and closes every open terminal of that user. Any other login of the same user issued before this moment also stops working (tokens\_valid\_after). |
| Remote server connection | Does not depend on the login cookie. It lives inside that terminal's engine process, uses the saved credential, and we end it ourselves on close one, close all, logout and close all, idle timeout or maximum lifetime. |
| Open-terminal list | Vert.x keeps an in-memory list of each user's open terminals, keyed by user, not by login. Every way a terminal ends (user closes it, close all, idle timeout, engine crash, SSH ended, forced close) goes through one close routine that also removes it from the list. |
| Server restart | All logins and open terminals end, because both live in the service's memory, and users log in again. Each engine process sees its pipe close and exits by itself, closing its SSH connection. A normal shutdown closes all terminals and writes their end records first; on startup, history rows still open are marked interrupted. |
| Tokens | No JWT, refresh token or ticket in the MVP. A one-time ticket can be added later as an extra safety layer. |

## 6. Key Flows

### 6.1 Connect with first-time host key

1. The user logs in earlier and gets the login cookie.
2. The user clicks Connect on a saved host. The browser opens `/ws/terminal`; the cookie and Origin go along automatically.
3. terminal-gateway checks the user is logged in and the Origin is our own site. If not, it closes the connection.
4. The browser sends its first message: `connect` with the host ID and terminal size.
5. terminal-gateway checks that the host belongs to the user, the address is allowed, and the user is under the session limit.
6. engine-manager starts a new engine process for this terminal. The browser sees an "engine starting" state. Vert.x waits for the engine's `ready` message, with a timeout.
7. The vault hands the saved credential to terminal-gateway (in memory only). engine-channel sends the `open` message down this process's stdin, with the saved fingerprint if there is one.
8. In the engine, ssh-client connects to the server and hostkey-verifier checks its fingerprint. For a first-time server, the browser shows the fingerprint, the user trusts it, and hosts saves it.
9. ssh-client logs in and opens a terminal. pty-pump carries typing and output in both directions through the pipe.
10. history records the start. If logging is on, session-logger writes the text log. When the terminal closes, the engine process exits, which also removes the credential from memory, and history records the end.

### 6.2 Generate and deploy a key

1. `POST /keys/generate` → Keys module generates the pair, vault stores the private key as a credential, response returns the public key, fingerprint and download token.
2. `POST /hosts/{id}/deploy-key` with the server password → engine-manager starts a one-off engine process and sends it a `deploy_key` message.
3. The engine connects with the password, creates `~/.ssh` (mode 700) and `authorized_keys` (mode 600) if needed, appends the key if absent, sends `result` and exits.
4. Vert.x responds with the outcome; the host can then be switched to the new key credential.

### 6.3 Close one terminal

1. The user closes a tab. The browser sends `close` on that tab's WebSocket (or calls `DELETE /terminals/{sessionId}`).
2. terminal-gateway finds that terminal in the user's list and runs the close routine (4.3): send `close` to the engine, wait up to 3 seconds, force-stop if needed.
3. The engine closes its SSH connection, saves the log, sends `closed` and exits.
4. Vert.x collects the process, removes the terminal from the list, history records the end, and the WebSocket is closed. The user's other terminals are not touched.

### 6.4 Close all terminals

1. The user presses "Close all" in the UI (`DELETE /terminals`), or chooses "logout and close all" (`POST /auth/logout?closeAll=true`).
2. terminal-gateway takes every terminal in that user's list and runs the same close routine (6.3) for each, in parallel.
3. For logout and close all, the login is also ended and `tokens_valid_after` is set. For "Close all" alone, the login stays valid.
4. The call returns after all terminals are closed, or after the 3-second limit has force-stopped the rest.

### 6.5 Engine crash or Vert.x restart

- **Engine process crashes:** Vert.x sees the end of its output and the process exit, sends `error` (`ENGINE_CRASHED`) and `closed` to the browser, removes the terminal from the list and marks the history row as crashed. Other terminals are not affected.
- **Vert.x crashes or restarts:** every engine process sees its stdin close, closes its SSH connection and exits. On startup, history rows still open are marked interrupted.

## 7. Data Ownership by Module

| Module | Tables owned |
| --- | --- |
| auth | users (including tokens\_valid\_after) |
| hosts | hosts (including `agent_id` nullable for later) |
| credentials | credentials |
| known-hosts | known\_hosts |
| history | session\_history |
| logs | session\_logs (metadata; files on disk) |
| settings | user\_settings |

Other modules read these tables only through the owning module's service.

## 8. Repository and Package Layout

```
web-session-manager/
  frontend/                 Vue app (src/modules/<module>/{views,components,store,api})
  gateway/                  Java Vert.x service
    src/main/java/.../middleware|auth|hosts|credentials|keys|
                      terminal|engine|history|logs|exportimport
    src/main/resources/db/migration/
  engine/                   Go session engine (one process per session)
    cmd/engine/main.go
    internal/stdio|session|sshclient|hostkey|pty|logger|keydeploy|debugws
  api/openapi.yaml          API contract (source of truth)
  api/engine-protocol.md    Frame format and messages between Vert.x and the engine
  deploy/                   Docker, systemd, Windows service files
  .github/workflows/        CI on Ubuntu and Windows
```

The engine binary is shipped next to the Vert.x JAR (same Docker image on Linux, same folder on Windows). Its path is a configuration setting.

## 9. Cross-Cutting Concerns

| Concern | Approach |
| --- | --- |
| Threading | Event loop never blocks. Hashing, crypto, key generation, file I/O and starting engine processes run on worker threads. Pipe reads and writes run on dedicated per-session threads (see 4.2). |
| Security | TLS, strict CSP, Origin checks, a SameSite login cookie, per-user ownership on every query, target allow-list, secrets never logged, secrets zeroed after use, secrets never in command-line arguments or environment variables, engine has no network port |
| Resource limits | Per-user session limit and an overall limit on running engine processes, both enforced by Vert.x before a process is started. Bounded queues on every pipe. |
| Errors | Typed exceptions in services, one central mapper to the API error format |
| Observability | Session ID in engine log lines and messages; JSON logs; metrics for running processes, start time of engine processes, crashes, and failures |
| Configuration | Environment variables with validation at startup (including the engine binary path and the close wait time) |
| Testing | Unit tests per module; contract tests against the OpenAPI spec; engine protocol tests that pipe frames into the engine binary with a fake Vert.x; integration tests with Docker OpenSSH servers; a mock terminal and the debug WebSocket are test-only |
| Windows support | No OS-specific paths; the JAR runs as a service via WinSW, and the Go engine is a plain child process started by Vert.x, not a service of its own |

## 10. Suggested Build Order

| Step | What gets built | Done when |
| --- | --- | --- |
| 1 | Contracts and skeleton: API spec, engine frame and message format, the engine-channel interface, repo and CI on Linux and Windows | Empty Vert.x, Go and Vue projects build in CI |
| 2 | Vert.x basics: middleware, auth (login, session cookie, logout), database setup | A user can log in and log out, and unauthenticated calls are rejected |
| 3 | hosts and credentials (vault) with standard encryption | A user can save a host and a credential and link them |
| 4 | Go engine core: stdio-server, session, ssh-client, pty-pump (plus the test-only debug WebSocket) | A test harness can pipe frames into the engine binary and open a shell on a test server; the engine exits when stdin closes |
| 5 | engine-manager and engine-channel | Vert.x starts one engine process per session, talks to it over the pipe, collects it on close, force-stops it if it hangs, and a killed Vert.x leaves no engine running |
| 6 | terminal-gateway and the browser terminal (login cookie, Origin check, `connect` message, tabs, resize) | First end-to-end shell works in the browser, including vim and Ctrl keys |
| 7 | hostkey-verifier and the fingerprint prompt, with hosts saving the fingerprint | First connect asks to trust, and a changed key blocks the connection |
| 8 | keys and key-deployer (one-off process) | A user can generate a key, deploy it with a password once, and then log in with the key |
| 9 | Extended modules: history, logs and session-logger, favourites, tags and last used, export-import | Past connections are listed, logs can be downloaded, and a backup can be restored |
| 10 | Session rules (fixed login expiry, close one, close all, logout and close all, restart clean-up, idle timeouts, warnings), limits, Windows packaging, load test | 50 sessions run stably for an hour; engine start time and memory per process are measured on Linux and Windows; the lifetime rules in 5.4 are verified |

## 11. Assumptions and Open Points for the Mentor

1. One engine process per terminal session and per one-off job, started as a child process of the Vert.x service.
2. Vert.x and the engine run on the same machine in the MVP.
3. Login uses a random token held in server memory, with a fixed expiry and no refresh. To confirm with the mentor: is JWT required?
4. Log files stay on the Vert.x host's disk. To confirm: path, size limits and retention.
5. To confirm: which queue technology the host product already runs, for the later engine-channel option.
6. A one-time ticket can be added later as extra protection for the terminal connection if it is needed.
7. Restart policy: all logins and open terminals end when Vert.x restarts, because they live only in memory. To confirm this is acceptable.
8. To confirm: how many sessions at once should the system support? The one-process-per-session design is comfortable for around 100 to 200 sessions. If the target is in the thousands, a shared engine behind the same `EngineChannel` should be reconsidered.
9. To measure on Windows: engine start time (antivirus scanning) and memory per engine process. Linux memory is already measured (see 4.4). If start time is too slow, start a spare process in advance.
10. Process isolation is for crashes and memory, not security, because all engine processes run as the same operating-system user as the service. To confirm this is acceptable.
11. Closing the browser WebSocket closes that terminal (no reattach in the MVP). To confirm.

## 12. What Changed from v3

| Area | v3 | v4 |
| --- | --- | --- |
| Vert.x ↔ engine link | One WebSocket per user, shared by all of that user's terminals | One stdin/stdout pipe per terminal, no network port |
| Engine | One shared Go process, started on demand and stopped when idle | One Go process per session, started on connect, exits when the session ends |
| Message routing | Messages tagged and routed by session ID, per-session counters | No routing: the process is the session. Session ID kept only for logs |
| Engine modules | control-server, session-manager, plus the rest | stdio-server and session replace them; debug-ws added (test only) |
| Engine lifecycle | STOPPED / STARTING / RUNNING / IDLE / STOPPING, idle timer, `/health`, `/status`, `/shutdown` | Per-terminal lifecycle with one close routine; no idle timer or control endpoints |
| Messages | JSON with base64 input and output | Length-prefixed frames; raw bytes for input and output; added `ready`, added `PROTOCOL_ERROR`; removed `SESSION_LIMIT` from the engine |
| Close one / close all | Close tab; logout and close all | Same, plus "Close all" without logout and `GET/DELETE /terminals` (this also settles open point 8 of v3) |
| Windows service | JAR and Go engine both as services | JAR only; the engine is started by Vert.x |
| Engine security | Shared secret token on the engine's connection | Not needed: only the parent can reach the pipe. Secrets never in arguments or environment |