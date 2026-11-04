package stdio

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"sync"
	"testing"
	"time"

	"github.com/gaurav/web-session/session-engine/internal/protocol"
)

type harness struct {
	srv    *Server
	in     *io.PipeWriter
	out    chan protocol.Envelope
	runErr chan error
}

func newHarness(t *testing.T, opts ...Option) *harness {
	t.Helper()
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()

	all := append([]Option{
		WithReader(inR),
		WithWriter(outW),
		WithLogger(log.New(io.Discard, "", 0)),
	}, opts...)
	srv := NewServer(all...)

	h := &harness{srv: srv, in: inW, out: make(chan protocol.Envelope, 8192), runErr: make(chan error, 1)}

	go func() {
		sc := bufio.NewScanner(outR)
		sc.Buffer(make([]byte, 0, 64*1024), 4<<20)
		for sc.Scan() {
			var e protocol.Envelope
			if json.Unmarshal(sc.Bytes(), &e) == nil {
				h.out <- e
			}
		}
		close(h.out)
	}()
	go func() { h.runErr <- srv.Run() }()

	t.Cleanup(func() {
		srv.Close()
		inW.Close()
		outW.Close()
	})
	return h
}

func (h *harness) send(t *testing.T, typ, payload string) {
	t.Helper()
	if payload == "" {
		payload = "{}"
	}
	if _, err := fmt.Fprintf(h.in, "{\"v\":1,\"type\":%q,\"sessionId\":\"s1\",\"payload\":%s}\n", typ, payload); err != nil {
		t.Fatalf("write to stdin failed: %v", err)
	}
}

func (h *harness) next(t *testing.T) protocol.Envelope {
	t.Helper()
	select {
	case e, ok := <-h.out:
		if !ok {
			t.Fatal("stdout closed before a message arrived")
		}
		return e
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for a message on stdout")
	}
	return protocol.Envelope{}
}

func (h *harness) nextError(t *testing.T) protocol.ErrorPayload {
	t.Helper()
	e := h.next(t)
	if e.Type != protocol.TypeError {
		t.Fatalf("expected an error message, got %q", e.Type)
	}
	var p protocol.ErrorPayload
	if err := e.DecodePayload(&p); err != nil {
		t.Fatal(err)
	}
	return p
}

// Bug 1: a long-running "open" must not stop the reader loop from reading
// "hostkey_decision" (which is what finishes the "open").
func TestAsyncOpenDoesNotBlockHostkeyDecision(t *testing.T) {
	h := newHarness(t)
	decision := make(chan struct{}, 1)

	h.srv.OnAsync(protocol.TypeOpen, func(ctx context.Context, env *protocol.Envelope) error {
		_ = h.srv.SendState(protocol.StateConnecting, "")
		select {
		case <-decision:
			return h.srv.SendState(protocol.StateConnected, "")
		case <-ctx.Done():
			return nil
		}
	})
	h.srv.On(protocol.TypeHostkeyDecision, func(ctx context.Context, env *protocol.Envelope) error {
		decision <- struct{}{}
		return nil
	})

	h.send(t, protocol.TypeOpen, "")
	if e := h.next(t); e.Type != protocol.TypeState {
		t.Fatalf("expected state, got %q", e.Type)
	}
	h.send(t, protocol.TypeHostkeyDecision, `{"decision":"accept"}`)

	e := h.next(t)
	var st protocol.StatePayload
	_ = e.DecodePayload(&st)
	if e.Type != protocol.TypeState || st.State != protocol.StateConnected {
		t.Fatalf("expected connected state, got %q %+v", e.Type, st)
	}
}

