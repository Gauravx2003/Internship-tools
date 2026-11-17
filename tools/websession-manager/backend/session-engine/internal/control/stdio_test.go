package control

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gaurav/web-session/session-engine/internal/session"
)

type lineReader struct {
	reader *bufio.Reader
}

func newLineReader(r io.Reader) *lineReader {
	return &lineReader{reader: bufio.NewReader(r)}
}

func (lr *lineReader) readEnvelope(timeout time.Duration) (map[string]any, error) {
	type result struct {
		env map[string]any
		err error
	}
	ch := make(chan result, 1)

	go func() {
		line, err := lr.reader.ReadBytes('\n')
		if err != nil {
			ch <- result{nil, err}
			return
		}
		var env map[string]any
		if err := json.Unmarshal(bytes.TrimSpace(line), &env); err != nil {
			ch <- result{nil, err}
			return
		}
		ch <- result{env, nil}
	}()

	select {
	case res := <-ch:
		return res.env, res.err
	case <-time.After(timeout):
		return nil, context.DeadlineExceeded
	}
}

func sendJSONLine(w io.Writer, env map[string]any) error {
	b, err := json.Marshal(env)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	_, err = w.Write(b)
	return err
}

func TestStdioServer_PingPong(t *testing.T) {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()

	server := NewStdioServer(inR, outW, nil, 5)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = server.Run(ctx)
	}()

	lr := newLineReader(outR)

	// Send ping
	err := sendJSONLine(inW, map[string]any{
		"type":      "ping",
		"sessionId": "test-sess-1",
	})
	if err != nil {
		t.Fatalf("failed to write ping: %v", err)
	}

	env, err := lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read pong envelope: %v", err)
	}

	if env["type"] != "pong" {
		t.Errorf("expected type=pong, got %v", env["type"])
	}
	if env["sessionId"] != "test-sess-1" {
		t.Errorf("expected sessionId=test-sess-1, got %v", env["sessionId"])
	}

	_ = inW.Close()
	wg.Wait()
}

func TestStdioServer_MockOpenAndClose(t *testing.T) {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()

	server := NewStdioServer(inR, outW, nil, 5)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		_ = server.Run(ctx)
	}()

	lr := newLineReader(outR)

	// Open mock session
	err := sendJSONLine(inW, map[string]any{
		"type":      "open",
		"sessionId": "mock-sess-1",
		"payload": map[string]any{
			"host": "localhost",
		},
	})
	if err != nil {
		t.Fatalf("failed to write open: %v", err)
	}

	env, err := lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read state envelope: %v", err)
	}

	if env["type"] != "state" {
		t.Errorf("expected type=state, got %v", env["type"])
	}
	payload, ok := env["payload"].(map[string]any)
	if !ok || payload["state"] != "connected" {
		t.Errorf("expected state=connected, got %v", env["payload"])
	}

	if count := server.ActiveSessionCount(); count != 1 {
		t.Errorf("expected ActiveSessionCount=1, got %d", count)
	}

	// Close mock session
	err = sendJSONLine(inW, map[string]any{
		"type":      "close",
		"sessionId": "mock-sess-1",
		"payload": map[string]any{
			"reason": "user_exit",
		},
	})
	if err != nil {
		t.Fatalf("failed to write close: %v", err)
	}

	env, err = lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read closed envelope: %v", err)
	}

	if env["type"] != "closed" {
		t.Errorf("expected type=closed, got %v", env["type"])
	}
	closedPayload, ok := env["payload"].(map[string]any)
	if !ok || closedPayload["reason"] != "user_exit" {
		t.Errorf("expected reason=user_exit, got %v", env["payload"])
	}

	if count := server.ActiveSessionCount(); count != 0 {
		t.Errorf("expected ActiveSessionCount=0, got %d", count)
	}

	_ = inW.Close()
}

func TestStdioServer_MultiplexUpToLimit(t *testing.T) {
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()

	// Max sessions = 3 for this test
	mgr := session.NewManager(3)
	server := NewStdioServer(inR, outW, mgr, 3)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		_ = server.Run(ctx)
	}()

	lr := newLineReader(outR)

	// Open 3 sessions (sess-1, sess-2, sess-3)
	for i := 1; i <= 3; i++ {
		sessID := strings.TrimSpace("sess-" + string(rune('0'+i)))
		err := sendJSONLine(inW, map[string]any{
			"type":      "open",
			"sessionId": sessID,
			"payload": map[string]any{
				"host": "localhost",
			},
		})
		if err != nil {
			t.Fatalf("failed to open %s: %v", sessID, err)
		}

		env, err := lr.readEnvelope(2 * time.Second)
		if err != nil {
			t.Fatalf("failed to read connected state for %s: %v", sessID, err)
		}
		if env["type"] != "state" || env["sessionId"] != sessID {
			t.Fatalf("unexpected envelope for %s: %v", sessID, env)
		}
	}

	if count := server.ActiveSessionCount(); count != 3 {
		t.Fatalf("expected ActiveSessionCount=3, got %d", count)
	}

	// Now attempt 4th session - should fail because maxSessions=3
	err := sendJSONLine(inW, map[string]any{
		"type":      "open",
		"sessionId": "sess-4",
		"payload": map[string]any{
			"host": "localhost",
		},
	})
	if err != nil {
		t.Fatalf("failed to write 4th open: %v", err)
	}

	// Expect error envelope
	errEnv, err := lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read error envelope for sess-4: %v", err)
	}
	if errEnv["type"] != "error" {
		t.Errorf("expected error envelope for sess-4, got %v", errEnv["type"])
	}

	// Expect closed envelope for sess-4
	closedEnv, err := lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read closed envelope for sess-4: %v", err)
	}
	if closedEnv["type"] != "closed" {
		t.Errorf("expected closed envelope for sess-4, got %v", closedEnv["type"])
	}

	// Count must still be 3
	if count := server.ActiveSessionCount(); count != 3 {
		t.Errorf("expected ActiveSessionCount=3, got %d", count)
	}

	// Now close sess-1 -> freeing up a slot!
	err = sendJSONLine(inW, map[string]any{
		"type":      "close",
		"sessionId": "sess-1",
	})
	if err != nil {
		t.Fatalf("failed to send close for sess-1: %v", err)
	}

	closedSess1, err := lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read closed for sess-1: %v", err)
	}
	if closedSess1["type"] != "closed" || closedSess1["sessionId"] != "sess-1" {
		t.Errorf("expected closed for sess-1, got %v", closedSess1)
	}

	if count := server.ActiveSessionCount(); count != 2 {
		t.Fatalf("expected ActiveSessionCount=2 after closing sess-1, got %d", count)
	}

	// Now open sess-4 again -> should SUCCEED now that slot is available!
	err = sendJSONLine(inW, map[string]any{
		"type":      "open",
		"sessionId": "sess-4",
		"payload": map[string]any{
			"host": "localhost",
		},
	})
	if err != nil {
		t.Fatalf("failed to send open for sess-4: %v", err)
	}

	stateSess4, err := lr.readEnvelope(2 * time.Second)
	if err != nil {
		t.Fatalf("failed to read connected state for sess-4: %v", err)
	}
	if stateSess4["type"] != "state" || stateSess4["sessionId"] != "sess-4" {
		t.Errorf("expected state for sess-4, got %v", stateSess4)
	}

	if count := server.ActiveSessionCount(); count != 3 {
		t.Errorf("expected ActiveSessionCount=3 after re-opening sess-4, got %d", count)
	}

	_ = inW.Close()
}
