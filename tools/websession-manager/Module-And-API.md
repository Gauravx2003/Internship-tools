# Web Session Manager — Modules and API Architecture*Author: Gaurav Daware · October 2026 · Draft v2, ticket service removed · Companion to the PRD*

## 1. Purpose

This document lists every module in the system, what each one is responsible for, how the modules talk to each other, and how the API is structured. It is written to be reviewed before implementation starts.

## 2. System Overview

```
BROWSER (Vue + terminal)
   |  HTTPS for pages and data
   |  WebSocket for the live terminal
   v
+----------------------------------------------------------------+
| VERT.X SERVICE                                                 |
|  middleware, auth                                              |
|  hosts, credentials (vault), keys                              |
|  history*, logs*, export-import*                               |
|  terminal-gateway, engine-manager, engine-channel              |
+----------------------------------------------------------------+
   |  PostgreSQL            |  link to the engine
   v                        |  (WebSocket now, queue later)
                            v
+----------------------------------------------------------------+
| GO SESSION ENGINE                                              |
|  control-server, session-manager                               |
|  ssh-client, pty-pump                                          |
|  hostkey-verifier*, session-logger*, key-deployer*             |
+----------------------------------------------------------------+
   |  SSH (direct for now, through an agent later)
   v
REMOTE SERVER

* = extended or feature-specific module
```

**Rules that shape everything below**

1. The browser talks only to Vert.x. The engine is never exposed to the browser.
2. Vert.x owns all state (users, vault, history, known hosts). The engine is stateless.
3. Every Vert.x-to-engine call goes through one transport-neutral interface, `EngineChannel`.
4. Every engine-to-target connection goes through one interface, `SessionDialer`.
5. Every database query is scoped by `user_id`.

## 3. Module Catalog

### 3.1 Frontend (Vue 3)

| Module | Responsibility |
| --- | --- |
| **api-client** | One typed wrapper over `fetch`: base URL, cookie credentials, CSRF header, error normalization, 401 handling (redirect to login). Types generated from the OpenAPI spec. |
| **auth** | Login page, logout, session-expiry handling, route guards (Vue Router). |
| **host-manager** | List, create, edit, duplicate, delete saved sessions; folders/tags, color, favorite toggle; search and pagination. |
| **credential-manager** | Create/edit/delete credentials (password or private key upload); write-only secret fields; shows "used by N hosts". |
| **key-manager** | Generate key pair, show public key and fingerprint, one-time private key download, deploy-key wizard. |
| **quick-access** | Ctrl+K command palette, favorites list, recent list, quick-connect form. |
| **terminal** | xterm.js wrapper (fit, web-links, search, unicode addons), keyboard handling, clipboard, resize observer, flow-control acknowledgements. One instance per tab. |
| **session-tabs** | Tab bar and tab state (Pinia): open, close, rename, reorder, per-tab connection state (connecting, host-key prompt, connected, closed), `v-show` visibility so scrollback survives. |
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
| **auth** | Handles login and logout. It keeps the user signed in while they are active and signs them out after a period of inactivity. It is built so a company login (SSO) can replace it later. | Core |
| **hosts** | Saves and manages the servers a user connects to: add, edit, delete and search. It also keeps each server's startup script and the trusted fingerprint of the server. Favourites, tags and last used are extended features. | Core, with extended parts |
| **credentials (vault)** | Saves usernames, passwords and private keys. It checks that an uploaded key is valid, encrypts secrets with standard encryption before saving, and never sends a saved password or key back to the browser. Only the service itself can read them when a connection starts. | Core |
| **keys** | Creates a new public and private key pair. The private key is saved in the vault and the user gets the public key. Useful when the user wants to log in with a key instead of a password. | Core |
| **terminal-gateway** | The link between the browser terminal and the engine. When the browser opens a terminal connection, it checks the user is logged in and the request comes from our own site. Then it passes what the user types to the engine and what the server prints back to the browser. It also limits how many sessions one user can open and closes sessions left unused for too long. | Core |
| **engine-manager** | Starts the Go engine when someone needs it and stops it after it has been idle for a while. If the engine crashes, it starts it again on the next request. It also counts how many sessions are running. | Core |
| **engine-channel** | The road between the Vert.x service and the Go engine. For now it uses WebSockets. Later we will test message queues and keep whichever works better. | Core |
| **history** | Keeps a record of every connection: which server, when it started, when it ended and how it ended. Also feeds the recent and most used lists. | Extended |
| **logs** | Keeps the saved text logs of sessions. Users can see the list, download a log or delete it. Old logs are removed automatically and log files stay private to their owner. | Extended |
| **export-import** | Backs up saved servers into a protected file and restores them later or on another system. It checks the file before importing, and imported items are always added as new, so nothing existing is overwritten. | Extended |

