# Web Session Manager — Product Requirements Document

*Author: Gaurav Daware · October 2026 · Draft v1 · Working name: Web Session Manager (Web Solar-PuTTY)*

## 1. Summary

Web Session Manager is a browser-based SSH session manager. A user logs in, saves hosts and credentials, and opens a terminal to a remote server in a browser tab, with no client software installed. The browser talks only to a Java Vert.x service; Vert.x talks to an on-demand Go session engine; the engine makes the SSH connection to the remote server.

**Flow:** Browser (Vue + xterm.js) ⇄ Vert.x service ⇄ Go session engine ⇄ Remote server (SSH)

It is a single-user tool: each user has their own private hosts, credentials, sessions and history. There is no sharing and no role system. It is built as a self-hosted feature that can be added to the existing on-premise product.

## 2. Problem and Goals

### 2.1 Problem

Desktop SSH clients (PuTTY, Solar-PuTTY) must be installed per machine and OS, and keep credentials and private keys on every laptop. Users juggle many hosts and logins with no quick, central way to reach them.

### 2.2 Goals

1. Connect to a remote server over SSH from any modern browser with zero install.
2. Provide a terminal that feels like a native one: correct key handling, colors, full-screen apps, resize, copy/paste.
3. Store credentials securely server-side, reusable across many hosts.
4. Make frequent access fast: saved sessions, favorites, history.
5. Keep the architecture ready for two later changes: queues between Vert.x and the engine, and a remote agent for private networks.
6. Keep resource use low: the Go engine runs only when needed.

### 2.3 Non-goals

