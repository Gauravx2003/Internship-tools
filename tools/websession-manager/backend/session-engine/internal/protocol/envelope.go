package protocol

import (
	"encoding/base64"
	"encoding/json"
	"time"
)

// ProtocolVersion is the current version of the Vert.x <-> Engine protocol.
const ProtocolVersion = 1

// Inbound message types (Vert.x -> Engine)
const (
	TypeOpen            = "open"
	TypeInput           = "input"
	TypeResize          = "resize"
	TypeAck             = "ack"
	TypeHostkeyDecision = "hostkey_decision"
	TypeClose           = "close"
	TypeDeployKey       = "deploy_key"
	TypePing            = "ping"
)

// Outbound message types (Engine -> Vert.x)
const (
	TypeState            = "state"
	TypeHostkeyChallenge = "hostkey_challenge"
	TypeOutput           = "output"
	TypeError            = "error"
	TypeClosed           = "closed"
	TypeResult           = "result"
	TypePong             = "pong"
)

// Engine standard error codes (Module-And-API.md §4.1)
const (
	ErrCodeDNSFailed         = "DNS_FAILED"
	ErrCodeConnectTimeout    = "CONNECT_TIMEOUT"
	ErrCodeConnectionRefused = "CONNECTION_REFUSED"
	ErrCodeAuthFailed        = "AUTH_FAILED"
	ErrCodeHostkeyMismatch   = "HOSTKEY_MISMATCH"
	ErrCodeHostkeyRejected   = "HOSTKEY_REJECTED"
	ErrCodePtyFailed         = "PTY_FAILED"
	ErrCodeSessionLimit      = "SESSION_LIMIT"
	ErrCodeInternal          = "INTERNAL"
	ErrCodeInvalidPayload    = "INVALID_PAYLOAD"
	ErrCodeProtocolError     = "PROTOCOL_ERROR"
)

// State values for StatePayload
const (
	StateConnecting     = "connecting"
	StateAuthenticating = "authenticating"
	StateConnected      = "connected"
)

// Envelope represents the standard message envelope exchanged between Vert.x
// and the Go Session Engine over standard pipes (stdin / stdout).
//
// Format:
//
//	{
//	  "v": 1,
//	  "type": "...",
//	  "session.id": "uuid",
//	  "seq": 123,
//	  "ts": "2026-10-19T10:00:00Z",
//	  "payload": { ... }
//	}
type Envelope struct {
	V         int             `json:"v"`
	Type      string          `json:"type"`
	SessionID string          `json:"session.id"`
	Seq       int64           `json:"seq"`
	Ts        string          `json:"ts"`
	Payload   json.RawMessage `json:"payload"`
}

// envelopeJSON is an internal struct used for marshalling and unmarshalling
// to handle both "sessionId" and legacy "session.id" seamlessly.
type envelopeJSON struct {
	V         int             `json:"v"`
	Type      string          `json:"type"`
	SessionID string          `json:"session.id,omitempty"`
	Seq       int64           `json:"seq"`
	Ts        string          `json:"ts"`
	Payload   json.RawMessage `json:"payload"`
}

// UnmarshalJSON parses envelope JSON, supporting both "sessionId" and "session.id".
func (e *Envelope) UnmarshalJSON(data []byte) error {
	var raw envelopeJSON
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	e.V = raw.V
	e.Type = raw.Type
	if raw.SessionID != "" {
		e.SessionID = raw.SessionID
	}

	e.Seq = raw.Seq
	e.Ts = raw.Ts
	e.Payload = raw.Payload
	return nil
}

// MarshalJSON serializes the envelope, emitting both "sessionId" and "session.id"
// for compatibility with all Vert.x decoders.
func (e Envelope) MarshalJSON() ([]byte, error) {
	v := e.V
	if v == 0 {
		v = ProtocolVersion
	}
	ts := e.Ts
	if ts == "" {
		ts = time.Now().UTC().Format(time.RFC3339)
	}
	payload := e.Payload
	if len(payload) == 0 {
		payload = json.RawMessage("{}")
	}

	return json.Marshal(envelopeJSON{
		V:         v,
		Type:      e.Type,
		SessionID: e.SessionID,
		Seq:       e.Seq,
		Ts:        ts,
		Payload:   payload,
	})
}

// NewEnvelope constructs a new Envelope with default protocol version and current timestamp.
func NewEnvelope(msgType, sessionID string, seq int64, payload json.RawMessage) *Envelope {
	return &Envelope{
		V:         ProtocolVersion,
		Type:      msgType,
		SessionID: sessionID,
		Seq:       seq,
		Ts:        time.Now().UTC().Format(time.RFC3339),
		Payload:   payload,
	}
}