Shared plumbing such as database access and settings is not a separate module. It is code every module above uses.

### 3.3 Go session engine

| Module | What it does | Type |
| --- | --- | --- |
| **control-server** | The engine's front door. It accepts connections only from Vert.x and only when Vert.x shows the secret token. It also answers "are you alive?", "how many sessions are running?" and "please shut down". | Core |
| **session-manager** | The engine's list of open sessions. It starts a new session, closes finished ones, counts how many are running, enforces a maximum, and closes everything neatly when the engine is told to stop. | Core |
| **ssh-client** | Does the actual SSH work. It connects to the remote server (directly for now, through an agent later), logs in with a password, a key, or a key with a passphrase, opens a terminal on the remote side, and keeps the connection alive. | Core |
| **pty-pump** | Moves data between Vert.x and the remote server. It carries what the user types to the server and what the server prints back, slows down if the other side cannot keep up, and passes on window resizes so full-screen apps redraw properly. | Core |
| **hostkey-verifier** | Checks that the server is really the one you think it is. If the server's fingerprint is already known, it compares and blocks the connection on a mismatch. For a first-time server it pauses and asks the user to trust it or not. Kept as a separate module in case Go's SSH library does not handle this the way we need. | Alternative |
| **session-logger** | If logging is turned on for a session, it saves everything shown in the terminal to a plain text file, optionally without colour and cursor codes. The file is private to its owner and is saved when the session ends. | Extended |
| **key-deployer** | A one-time helper for "deploy my public key". It logs in with the password, creates the .ssh folder with safe permissions if missing, adds the public key if it is not already there, and reports back. | Feature |

## 4. Internal Communication

### 4.1 Vert.x ↔ Engine (MVP: WebSocket)

- **Control endpoints (HTTP):** `/health`, `/status`, `/shutdown`. Used by the Engine Manager only.
- **Session endpoint:** `WS /session`. One WebSocket per SSH session (terminal) or per one-shot operation (deploy key, test).
- **Envelope (all messages, both directions):**

```
{ "v": 1, "type": "...", "sessionId": "uuid", "seq": 123,
  "ts": "2026-10-19T10:00:00Z", "payload": { ... } }
```

| Direction | Type | Payload |
| --- | --- | --- |
| Vert.x → Engine | `open` | host, port, username, authType, secret (password or key + passphrase), expectedFingerprint (nullable), term, cols, rows, logging {enabled, stripControl, path} |
| Vert.x → Engine | `input` | bytes (base64) |
| Vert.x → Engine | `resize` | cols, rows |
| Vert.x → Engine | `ack` | bytes consumed (flow control) |
| Vert.x → Engine | `hostkey_decision` | accept / reject |
| Vert.x → Engine | `close` | reason |
| Vert.x → Engine | `deploy_key` | host, port, username, password, publicKey |
| Engine → Vert.x | `state` | connecting / authenticating / connected |
| Engine → Vert.x | `hostkey_challenge` | keyType, fingerprint (SHA256) |
| Engine → Vert.x | `output` | bytes (base64) |
| Engine → Vert.x | `error` | code, message |
| Engine → Vert.x | `closed` | reason, exitStatus |
| Engine → Vert.x | `result` | outcome of a one-shot operation |

**Error codes (engine):** `DNS_FAILED`, `CONNECT_TIMEOUT`, `CONNECTION_REFUSED`, `AUTH_FAILED`, `HOSTKEY_MISMATCH`, `HOSTKEY_REJECTED`, `PTY_FAILED`, `SESSION_LIMIT`, `INTERNAL`.

### 4.2 Making queues possible later

- Vert.x modules never see WebSockets of the engine; they use `EngineChannel`.
- Every message has `sessionId` and a per-session `seq`, so a queue can preserve order and a consumer can detect gaps.
- Commands that start work (`open`, `deploy_key`) can move to a queue first (request buffering, multiple engines). Interactive `input`/`output` streams are latency-sensitive and may stay on a direct channel; this will be decided by measurement.
- The engine's command handling is separated from the transport inside the engine (protocol module), so a queue consumer calls the same handlers as the WebSocket server.

### 4.3 Engine lifecycle (on demand)

```
STOPPED --request--> STARTING --ready--> RUNNING <--new request-- IDLE
   ^                    |                  |  active=0             |
   |                 fail/timeout          +----------> IDLE ------+
   |                                                      | idle timer expires
   +------------------ process exits <---- STOPPING <-----+
```