- Session recording or replay (only plain-text logs, see 5.7)
- Shared sessions, shared folders, teams, RBAC, admin/auditor roles
- SSHv1, SCP (SFTP covers file transfer later), serial/COM ports
- Local port forwarding and agent forwarding
- Compatibility with Solar-PuTTY .dat files
- Telnet, FTPS, MFA (possible later, not in this PRD's MVP)

## 3. Users and Use Cases

**Primary user:** an engineer or sysadmin who connects to several Linux/Unix servers regularly and wants one place to keep and launch those connections.

| ID | Use case | Summary |
| --- | --- | --- |
| UC1 | Log in | Username and password; session expires after inactivity |
| UC2 | Quick connect | Type host, port, username and password (or key) and connect without saving anything |
| UC3 | Manage credentials | Save a username with a password or private key (plus passphrase); reuse across hosts |
| UC4 | Save a session | Name, host, port, credential, color, tags, logging flag, post-connect script |
| UC5 | Connect and use terminal | Open a live terminal in a tab; resize, copy/paste, scrollback |
| UC6 | Multiple tabs | Several sessions open at once without losing output when switching |
| UC7 | Favorites and quick access | Mark favorites, see recent and most used, search (Ctrl+K) |
| UC8 | Session history | See when and where I connected, duration and status |
| UC9 | Session logging | Optionally save a plain-text log of a session |
| UC10 | Generate a key pair | Create Ed25519 or RSA key; private key goes to the vault, I get the public key and fingerprint |
| UC11 | Deploy public key | Enter the password once; the system adds my public key to the server's authorized\_keys |
| UC12 | Verify host key | First connect shows the fingerprint to trust; a changed key blocks the connection |

## 4. Architecture and Key Decisions

### 4.1 Components

| Component | Technology | Responsibility |
| --- | --- | --- |
| Web UI | Vue 3, TypeScript, Pinia, Vite, xterm.js | Login, host/credential/session management, terminal tabs |
| Gateway service | Java, Eclipse Vert.x | Auth, REST API, vault, WebSocket gateway, engine lifecycle manager, history, PostgreSQL access |
| Session engine | Go (x/crypto/ssh) | SSH connection, PTY, host-key check, byte streaming, log writing |
| Database | PostgreSQL | Users, hosts, credentials, sessions, history, known hosts |
| Targets | Linux/Unix servers with OpenSSH | Remote machines |

The browser only ever talks to Vert.x. The engine accepts connections only from Vert.x.

### 4.2 Decisions

| Decision | Choice | Reason |
| --- | --- | --- |
| Vert.x ⇄ engine transport | WebSocket, one per SSH session (MVP) | Browser leg is also WebSocket, so frames pipe through with little translation; easy to debug |
| Future queues | Transport sits behind a `EngineChannel` interface in Vert.x and a matching one in Go | A queue-backed implementation (e.g. NATS, RabbitMQ, Redis Streams) can replace WebSocket without touching business logic |
| Connection model | MVP: engine dials the remote host directly. Later: remote agent makes an outbound connection | A `SessionDialer` interface in the engine hides how the target is reached |
| Engine lifetime | On demand with idle timeout | Saves resources; engine is stateless |
| Credential custody | Vert.x owns the vault and sends the credential to the engine once per session | One component holds the master key; engine holds secrets only in memory for the connection |
| Logging vs recording | Plain-text logs only | Simpler, smaller, no replay features needed |

### 4.3 Connect flow

1. User clicks Connect on a saved session (or submits Quick Connect).
2. Vue calls `POST /api/sessions/{id}/connect` (authenticated) with an optional one-off credential.
3. Vert.x validates the request, applies the allowed-target rules, and checks whether the engine is running. If not, it starts it and waits until it reports ready.
4. Vert.x returns a one-time WebSocket ticket (valid \~30 seconds, single use).
5. Vue opens `wss://…/ws/terminal?ticket=…` with xterm.js attached.
6. Vert.x validates ticket and Origin, loads and decrypts the credential in memory.
7. Vert.x opens an engine WebSocket and sends the connect request with the credential.
8. The engine dials the host, verifies the host key, authenticates, requests a PTY and starts a shell.
9. Bytes flow: Browser ⇄ Vert.x ⇄ Engine ⇄ Target.
10. Vert.x writes a history record at start and updates it at end; the engine writes the plain-text log if enabled.

### 4.4 On-demand engine lifecycle

Vert.x contains an **Engine Manager** that owns the engine process.

| State | Meaning | Transition |
| --- | --- | --- |
| STOPPED | No engine process | A connect request arrives → STARTING |
| STARTING | Process launched, waiting for ready signal | Ready within start timeout → RUNNING; failure → STOPPED with error to user |
| RUNNING | At least one active SSH session or pending request | Active sessions reach zero → IDLE |
| IDLE | Running, no sessions, idle timer counting down | New request → RUNNING (timer cancelled); timer expires → STOPPING |
| STOPPING | Graceful shutdown | Process exits → STOPPED |

Rules:

- The **idle timeout is configurable** (default 5 minutes).
- Active sessions are counted by Vert.x and confirmed by the engine; a session counts until its SSH connection closes.
- A request arriving during STARTING is queued and served when the engine is ready; a request arriving during STOPPING waits for STOPPED and then triggers a fresh start (no race).
- If the engine crashes, Vert.x marks its sessions as ended with status "engine failure", moves to STOPPED, and starts it again on the next request.
- First-connect latency includes engine startup; the target is under 2 seconds (Go binary start is fast), and the UI shows a "starting engine" state.

## 5. Functional Requirements

Priority: **P0** = MVP, **P1** = planned after MVP, **P2** = later.

### 5.1 Authentication

- **FR-A1 (P0)** Users log in with username and password. Passwords are hashed with Argon2id on worker threads.
- **FR-A2 (P0)** Sessions expire after configurable inactivity; logout invalidates the session.
- **FR-A3 (P0)** Every API handler checks that the resource belongs to the logged-in user. Users never see other users' data.
- **FR-A4 (P0)** Per-user login rate limiting.
- **FR-A5 (P2)** Authentication sits behind a Vert.x `AuthenticationProvider` so the host product's login/SSO can replace it.

### 5.2 Credentials (vault)

- **FR-C1 (P0)** A credential has a name, username, and either a password or a private key (with optional passphrase).
- **FR-C2 (P0)** One credential can be linked to many hosts or sessions.
- **FR-C3 (P0)** Secrets are encrypted with AES-256-GCM using a per-credential data key (DEK); DEKs are wrapped by a master key (KEK) kept outside the database (environment variable or secret file). KEK version is stored to support rotation.
- **FR-C4 (P0)** Secrets are write-only through the API: after saving, only metadata (name, username, type, key fingerprint) is returned.
- **FR-C5 (P0)** Uploaded private keys (OpenSSH/PEM) are validated by parsing before saving.
- **FR-C6 (P1)** KEK rotation re-wraps DEKs without re-encrypting every secret.
- **FR-C7 (P0)** Deleting a credential that is in use warns the user and lists affected hosts.

### 5.3 Hosts and saved sessions

- **FR-H1 (P0)** A host has name, address, port (default 22), optional credential, color and tags.
- **FR-H2 (P0)** A saved session is a host plus its settings: credential, logging on/off, post-connect script, terminal options. Users can create, edit, duplicate and delete sessions.
- **FR-H3 (P0)** Quick Connect: the user can type host, port, username and password (or pick a key) and connect without saving. One-off secrets are used for that connection only and are never stored.
- **FR-H4 (P0)** Favorites: a session can be marked as favorite and appears in a favorites list.
- **FR-H5 (P0)** Recent and most used lists are derived from history (last\_used\_at and connection count).
- **FR-H6 (P0)** Search by name, host or tag with server-side search and pagination; a Ctrl+K command palette opens a session quickly.
- **FR-H7 (P1)** Post-connect script: an ordered list of commands run after login with a configurable delay between commands (default 300 ms). Warn users that secrets don't belong in scripts.
- **FR-H8 (P1)** Import/export of sessions as an encrypted JSON file (Argon2id key derivation + AES-GCM), without secrets by default; imports are schema-validated, size-limited and always create new records.

### 5.4 Terminal (browser experience)

This is the most important quality area. The terminal must behave like a native terminal, not a web page.

- **FR-T1 (P0)** xterm.js is the emulator, with a real PTY on the remote side (TERM=xterm-256color), so full-screen programs (vim, nano, htop, tmux, less, top, man) work.
- **FR-T2 (P0)** **Keystroke fidelity:** all keys, including Ctrl combinations (Ctrl+C, Ctrl+D, Ctrl+Z, Ctrl+R, Ctrl+A/E/L/U/W), Alt/Meta combinations, Tab, Esc, arrows, Home/End, PageUp/PageDown, Insert/Delete and F1–F12, are sent to the remote shell, not interpreted by the browser. The terminal captures keyboard focus and calls `preventDefault` on keys it handles, with the exceptions listed in FR-T3.
- **FR-T3 (P0)** Browser-reserved shortcuts (Ctrl+W, Ctrl+T, Ctrl+N, Ctrl+Tab, F5, F11, F12) cannot be reliably captured by a web page. These are documented, and the app provides alternate shortcuts for tab actions. Copy/paste uses Ctrl+Shift+C / Ctrl+Shift+V, and Ctrl+C copies only when text is selected (configurable); right-click paste is supported. Where supported, the Keyboard Lock API is used in fullscreen mode to capture more keys.
- **FR-T4 (P0)** Application cursor keys and keypad modes, bracketed paste, mouse reporting and alternate screen buffer are supported, so editors and TUIs behave correctly. Pasted text uses bracketed paste so multi-line pastes don't run unexpectedly.
- **FR-T5 (P0)** Resize: the terminal fits its container (fit addon) and sends the new rows/columns to the engine, which updates the PTY window size, so full-screen apps redraw correctly.
- **FR-T6 (P0)** Full UTF-8 and Unicode support, including wide characters and emoji; 256-color and truecolor.
- **FR-T7 (P0)** Scrollback buffer (default 10,000 lines, configurable); scroll position and output survive tab switching (tabs hidden with `v-show`, not destroyed).
- **FR-T8 (P0)** IME and non-US keyboard layouts work (AltGr, dead keys, composition input).
- **FR-T9 (P0)** Flow control: the browser acknowledges data in chunks and Vert.x applies backpressure to the engine so heavy output (e.g., `cat` on a large file) doesn't freeze the page or exhaust memory.
- **FR-T10 (P0)** Output is written to xterm.js as bytes; terminal data is never rendered as HTML.
- **FR-T11 (P1)** Font, font size, theme and cursor style settings; link detection; in-terminal search.
- **FR-T12 (P0)** Connection state is visible per tab: connecting, host-key prompt, connected, disconnected (with a reconnect action).

### 5.5 Multiple tabs

- **FR-M1 (P0)** Each tab owns one xterm instance and one WebSocket; tabs can be renamed, reordered and closed; the tab shows the session color.
- **FR-M2 (P0)** Per-user cap on concurrent sessions (default 10) and an idle timeout per session, with a warning before disconnect.

### 5.6 Host-key verification

- **FR-K1 (P0)** Trust on first use: the first connect pauses and shows the host-key fingerprint; the user accepts or rejects, and an accepted fingerprint is stored per user and host.
- **FR-K2 (P0)** A later mismatch blocks the connection with a clear warning.
- **FR-K3 (P0)** The user may replace a stored fingerprint after an explicit confirmation (the change is recorded in history).

### 5.7 Session logging and history

- **FR-L1 (P0)** **Session history:** every connection creates a record (host, credential used, start, end, duration, status, close reason). History is listed, filterable and searchable by the user.
- **FR-L2 (P0)** **Session logging** is plain text, one file per session, optional per session (logging flag) or per user preference. This is not a recording: no timing data, no replay.
- **FR-L3 (P0)** Logs are written by the engine as the session runs, and filenames include host and timestamp. The user can list, download and delete their logs.
- **FR-L4 (P0)** Logs can contain passwords the user typed or that appear on screen, so logging is **off by default**, shown with a warning when enabled, stored with restricted file permissions, and logs are encrypted at rest (P1).
- **FR-L5 (P1)** Log retention: delete logs and history older than a configurable number of days.
- **FR-L6 (P1)** Log options similar to PuTTY: strip control sequences or keep raw output.
- **FR-L7 (P0)** The application never logs session content or secrets in its own service logs.

### 5.8 Key generation and deployment

- **FR-G1 (P0)** Generate Ed25519 (default) or RSA 3072/4096 key pairs. DSA is not offered.
- **FR-G2 (P0)** The private key is saved to the vault as a credential; the user receives the public key (OpenSSH one-line format) and its fingerprint, with a one-time download option for the private key.
- **FR-G3 (P0)** **Deploy public key:** the user selects a host and enters the password once; the engine connects, creates `~/.ssh` with correct permissions if missing, appends the key to `authorized_keys` without duplicates, and reports success. The user can then switch the host to key-based login.
- **FR-G4 (P1)** Deployment verifies by test-connecting with the new key.
- **FR-G5 (P0)** The password used for deployment is held only in memory for that operation.

### 5.9 Engine behavior (Go)

- **FR-E1 (P0)** Opens SSH sessions with password, private key and key with passphrase authentication, using `golang.org/x/crypto/ssh`.
- **FR-E2 (P0)** Requests a PTY and shell; streams bytes both ways; forwards resize events; sends SSH keepalives.
- **FR-E3 (P0)** Handles many concurrent sessions with one goroutine pair per session.
- **FR-E4 (P0)** Starts and stops on demand as described in 4.4: exposes a ready signal, reports its active session count, and shuts down gracefully (closing sessions cleanly) when told to.
- **FR-E5 (P0)** Accepts connections only from Vert.x (loopback or Unix socket plus a shared token or mTLS).
- **FR-E6 (P0)** Stateless: no credentials or session data persist after a session ends.
- **FR-E7 (P0)** Dials through a `SessionDialer` interface; MVP implements `DirectDialer`.

## 6. Extensibility Requirements

### 6.1 Queues between Vert.x and the engine

The MVP uses WebSockets. The design must allow a queue later (for example, to buffer requests while the engine is starting, to scale to several engines, or to decouple restarts).

- **NFR-Q1** All Vert.x ⇄ engine communication goes through one `EngineChannel` abstraction (open session, send input, receive output, resize, close, health).
- **NFR-Q2** Messages use one versioned envelope: `{ "v": 1, "type": "input|output|control|error|done", "sessionId": "...", "seq": 0, "ts": "...", "payload": {} }`. A sequence number per session allows ordering and replay across a queue.
- **NFR-Q3** The engine's commands and events are defined independently of the transport (documented message schema), so a queue-based transport can carry the same messages.
- **NFR-Q4** Terminal streams are latency-sensitive; if a queue is added, control and request messages go through it first, and interactive byte streams may stay on a direct channel unless measured latency is acceptable. This will be decided with benchmarks, not assumed.

### 6.2 Remote agent (post-MVP)

- **NFR-A1** The engine dials targets only through `SessionDialer`. An `AgentDialer` will route the connection through an agent that sits in the remote network and makes an **outbound** connection to the backend (no inbound firewall holes).
- **NFR-A2** Host records get an optional `agent_id` field from the start (null in the MVP) so no migration is needed later.
- **NFR-A3** Agent enrollment, authentication and routing are specified in a separate document when this phase begins.

## 7. Data Model (summary)

- **users** (id, username, password\_hash, created\_at)
- **hosts** (id, user\_id, name, address, port, color, tags, favorite, last\_used\_at, use\_count, credential\_id, post\_connect\_script, logging\_enabled, agent\_id nullable)
- **credentials** (id, user\_id, name, username, auth\_type, ciphertext, wrapped\_dek, kek\_version, key\_fingerprint, created\_at)
- **known\_hosts** (id, user\_id, host\_id, key\_type, fingerprint, approved\_at)
- **session\_history** (id, user\_id, host\_id, started\_at, ended\_at, status, close\_reason, log\_ref nullable)
- **settings** (user\_id, terminal preferences, logging default, idle timeouts)

Every table that holds user data carries `user_id`, and every query filters by it.

## 8. API Sketch

- `POST /api/auth/login`, `POST /api/auth/logout`
- `GET/POST/PUT/DELETE /api/hosts`, `/api/credentials` (secrets write-only)
- `POST /api/hosts/{id}/connect` returns a one-time ticket; `POST /api/connect` for Quick Connect
- `POST /api/keys/generate`, `POST /api/hosts/{id}/deploy-key`
- `POST /api/hosts/{id}/favorite`
- `GET /api/history`, `GET /api/logs`, `GET /api/logs/{id}/download`, `DELETE /api/logs/{id}`
- `POST /api/export`, `POST /api/import`
- `GET /health`, `GET /metrics`
- `WS /ws/terminal?ticket=…`

## 9. Non-Functional Requirements

| Area | Requirement |
| --- | --- |
| Latency | Keystroke echo adds under 50 ms over network baseline on a LAN; engine cold start under 2 s |
| Capacity | 50 concurrent sessions per instance in MVP; target 100 |
| Resources | Idle engine consumes no process; Vert.x idle footprint stays small |
| Reliability | Clean session close on browser close; engine crash isolated from the gateway |
| Security | TLS everywhere; strict CSP; one-time tickets plus Origin check; SameSite cookies; secrets never logged |
| Portability | Gateway and engine run on Linux and Windows (native binaries; Docker for Linux) |
| Browsers | Latest Chrome, Edge, Firefox; Safari best effort |
| Operability | Environment-based config, JSON logs, `/health` and `/metrics`, OpenAPI spec |

## 10. Security and Trade-offs

- The browser cannot open SSH, so the backend is the SSH client and **sees session content in plaintext**. This is the accepted trade-off for central credential storage and history.
- **SSRF and pivoting:** a configurable allowed-CIDR list; loopback, link-local and cloud metadata addresses are blocked.
- **Cross-site WebSocket hijack:** one-time tickets, Origin validation.
- **Man-in-the-middle on the engine-to-target leg:** mandatory host-key verification.
- **XSS reading terminal output:** strict CSP, no HTML rendering of terminal data.
- **Vault compromise:** DB and master key kept apart, write-only secrets, restricted production access.
- **Shared source IP:** all users connect from the engine's IP, so one user's failed logins can trigger fail2ban for everyone; mitigated by per-user rate limits and by documenting allowlisting of the gateway IP.
- **Logs containing secrets:** logging is opt-in, warned, permission-restricted.

## 11. Success Metrics

- A user can go from login to an interactive shell in under 30 seconds on first use, and in 2 clicks (or Ctrl+K) for saved sessions.
- Keystroke test suite passes: vim, nano, tmux, htop, less, and shell history/line editing behave identically to a native terminal.
- 50 concurrent sessions run stably for one hour with no leaked goroutines or memory growth.
- Engine stops within the idle timeout plus 10 s after the last session ends, and restarts transparently on the next request.
- Zero secrets found in service logs during security testing.

## 12. Release Plan

| Phase | Scope |
| --- | --- |
| MVP (P0) | Login, credentials vault, hosts and saved sessions, quick connect, terminal with tabs and full key fidelity, host-key verification, favorites/recent/search, session history, plain-text logging, key generation and deployment, on-demand engine |
| Post-MVP (P1) | Post-connect scripts, import/export, log retention and encryption, terminal customization, KEK rotation |
| Later (P2) | Queue transport, remote agent, SFTP browser, SSO integration, session reattach after network drop |

## 13. Testing Approach

- **Unit:** crypto helpers, ownership checks, key parsing, engine lifecycle state machine.
- **Integration:** Docker OpenSSH servers (password, key, wrong key, changed host key, no authorized\_keys file for deploy test).
- **Terminal fidelity:** scripted checks and manual matrix of keys and TUI apps across Chrome, Edge and Firefox on Windows, macOS and Linux.
- **Lifecycle:** start-on-demand, idle stop, request during STOPPING, engine crash, concurrent first requests.
- **Security:** access to another user's host IDs, ticket reuse, cross-origin connect, SSRF targets, secrets absent from logs.
- **Load:** 50 to 100 concurrent sessions, heavy output with backpressure, network drops.

## 14. Open Questions

1. Which queue technology, if any, does the host product already run (for the future transport)?
2. Should the idle timeout be a global admin setting or per user?
3. Where should log files live (local disk path, size limits) and who cleans them up?
4. Should one-off Quick Connect attempts appear in history (host and user only, never the password)?
5. Does the host product provide an auth/SSO layer to plug into?
6. Is a separate Go process acceptable in the on-premise deployment, including packaging as a service on Windows and Linux?
