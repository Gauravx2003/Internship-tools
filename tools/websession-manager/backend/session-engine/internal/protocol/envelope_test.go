package protocol_test

import (
	"encoding/base64"
	"encoding/json"
	"testing"

	"github.com/gaurav/web-session/session-engine/internal/protocol"
)

func TestEnvelope_MarshalAndUnmarshal(t *testing.T) {
	openPayload := protocol.OpenPayload{
		Host:     "192.168.1.10",
		Port:     22,
		Username: "testuser",
		AuthType: "password",
		Secret:   "secret123",
		Cols:     80,
		Rows:     24,
	}

	env, err := protocol.NewEnvelopeWithPayload(protocol.TypeOpen, "sess-123", 1, openPayload)
	if err != nil {
		t.Fatalf("Failed to create envelope: %v", err)
	}

	data, err := json.Marshal(env)
	if err != nil {
		t.Fatalf("Failed to marshal envelope: %v", err)
	}

	// Verify both "sessionId" and "session.id" exist in JSON
	var rawMap map[string]any
	if err := json.Unmarshal(data, &rawMap); err != nil {
		t.Fatalf("Failed to unmarshal into map: %v", err)
	}
	if rawMap["sessionId"] != "sess-123" {
		t.Errorf("Expected sessionId 'sess-123', got '%v'", rawMap["sessionId"])
	}
	if rawMap["session.id"] != "sess-123" {
		t.Errorf("Expected session.id 'sess-123', got '%v'", rawMap["session.id"])
	}

	// Unmarshal back into Envelope
	var decoded protocol.Envelope
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("Failed to unmarshal back: %v", err)
	}
	if decoded.SessionID != "sess-123" {
		t.Errorf("Expected SessionID 'sess-123', got '%s'", decoded.SessionID)
	}
	if decoded.Type != protocol.TypeOpen {
		t.Errorf("Expected Type '%s', got '%s'", protocol.TypeOpen, decoded.Type)
	}

	// Verify payload decode
	var decodedPayload protocol.OpenPayload
	if err := decoded.DecodePayload(&decodedPayload); err != nil {
		t.Fatalf("Failed to decode payload: %v", err)
	}
	if decodedPayload.Host != "192.168.1.10" || decodedPayload.Port != 22 {
		t.Errorf("Unexpected payload contents: %+v", decodedPayload)
	}
}

func TestEnvelope_UnmarshalLegacySessionDotID(t *testing.T) {
	jsonStr := `{"v":1,"type":"input","session.id":"sess-dot-999","seq":5,"payload":{"bytes":"aGVsbG8="}}`
	var env protocol.Envelope
	if err := json.Unmarshal([]byte(jsonStr), &env); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}
	if env.SessionID != "sess-dot-999" {
		t.Errorf("Expected SessionID 'sess-dot-999', got '%s'", env.SessionID)
	}

	var input protocol.InputPayload
	if err := env.DecodePayload(&input); err != nil {
		t.Fatalf("Failed to decode input payload: %v", err)
	}
	bytes, err := input.DecodeBytes()
	if err != nil {
		t.Fatalf("Failed to decode bytes: %v", err)
	}
	if string(bytes) != "hello" {
		t.Errorf("Expected 'hello', got '%s'", string(bytes))
	}
}

func TestInputPayload_DecodeBytes(t *testing.T) {
	// Base64 Bytes
	p1 := protocol.InputPayload{Bytes: base64.StdEncoding.EncodeToString([]byte("ls -la\n"))}
	b1, err := p1.DecodeBytes()
	if err != nil || string(b1) != "ls -la\n" {
		t.Errorf("Failed decoding Bytes: %s, err: %v", string(b1), err)
	}

	// Base64 Data
	p2 := protocol.InputPayload{Data: base64.StdEncoding.EncodeToString([]byte("pwd\n"))}
	b2, err := p2.DecodeBytes()
	if err != nil || string(b2) != "pwd\n" {
		t.Errorf("Failed decoding Data: %s, err: %v", string(b2), err)
	}

	// Plaintext Command
	p3 := protocol.InputPayload{Command: "whoami\n"}
	b3, err := p3.DecodeBytes()
	if err != nil || string(b3) != "whoami\n" {
		t.Errorf("Failed decoding Command: %s, err: %v", string(b3), err)
	}
}

func TestAckPayload_Consumed(t *testing.T) {
	ack1 := protocol.AckPayload{BytesConsumed: 1024}
	if ack1.Consumed() != 1024 {
		t.Errorf("Expected 1024, got %d", ack1.Consumed())
	}

	ack2 := protocol.AckPayload{Bytes: 512}
	if ack2.Consumed() != 512 {
		t.Errorf("Expected 512, got %d", ack2.Consumed())
	}
}

func TestOutputPayload(t *testing.T) {
	data := []byte("welcome to terminal\r\n")
	out := protocol.NewOutputPayload(data)
	if out.Raw != string(data) {
		t.Errorf("Expected raw %q, got %q", string(data), out.Raw)
	}
	decoded, err := base64.StdEncoding.DecodeString(out.Bytes)
	if err != nil || string(decoded) != string(data) {
		t.Errorf("Failed to decode base64 output: %v", err)
	}
}