- Active session count is tracked by Vert.x (authoritative) and checked against the engine's `/status` before stopping.
- Idle timeout default: 5 minutes, configurable.
- A request during STARTING waits for ready (with timeout); during STOPPING it waits for STOPPED, then starts a new process. Starts are guarded by a single lock so concurrent first requests start exactly one engine.

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
| POST | `/auth/logout` | Ends session |
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
| POST | `/keys/generate` | \`{ type: ed25519 |
| GET | `/keys/download/{token}` | One-time private-key download (expires, single use) |
| POST | `/hosts/{id}/deploy-key` | `{ publicKeyCredentialId, password }` → engine appends key; returns result |

**Connecting**

| Method | Path | Purpose |
| --- | --- | --- |
| WS | `/ws/terminal` | Opens a live terminal (see 5.3). There is no separate connect call. |
| DELETE | `/hosts/{id}/fingerprint` | Forgets the saved server fingerprint, for example after a server was rebuilt. The next connect asks the user to trust it again. |

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
| GET | `/health` | Liveness and readiness (DB, engine manager) |
| GET | `/metrics` | Prometheus metrics (internal) |
| GET | `/engine/status` | Engine state and active sessions (diagnostic) |

### 5.3 Browser WebSocket API

`WS /ws/terminal` (no ticket and no query string)

- The browser opens the connection. The login cookie and the Origin (which website the request came from) go along automatically. Vert.x accepts it only if the user is logged in and the Origin is our own site; otherwise it closes the connection.
- The first message from the browser must be `connect`. It carries the host ID and the terminal size. Quick Connect also carries the host, port, username and secret, used for that session only and never stored.

| Direction | Type | What it carries |
| --- | --- | --- |
| Browser to server | `connect` | hostId, cols, rows (Quick Connect adds host, port, username, secret) |
| Browser to server | `input` | what the user types |
| Browser to server | `resize` | cols, rows |
| Browser to server | `ack` | how much output was drawn, so fast output cannot overwhelm the browser |
| Browser to server | `hostkey_decision` | trust or reject |
| Browser to server | `close` | nothing |
| Browser to server | `ping` | nothing |
| Server to browser | `state` | engineStarting, connecting, authenticating, connected |
| Server to browser | `hostkey_prompt` | key type, fingerprint, and whether it changed from the saved one |
| Server to browser | `output` | terminal output |
| Server to browser | `error` | code and message |
| Server to browser | `closed` | reason |
| Server to browser | `pong` | nothing |

The `input` and `output` payloads are binary WebSocket frames in practice, with a small header carrying the type, to avoid base64 overhead on the browser leg. The JSON envelope is used for control messages.

### 5.4 Login, session and terminal lifetime

The MVP uses one login cookie and no separate tokens. A terminal opens only if the user is logged in and the request comes from our own website.

| Topic | Rule |
| --- | --- |
| Login cookie | Set at login. Scripts cannot read it (HttpOnly), it travels only over HTTPS (Secure), and the browser sends it only on requests that start from our own site (SameSite=Strict). |
| Where the login lives | In a session store on the server, so logout takes effect immediately. A new session ID is created at every login. |
| Login expiry | After 30 minutes without activity, and never later than 8 hours after login (both configurable). Using the app or typing in a terminal counts as activity, recorded at most once a minute. |
| After the cookie expires | New terminals and new API calls need a fresh login. Terminals already open keep running until their own idle timeout. |
| Terminal idle timeout | A terminal closes by itself after a period with no typing and no output (default 30 minutes), with a warning 1 to 2 minutes before. It closes only that terminal. |
| Maximum terminal lifetime | 12 hours, so no terminal runs forever. |
| Logout | Ends the login and closes every open terminal of that user. |
| Last terminal closes | When no terminal is open and the user has done nothing in the app for the inactivity period, the login ends too. |
| Remote server connection | Does not depend on the login cookie. It uses the saved credential, and we close it ourselves on logout or idle timeout. |
| Tokens | No JWT, refresh token or ticket in the MVP. A one-time ticket can be added later as an extra safety layer. |

## 6. Key Flows

### 6.1 Connect with first-time host key

1. The user logs in earlier and gets the login cookie.
2. The user clicks Connect on a saved host. The browser opens `/ws/terminal`; the cookie and Origin go along automatically.
3. terminal-gateway checks the user is logged in and the Origin is our own site. If not, it closes the connection.
4. The browser sends its first message: `connect` with the host ID and terminal size.
5. terminal-gateway checks that the host belongs to the user, the address is allowed, and the user is under the session limit.
6. engine-manager makes sure the engine is running and starts it if it is stopped. The browser sees an "engine starting" state.
7. The vault hands the saved credential to terminal-gateway (in memory only). engine-channel opens a link to the engine and sends the connect request, with the saved fingerprint if there is one.
8. In the engine, ssh-client connects to the server and hostkey-verifier checks its fingerprint. For a first-time server, the browser shows the fingerprint, the user trusts it, and hosts saves it.
9. ssh-client logs in and opens a terminal. pty-pump carries typing and output in both directions.
10. history records the start. If logging is on, session-logger writes the text log. On close, history records the end and the engine drops the credential from memory.

### 6.2 Generate and deploy a key

1. `POST /keys/generate` → Keys module generates the pair, vault stores the private key as a credential, response returns the public key, fingerprint and download token.
2. `POST /hosts/{id}/deploy-key` with the server password → Vert.x ensures the engine is running and opens a one-shot engine operation with `deploy_key`.
3. Engine connects with the password, creates `~/.ssh` (mode 700) and `authorized_keys` (mode 600) if needed, appends the key if absent, returns `result`.
4. Vert.x responds with the outcome; the host can then be switched to the new key credential.

### 6.3 Engine idle stop

1. Last session closes → active count hits 0 → Engine Manager moves RUNNING → IDLE and starts the timer.
2. A new connect request cancels the timer and returns to RUNNING.
3. Timer expires → Manager checks `/status` shows 0 sessions → `POST /shutdown` → STOPPING → STOPPED. If a session appeared in between, shutdown is aborted.

## 7. Data Ownership by Module

| Module | Tables owned |
| --- | --- |
| auth | users, auth\_sessions |
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
  engine/                   Go session engine
    cmd/engine/main.go
    internal/control|session|sshclient|hostkey|pty|logger|keydeploy
  api/openapi.yaml          API contract (source of truth)
  api/engine-protocol.md    Messages between Vert.x and the engine
  deploy/                   Docker, systemd, Windows service files
  .github/workflows/        CI on Ubuntu and Windows
```