// NewEnvelopeWithPayload constructs an Envelope by JSON-encoding the given payload object.
func NewEnvelopeWithPayload(msgType, sessionID string, seq int64, payload any) (*Envelope, error) {
	raw, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	return NewEnvelope(msgType, sessionID, seq, raw), nil
}

// DecodePayload unmarshals the envelope's raw payload into target.
func (e *Envelope) DecodePayload(target any) error {
	if len(e.Payload) == 0 {
		return nil
	}
	return json.Unmarshal(e.Payload, target)
}

// --- Payload Structures ---

// LoggingConfig defines session logging options in OpenPayload.
type LoggingConfig struct {
	Enabled      bool   `json:"enabled"`
	StripControl bool   `json:"stripControl"`
	Path         string `json:"path"`
}

// OpenPayload is sent by Vert.x to initiate an SSH session.
type OpenPayload struct {
	Host                string         `json:"host"`
	Port                int            `json:"port"`
	Username            string         `json:"username"`
	AuthType            string         `json:"authType"`
	Secret              string         `json:"secret"`
	Passphrase          string         `json:"passphrase,omitempty"`
	ExpectedFingerprint string         `json:"expectedFingerprint,omitempty"`
	Term                string         `json:"term,omitempty"`
	Cols                uint32         `json:"cols,omitempty"`
	Rows                uint32         `json:"rows,omitempty"`
	Logging             *LoggingConfig `json:"logging,omitempty"`
}

// InputPayload carries user input bytes or commands from Vert.x to the Engine.
type InputPayload struct {
	Bytes   string `json:"bytes,omitempty"`   // Base64 encoded terminal input bytes
	Data    string `json:"data,omitempty"`    // Alternative base64 encoded bytes
	Command string `json:"command,omitempty"` // Plain text command (testing / scripting fallback)
}

// DecodeBytes decodes input bytes from base64 (Bytes/Data) or raw Command.
func (p *InputPayload) DecodeBytes() ([]byte, error) {
	str := p.Bytes
	if str == "" {
		str = p.Data
	}
	if str != "" {
		return base64.StdEncoding.DecodeString(str)
	}
	if p.Command != "" {
		return []byte(p.Command), nil
	}
	return nil, nil
}

// ResizePayload carries terminal dimensions from Vert.x to the Engine.
type ResizePayload struct {
	Cols uint32 `json:"cols"`
	Rows uint32 `json:"rows"`
}

// AckPayload acknowledges bytes consumed for flow control backpressure.
type AckPayload struct {
	Bytes         int64 `json:"bytes,omitempty"`
	BytesConsumed int64 `json:"bytesConsumed,omitempty"`
}

// Consumed returns the number of acknowledged bytes.
func (p *AckPayload) Consumed() int64 {
	if p.BytesConsumed > 0 {
		return p.BytesConsumed
	}
	return p.Bytes
}

// HostkeyDecisionPayload carries user's trust decision for remote host fingerprint.
type HostkeyDecisionPayload struct {
	Decision string `json:"decision"` // "accept", "reject", "trust"
}

// ClosePayload requests termination of the session.
type ClosePayload struct {
	Reason string `json:"reason"`
}

// DeployKeyPayload carries one-shot public key deployment details.
type DeployKeyPayload struct {
	Host      string `json:"host"`
	Port      int    `json:"port"`
	Username  string `json:"username"`
	Password  string `json:"password"`
	PublicKey string `json:"publicKey"`
}

// StatePayload reports SSH connection state transitions.
type StatePayload struct {
	State   string `json:"state"`
	Message string `json:"message,omitempty"`
}

// HostkeyChallengePayload prompts Vert.x for user trust verification.
type HostkeyChallengePayload struct {
	KeyType     string `json:"keyType"`
	Fingerprint string `json:"fingerprint"`
}

// OutputPayload carries terminal stdout chunks to Vert.x.
type OutputPayload struct {
	Bytes string `json:"bytes"`         // Base64 encoded bytes
	Raw   string `json:"raw,omitempty"` // Plain text representation (optional/diagnostic)
}

// NewOutputPayload creates an OutputPayload with base64 encoding and raw string.
func NewOutputPayload(data []byte) OutputPayload {
	return OutputPayload{
		Bytes: base64.StdEncoding.EncodeToString(data),
		Raw:   string(data),
	}
}

// ErrorPayload carries error details from Engine to Vert.x.
type ErrorPayload struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// ClosedPayload informs Vert.x that the session has exited.
type ClosedPayload struct {
	Reason     string `json:"reason"`
	ExitStatus int    `json:"exitStatus"`
}

// ResultPayload carries the outcome of a one-shot operation (e.g. deploy_key).
type ResultPayload struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
}
