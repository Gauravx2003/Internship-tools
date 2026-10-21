# Web Session Manager — Modules and API Architecture*Author: Gaurav Daware · October 2026 · Draft v1 · Companion to the PRD*

## 1. Purpose

This document lists every module in the system, what each one is responsible for, how the modules talk to each other, and how the API is structured. It is written to be reviewed before implementation starts.

## 2. System Overview

```
+---------------------------------------------------------------+
|  BROWSER  (Vue 3 + TypeScript + Pinia + xterm.js)             |
+---------------------------------------------------------------+
        | HTTPS  REST /api/v1/*            | WSS /ws/terminal
        v                                  v
+---------------------------------------------------------------+
|  VERT.X SERVICE (Java)                                        |
|                                                               |
|  HTTP layer:  Router -> Middleware -> Handlers                |
|  Domain:      Auth | Hosts | Credentials(Vault) | Keys |       |
|               History | Logs | KnownHosts | Export/Import      |
|  Gateway:     Ticket Service | Terminal Gateway (WS bridge)    |
|  Engine side: Engine Manager | EngineChannel (transport SPI)   |
|  Infra:       Repositories | Crypto | Config | Metrics         |
+---------------------------------------------------------------+
        | PostgreSQL                 | EngineChannel (WebSocket now,
        v                            |  queue later), localhost only
+----------------+                   v
|  PostgreSQL    |   +--------------------------------------------+
+----------------+   |  GO SESSION ENGINE                         |
                     |  Control API | Session Manager | SSH Client |
                     |  Host-Key Verifier | PTY Pump | Logger |   |
                     |  Key Deployer | Lifecycle | Dialer (SPI)   |
                     +--------------------------------------------+
                             | SSH (MVP: direct)   | later: via Agent
                             v                     v
                     +------------------+   +------------------+
                     |  Remote server   |   | Remote agent     |
                     +------------------+   +------------------+
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
| **ws-client** | WebSocket connection per tab: ticket handshake, message envelope encode/decode, heartbeat, reconnect action, backpressure acks. |
| **host-key-dialog** | Shows fingerprint on first connect (trust / cancel) and mismatch warning. |
| **history-logs** | Session history table with filters; list, download, delete session logs. |
| **settings** | Terminal preferences (font, size, theme, scrollback), logging default, idle timeout display. |
| **shell/layout** | App frame, navigation, theme, toasts. Packaged so it can mount inside a larger host app later. |

### 3.2 Vert.x service (Java)

Organized in layers: **handler** (HTTP/WS, validation, no business logic) → **service** (business rules, ownership checks, transactions) → **repository** (SQL only). Modules talk through service interfaces; long-running modules run as verticles.

| Module | Responsibility | Depends on |
| --- | --- | --- |
| **http-server** | Starts Vert.x Web, mounts routes, serves the built Vue app and OpenAPI spec, TLS, `/health`, `/metrics`. | all handlers |
| **middleware** | Cross-cutting handlers in order: request ID, access log (no bodies), CORS/Origin check, session cookie auth, CSRF, rate limit, body size limit, validation, central error handler (maps exceptions to the error format). | auth |
| **auth** | Login/logout, Argon2id hashing on worker threads, session store with inactivity expiry, current-user context. Behind an `AuthenticationProvider` so SSO can replace it later. | users repo |
| **hosts** | CRUD for hosts/saved sessions, favorites, tags, last-used and use-count updates, search with pagination, post-connect script storage. | repos, credentials (link check) |
| **credentials (vault)** | CRUD for credentials, validates uploaded keys by parsing, encrypts/decrypts secrets, never returns secrets from the API; exposes `decryptForConnect(credentialId, userId)` to internal modules only. | crypto, repos |
| **crypto** | AES-256-GCM encrypt/decrypt, DEK generation, KEK wrap/unwrap, KEK loading from env/file, KEK version handling, Argon2id helper, secure random. All on worker threads. | config |
| **keys** | Generates Ed25519/RSA key pairs, formats OpenSSH public key and fingerprint, stores private key via the vault, orchestrates key deployment (asks the engine to deploy). | vault, engine-manager |
| **target-policy** | Validates connection targets: allowed CIDR list, blocks loopback, link-local and metadata addresses, resolves DNS once and pins the IP. | config |
| **known-hosts** | Stores and looks up trusted fingerprints per user and host; records trust decisions; replacing a fingerprint requires explicit confirmation. | repos |
| **ticket-service** | Issues single-use, 30 s WebSocket tickets bound to user + host + session intent; validates and consumes them; in-memory map with TTL sweep. | none |
| **terminal-gateway** | Accepts `/ws/terminal`, validates ticket and Origin, creates the terminal session, bridges browser frames to the engine channel and back, applies backpressure, enforces per-user session cap and idle timeout, handles host-key prompt relay. | ticket, vault, engine-manager, known-hosts, history, target-policy |
| **engine-manager** | Owns the engine process: start on demand, readiness wait, idle timer, graceful stop, crash detection and restart on next request, active-session counting, request queuing during STARTING/STOPPING. Implements the state machine STOPPED → STARTING → RUNNING → IDLE → STOPPING. | engine-channel, config |
| **engine-channel (SPI)** | Interface: `open`, `send`, `onMessage`, `resize`, `close`, `health`. MVP implementation `WebSocketEngineChannel`; future `QueueEngineChannel`. Also owns the message envelope codec. | none |
| **history** | Creates a history record at connect, updates on close (status, reason, duration), lists/filter/search for the user, updates `last_used_at` and counters. | repos |
| **logs** | Stores log metadata, serves log list/download/delete, enforces retention job, restricts file permissions. | repos, file storage |
| **export-import** | Builds the encrypted export (Argon2id + AES-GCM), validates imports (schema, size, version), always creates new records. | hosts, credentials, crypto |
| **config** | Typed configuration from environment/file: DB, KEK source, engine path and timeouts, allowed CIDRs, limits. Fails fast on missing values. | none |
| **persistence** | Reactive PostgreSQL pool, repositories, migrations (Flyway), transaction helper. | none |
| **observability** | JSON structured logs, metrics (active sessions, engine state, ticket counts, login failures), health checks (DB, engine). | all |

### 3.3 Go session engine

| Module | Responsibility |
| --- | --- |
| **control-server** | Listens on loopback/Unix socket only; authenticates Vert.x with a shared token (mTLS later); exposes `GET /health`, `GET /status` (active session count, uptime), `POST /shutdown` (graceful), and the session WebSocket. |
| **protocol** | Message envelope, command/event types, versioning, encode/decode. The same schema is used regardless of transport. |
| **session-manager** | Registry of active sessions by `sessionId`, creates and closes sessions, enforces engine-side limits, counts active sessions, drains on shutdown. |
| **ssh-client** | Dials through `SessionDialer`, performs SSH handshake, authenticates (password, private key, key + passphrase), requests PTY, starts shell, sets keepalives. |
| **dialer (SPI)** | `SessionDialer` interface. MVP: `DirectDialer` (TCP to host:port with timeout). Later: `AgentDialer`. |
| **hostkey-verifier** | SSH `HostKeyCallback`. If the open request carries an expected fingerprint, compares it (mismatch → abort with `HOSTKEY_MISMATCH`). If not, pauses the handshake, sends a `hostkey_challenge` event, and waits for `hostkey_decision`. |
| **pty-pump** | Two goroutines per session: remote output → `output` events (chunked, flow-controlled by acks) and `input` commands → remote stdin; applies window-resize requests. |
| **session-logger** | Writes plain-text session log when enabled in the open request: optional control-sequence stripping, restricted file permissions, flush on close. |
| **key-deployer** | One-shot operation: connect with password, create `~/.ssh` with correct modes, append public key if absent, report result. |
| **diagnostics** | One-shot test-connection steps (DNS, TCP, SSH banner, auth) used by a later test feature. |
| **lifecycle** | Emits ready signal on startup, handles SIGTERM, closes sessions cleanly, wipes credentials from memory after use. |

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
| POST | `/hosts/{id}/connect` | Checks ownership and target policy, returns `{ ticket, wsUrl, expiresIn }` |
| POST | `/connect` | Quick connect with one-off host/port/username/secret; secret held in memory against the ticket only, never stored |
| POST | `/hosts/{id}/known-host/replace` | Confirm replacement of a stored fingerprint |
| GET | `/known-hosts` | List trusted fingerprints |

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

`WS /ws/terminal?ticket=<one-time ticket>`

- The server validates the ticket (unused, not expired, bound to this user and host) and the `Origin` header, then consumes the ticket.
- Same envelope as the engine protocol, with browser-facing types:

| Direction | Type | Payload |
| --- | --- | --- |
| Browser → Server | `input` | text or bytes typed by the user |
| Browser → Server | `resize` | cols, rows |
| Browser → Server | `ack` | bytes rendered (flow control) |
| Browser → Server | `hostkey_decision` | accept / reject |
| Browser → Server | `close` | none |
| Browser → Server | `ping` | none |
| Server → Browser | `state` | engineStarting / connecting / authenticating / connected |
| Server → Browser | `hostkey_prompt` | keyType, fingerprint, isMismatch, storedFingerprint |
| Server → Browser | `output` | bytes |
| Server → Browser | `error` | code, message |
| Server → Browser | `closed` | reason |
| Server → Browser | `pong` | none |

The `input` and `output` payloads are binary WebSocket frames in practice, with a small header carrying the type, to avoid base64 overhead on the browser leg. The JSON envelope is used for control messages.

## 6. Key Flows

### 6.1 Connect with first-time host key

1. Browser: `POST /hosts/{id}/connect` → Vert.x checks ownership, target policy, session cap.
2. Engine Manager ensures the engine is RUNNING (starts it if STOPPED; the browser will see `state: engineStarting`).
3. Vert.x returns a ticket; browser opens `/ws/terminal`.
4. Terminal Gateway validates ticket and Origin, calls `vault.decryptForConnect`, looks up `known-hosts`, then sends `open` to the engine (with `expectedFingerprint` if known).
5. Engine dials, receives the server key. If no expected fingerprint, it sends `hostkey_challenge`; Vert.x relays `hostkey_prompt` to the browser.
6. User accepts; browser sends `hostkey_decision`; Vert.x stores the fingerprint in `known-hosts` and forwards the decision; engine continues authentication.
7. Engine requests PTY and shell and sends `state: connected`; the post-connect script (if any) is sent as `input` with delays.
8. Bytes flow with acknowledgements for flow control. History record is created at start.
9. On close, either side sends `closed`; Vert.x updates history, decrements the active count (possibly starting the idle timer), and the engine closes the log file and wipes secrets.

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
  gateway/                  Java Vert.x
    src/main/java/.../http|middleware|auth|hosts|credentials|crypto|
                      keys|policy|knownhosts|ticket|terminal|engine|
                      history|logs|exportimport|config|persistence|observability
    src/main/resources/db/migration/
  engine/                   Go
    cmd/engine/main.go
    internal/control|protocol|session|sshclient|dialer|hostkey|pty|logger|
             keydeploy|diag|lifecycle
  api/openapi.yaml          Contract (source of truth)
  api/engine-protocol.md    Vert.x <-> engine message schema
  deploy/                   Docker, systemd, Windows service files
  .github/workflows/        CI matrix: Ubuntu + Windows
```

