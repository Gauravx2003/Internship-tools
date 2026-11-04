# Go Session Engine — Stdio Server

## 1. Overview & Purpose

Under the revised architecture (spawning a single dedicated Go process per active session instead of a monolithic multi-session daemon with WebSockets):
- Communication between **Vert.x** and the **Go Session Engine** takes place over standard I/O pipes (`stdin` and `stdout`).
- No internal HTTP control server (`/health`, `/status`, `/shutdown`) or WebSocket listener is needed.
- Inbound commands (`open`, `input`, `resize`, `ack`, `close`, `deploy_key`) arrive on `stdin` formatted as single-line JSON envelopes delimited by newlines (`\n`).
- Outbound notifications (`state`, `output`, `error`, `closed`, `result`, `hostkey_challenge`) are written to `stdout` formatted as single-line JSON envelopes delimited by newlines (`\n`).
- All internal logging and diagnostics are strictly written to `stderr` to guarantee that the `stdout` stream remains uncorrupted JSON.

The **stdio server** is the transport foundation for this new architecture.

---

## 2. Architecture & File Structure

```
backend/session-engine/
├── go.mod
├── STDIO_SERVER.md
└── internal/
    ├── protocol/
    │   ├── envelope.go         # Envelope model, JSON marshaler/unmarshaler, typed payloads, error codes
    │   └── envelope_test.go    # Protocol serialization, legacy compatibility, and payload decoding tests
    └── stdio/
        ├── server.go           # Stdio Server (reader loop, dispatching, thread-safe writer, OutputSink)
        └── server_test.go      # Concurrent I/O, EOF, invalid JSON, cancellation, and dispatch tests
```

---

## 3. Protocol Envelope Specification

Both inbound and outbound messages share the unified envelope defined in **Module-And-API.md §4.1**:

```json
{
  "v": 1,
  "type": "open",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "seq": 1,
  "ts": "2026-11-04T12:00:00Z",
  "payload": { ... }
}
```

### Protocol Fields
- `v`: Integer protocol version (defaults to `1`).
- `type`: String message identifier (`open`, `input`, `resize`, `ack`, `close`, `state`, `output`, `error`, `closed`, etc.).
- `sessionId`: Unique UUID string for the session.
  - The unmarshaler supports both `"sessionId"` and legacy `"session.id"`.
  - The marshaler outputs both `"sessionId"` and `"session.id"` for full interoperability.
- `seq`: Monotonically increasing sequence number (int64).
- `ts`: ISO-8601 UTC timestamp string.
- `payload`: Message-specific JSON object.

---

## 4. Server Features

### 4.1 Inbound Reader Loop (`Run`)
- Reads newline-delimited JSON chunks from `io.Reader` (defaults to `os.Stdin`).
- Gracefully handles pipe closure (`io.EOF`) and process context cancellation.
- Validates JSON format; emits an `INVALID_PAYLOAD` error envelope if malformed JSON is received.
- Dispatches messages to handlers registered via `server.On(msgType, handler)`.
- Fallback unhandled messages route to `server.SetDefaultHandler(...)`.

### 4.2 Outbound Thread-Safe Writer (`Send`)
- Synchronized with an internal `sync.Mutex` to prevent concurrent writes from interleaving JSON lines.
- Formats envelopes as single-line JSON followed by `\n`.
- Automatically assigns sequence numbers (`atomic.Int64`), protocol version `1`, UTC timestamps, and active `sessionId`.
- Implements `OutputSink` (`SendOutput` and `SendClosed`), enabling zero-glue connection to terminal pumps.

### 4.3 High-Level Helper Methods
- `SendState(state, message string) error`
- `SendOutput(data []byte) error` (encodes base64 into `OutputPayload`)
- `SendHostkeyChallenge(keyType, fingerprint string) error`
- `SendError(code, message string) error`
- `SendClosed(reason string, exitStatus int) error`
- `SendResult(success bool, message string) error`

---

## 5. Verification & Tests

Unit tests cover:
- **`TestServer_InboundDispatch`**: Dispatching of `open`, `input`, `resize`, `ack`, and `close` messages.
- **`TestServer_OutboundHelpers`**: Verifying formatting, sequence numbers, and payload values for all helpers.
- **`TestServer_ConcurrentWrites`**: 10 concurrent goroutines sending 50 messages each, validating no byte corruption or race conditions.
- **`TestServer_InvalidJSONHandling`**: Error recovery when malformed lines arrive.
- **`TestServer_DefaultHandler`**: Unmapped message routing.
- **`TestServer_ContextCancellation`**: Immediate, clean termination upon context cancellation.

Run the test suite:
```bash
cd backend/session-engine
go test -v -race ./...
```