// Bug 1 + 4: "close" must be handled while "open" is still running, and Run must
// return even though stdin is still open.
func TestCloseWorksWhileOpenIsRunning(t *testing.T) {
	h := newHarness(t)
	openStarted := make(chan struct{})

	h.srv.OnAsync(protocol.TypeOpen, func(ctx context.Context, env *protocol.Envelope) error {
		close(openStarted)
		<-ctx.Done() // stands in for a hanging SSH dial
		return nil
	})
	h.srv.On(protocol.TypeClose, func(ctx context.Context, env *protocol.Envelope) error {
		_ = h.srv.SendClosed("client_closed", 0)
		return h.srv.Close()
	})

	h.send(t, protocol.TypeOpen, "")
	<-openStarted
	h.send(t, protocol.TypeClose, `{"reason":"client_closed"}`)

	if e := h.next(t); e.Type != protocol.TypeClosed {
		t.Fatalf("expected closed, got %q", e.Type)
	}
	select {
	case err := <-h.runErr:
		if err != nil {
			t.Fatalf("Run returned error: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not return after close")
	}
	if !h.srv.Wait(2 * time.Second) {
		t.Fatal("async handler did not finish after the context was cancelled")
	}
}

// Bug 4: Close() must wake Run even when stdin is blocked and silent.
func TestRunReturnsOnCloseWhileStdinBlocked(t *testing.T) {
	h := newHarness(t)
	time.Sleep(50 * time.Millisecond) // let Run start reading
	h.srv.Close()
	select {
	case <-h.runErr:
	case <-time.After(time.Second):
		t.Fatal("Run stayed blocked on stdin after Close")
	}
}

// Parent (Vert.x) gone: stdin EOF ends Run and cancels the context.
func TestStdinEOFEndsRunAndCancelsContext(t *testing.T) {
	h := newHarness(t)
	h.in.Close()
	select {
	case err := <-h.runErr:
		if err != nil {
			t.Fatalf("expected nil on EOF, got %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("Run did not return on EOF")
	}
	select {
	case <-h.srv.Context().Done():
	default:
		t.Fatal("context was not cancelled on EOF")
	}
}

// Bug 2: an oversized line is rejected, memory stays bounded, and the next
// message still works.
func TestOversizedLineIsRejectedAndNextMessageWorks(t *testing.T) {
	h := newHarness(t, WithMaxMessageSize(1024))
	got := make(chan struct{}, 1)
	h.srv.On(protocol.TypeResize, func(ctx context.Context, env *protocol.Envelope) error {
		got <- struct{}{}
		return nil
	})

	big := make([]byte, 200*1024) // bigger than the 64 KB read buffer too
	for i := range big {
		big[i] = 'a'
	}
	h.send(t, protocol.TypeInput, fmt.Sprintf(`{"data":"%s"}`, big))

	if p := h.nextError(t); p.Code != protocol.ErrCodeProtocolError {
		t.Fatalf("expected PROTOCOL_ERROR, got %s", p.Code)
	}

	h.send(t, protocol.TypeResize, `{"cols":100,"rows":30}`)
	select {
	case <-got:
	case <-time.After(2 * time.Second):
		t.Fatal("message after the oversized one was not handled")
	}
}

// Bug 3: failures are reported to Vert.x instead of only being logged.
func TestErrorsAreReportedToVertx(t *testing.T) {
	h := newHarness(t)

	h.srv.On("coded", func(ctx context.Context, env *protocol.Envelope) error {
		return &CodedError{Code: protocol.ErrCodeAuthFailed, Err: errors.New("bad password")}
	})
	h.srv.On("plain", func(ctx context.Context, env *protocol.Envelope) error {
		return errors.New("boom")
	})
	h.srv.On("panics", func(ctx context.Context, env *protocol.Envelope) error {
		panic("oops")
	})
	ok := make(chan struct{}, 1)
	h.srv.On("fine", func(ctx context.Context, env *protocol.Envelope) error {
		ok <- struct{}{}
		return nil
	})

	cases := []struct{ typ, wantCode string }{
		{"no-such-type", protocol.ErrCodeProtocolError},
		{"coded", protocol.ErrCodeAuthFailed},
		{"plain", protocol.ErrCodeInternal},
		{"panics", protocol.ErrCodeInternal},
	}
	for _, c := range cases {
		h.send(t, c.typ, "")
		if p := h.nextError(t); p.Code != c.wantCode {
			t.Errorf("%s: expected %s, got %s", c.typ, c.wantCode, p.Code)
		}
	}

	// malformed JSON line
	if _, err := io.WriteString(h.in, "this is not json\n"); err != nil {
		t.Fatal(err)
	}
	if p := h.nextError(t); p.Code != protocol.ErrCodeProtocolError {
		t.Errorf("malformed JSON: expected PROTOCOL_ERROR, got %s", p.Code)
	}

	// server still alive after a panic and bad input
	h.send(t, "fine", "")
	select {
	case <-ok:
	case <-time.After(2 * time.Second):
		t.Fatal("server stopped handling messages after errors")
	}
}

// Bug 5: seq order must equal write order, even with many writers.
func TestSeqMatchesWriteOrderUnderConcurrency(t *testing.T) {
	h := newHarness(t)
	const writers, each = 20, 50

	var wg sync.WaitGroup
	for w := 0; w < writers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < each; i++ {
				_ = h.srv.SendState(protocol.StateConnecting, "")
			}
		}()
	}
	wg.Wait()

	var last int64
	for i := 0; i < writers*each; i++ {
		e := h.next(t)
		if e.Seq != last+1 {
			t.Fatalf("message %d has seq %d, expected %d", i, e.Seq, last+1)
		}
		last = e.Seq
	}
}