## 9. Cross-Cutting Concerns

| Concern | Approach |
| --- | --- |
| Threading | Event loop never blocks; hashing, crypto, key generation and file I/O on worker threads |
| Security | TLS, strict CSP, Origin checks, one-time tickets, per-user ownership on every query, target allow-list, secrets never logged, secrets zeroed after use |
| Errors | Typed exceptions in services, one central mapper to the API error format |
| Observability | Request ID propagated to engine messages; JSON logs; metrics for sessions, engine state, failures |
| Configuration | Environment variables with validation at startup |
| Testing | Unit tests per module; contract tests against the OpenAPI spec; engine protocol tests with a fake Vert.x client; integration tests with Docker OpenSSH servers |
| Windows support | No OS-specific paths; services via WinSW (JAR) and kardianos/service (Go) |

## 10. Suggested Build Order

1. Contracts: `openapi.yaml`, engine protocol, `EngineChannel` and `SessionDialer` interfaces; repo skeleton and CI.
2. Gateway foundation: config, persistence, middleware, auth.
3. Hosts, credentials, crypto (vault).
4. Engine core: control server, protocol, SSH client, PTY pump, host-key verifier.
5. Engine Manager, Terminal Gateway, tickets — first end-to-end shell.
6. Frontend terminal, tabs, host-key dialog.
7. Known hosts, history, logs, favorites, quick access.
8. Key generation and deployment.
9. Hardening: limits, target policy, load tests, Windows packaging.

## 11. Assumptions and Open Points for the Mentor

1. One engine process per gateway instance in the MVP (several engines only after the queue transport).
2. Engine is started as a child process of the gateway; confirm this is acceptable versus a separately managed service.
3. Engine and gateway run on the same machine (loopback/Unix socket) in the MVP.
4. Log files live on the gateway host's disk; confirm path, size limits and retention.
5. Confirm which queue technology the host product already runs, if any.
6. Confirm whether the host product's auth should replace the built-in login early.