## 9. Cross-Cutting Concerns

| Concern | Approach |
| --- | --- |
| Threading | Event loop never blocks; hashing, crypto, key generation and file I/O on worker threads |
| Security | TLS, strict CSP, Origin checks, a SameSite login cookie, per-user ownership on every query, target allow-list, secrets never logged, secrets zeroed after use |
| Errors | Typed exceptions in services, one central mapper to the API error format |
| Observability | Request ID propagated to engine messages; JSON logs; metrics for sessions, engine state, failures |
| Configuration | Environment variables with validation at startup |
| Testing | Unit tests per module; contract tests against the OpenAPI spec; engine protocol tests with a fake Vert.x client; integration tests with Docker OpenSSH servers |
| Windows support | No OS-specific paths; services via WinSW (JAR) and kardianos/service (Go) |

## 10. Suggested Build Order

| Step | What gets built | Done when |
| --- | --- | --- |
| 1 | Contracts and skeleton: API spec, engine message format, the engine-channel interface, repo and CI on Linux and Windows | Empty Vert.x, Go and Vue projects build in CI |
| 2 | Vert.x basics: middleware, auth (login, session cookie, logout), database setup | A user can log in and log out, and unauthenticated calls are rejected |
| 3 | hosts and credentials (vault) with standard encryption | A user can save a host and a credential and link them |
| 4 | Go engine core: control-server, session-manager, ssh-client, pty-pump | A test client can open a shell on a test server through the engine |
| 5 | engine-manager and engine-channel | The engine starts on demand, stops after the idle timeout, and restarts after a crash |
| 6 | terminal-gateway and the browser terminal (login cookie, Origin check, `connect` message, tabs, resize) | First end-to-end shell works in the browser, including vim and Ctrl keys |
| 7 | hostkey-verifier and the fingerprint prompt, with hosts saving the fingerprint | First connect asks to trust, and a changed key blocks the connection |
| 8 | keys and key-deployer | A user can generate a key, deploy it with a password once, and then log in with the key |
| 9 | Extended modules: history, logs and session-logger, favourites, tags and last used, export-import | Past connections are listed, logs can be downloaded, and a backup can be restored |
| 10 | Session rules (logout closes terminals, idle timeouts, warnings), limits, Windows packaging, load test | 50 sessions run stably for an hour and the lifetime rules in 5.4 are verified |

## 11. Assumptions and Open Points for the Mentor

1. One engine process per Vert.x instance, started as a child process of the service.
2. Vert.x and the engine run on the same machine in the MVP.
3. Login uses a server-side session cookie. To confirm with the mentor: is JWT required?
4. Log files stay on the Vert.x host's disk. To confirm: path, size limits and retention.
5. To confirm: which queue technology the host product already runs, for the later engine-channel option.
6. A one-time ticket can be added later as extra protection for the terminal connection if it is needed.
