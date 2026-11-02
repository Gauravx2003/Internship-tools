package pty

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gaurav/web-session/session-engine/internal/sshclient"
	"golang.org/x/crypto/ssh"
)

// mockTerminal implements pty.Terminal, pty.Waiter, and io.Closer for testing.
type mockTerminal struct {
	stdinReader  *io.PipeReader
	stdinWriter  *io.PipeWriter
	stdoutReader *io.PipeReader
	stdoutWriter *io.PipeWriter
	lastCols     atomic.Uint32
	lastRows     atomic.Uint32
	resizeCount  atomic.Int32
	waitErr      error
	closeOnce    sync.Once
	closed       atomic.Bool
}

func newMockTerminal(waitErr error) *mockTerminal {
	stdinR, stdinW := io.Pipe()
	stdoutR, stdoutW := io.Pipe()
	return &mockTerminal{
		stdinReader:  stdinR,
		stdinWriter:  stdinW,
		stdoutReader: stdoutR,
		stdoutWriter: stdoutW,
		waitErr:      waitErr,
	}
}

func (m *mockTerminal) Stdin() io.WriteCloser {
	return m.stdinWriter
}

func (m *mockTerminal) Stdout() io.Reader {
	return m.stdoutReader
}

func (m *mockTerminal) Resize(cols, rows uint32) error {
	m.lastCols.Store(cols)
	m.lastRows.Store(rows)
	m.resizeCount.Add(1)
	return nil
}

func (m *mockTerminal) Wait() error {
	return m.waitErr
}

func (m *mockTerminal) Close() error {
	m.closeOnce.Do(func() {
		m.closed.Store(true)
		_ = m.stdinWriter.Close()
		_ = m.stdoutWriter.Close()
		_ = m.stdinReader.Close()
		_ = m.stdoutReader.Close()
	})
	return nil
}

// mockSink collects output bytes and records close events.
type mockSink struct {
	mu          sync.Mutex
	output      []byte
	closedCh    chan struct{}
	closeReason string
	exitStatus  int
	outputCount int
}

func newMockSink() *mockSink {
	return &mockSink{
		closedCh: make(chan struct{}),
	}
}

func (s *mockSink) SendOutput(data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.output = append(s.output, data...)
	s.outputCount++
	return nil
}

func (s *mockSink) SendClosed(reason string, exitStatus int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closeReason = reason
	s.exitStatus = exitStatus
	select {
	case <-s.closedCh:
	default:
		close(s.closedCh)
	}
	return nil
}

func (s *mockSink) GetOutput() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]byte, len(s.output))
	copy(out, s.output)
	return out
}

func TestPump_NewPumpValidation(t *testing.T) {
	sink := newMockSink()
	term := newMockTerminal(nil)

	// Nil terminal
	if _, err := NewPump(context.Background(), nil, sink, DefaultConfig()); err != ErrNilTerminal {
		t.Fatalf("expected ErrNilTerminal, got %v", err)
	}

	// Nil sink
	if _, err := NewPump(context.Background(), term, nil, DefaultConfig()); err != ErrNilSink {
		t.Fatalf("expected ErrNilSink, got %v", err)
	}

	// Valid creation
	pump, err := NewPump(context.Background(), term, sink, DefaultConfig())
	if err != nil {
		t.Fatalf("NewPump failed: %v", err)
	}
	if pump.IsClosed() {
		t.Fatalf("pump should not be closed initially")
	}
}

func TestPump_InputAndOutputPumping(t *testing.T) {
	term := newMockTerminal(nil)
	sink := newMockSink()

	cfg := Config{
		BufferSize:  64,
		MaxInFlight: 1024,
	}

	pump, err := NewPump(context.Background(), term, sink, cfg)
	if err != nil {
		t.Fatalf("NewPump failed: %v", err)
	}

	if err := pump.Start(); err != nil {
		t.Fatalf("pump.Start failed: %v", err)
	}

	// Subsequent Start() should fail with ErrPumpAlreadyStarted
	if err := pump.Start(); err != ErrPumpAlreadyStarted {
		t.Fatalf("expected ErrPumpAlreadyStarted, got %v", err)
	}

	// 1. Test Input Pumping: Write to pump -> read from terminal Stdin
	inputMsg := []byte("ls -la\n")
	readStdinCh := make(chan []byte, 1)
	go func() {
		buf := make([]byte, len(inputMsg))
		_, _ = io.ReadFull(term.stdinReader, buf)
		readStdinCh <- buf
	}()

	if err := pump.WriteInput(inputMsg); err != nil {
		t.Fatalf("WriteInput failed: %v", err)
	}

	select {
	case received := <-readStdinCh:
		if !bytes.Equal(received, inputMsg) {
			t.Fatalf("input mismatch: got %q, want %q", string(received), string(inputMsg))
		}
	case <-time.After(time.Second):
		t.Fatalf("timed out reading terminal stdin")
	}

	// 2. Test Output Pumping: Write to terminal Stdout -> verify sink receives it
	outputMsg := []byte("total 48\ndrwxr-xr-x 5 user group 4096\n")
	go func() {
		_, _ = term.stdoutWriter.Write(outputMsg)
		// Close stdout writer to simulate EOF
		_ = term.stdoutWriter.Close()
	}()

	select {
	case <-sink.closedCh:
		// Output loop finished and notified closed
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for pump to finish output loop")
	}

	if !bytes.Equal(sink.GetOutput(), outputMsg) {
		t.Fatalf("output mismatch: got %q, want %q", string(sink.GetOutput()), string(outputMsg))
	}

	if sink.closeReason != "exit" {
		t.Errorf("expected closeReason 'exit', got %q", sink.closeReason)
	}
	if sink.exitStatus != 0 {
		t.Errorf("expected exitStatus 0, got %d", sink.exitStatus)
	}
}

func TestPump_Resize(t *testing.T) {
	term := newMockTerminal(nil)
	sink := newMockSink()
	pump, _ := NewPump(context.Background(), term, sink, DefaultConfig())

	// Invalid dimensions
	if err := pump.Resize(0, 24); err != ErrInvalidDimensions {
		t.Fatalf("expected ErrInvalidDimensions for cols=0, got %v", err)
	}
	if err := pump.Resize(80, 0); err != ErrInvalidDimensions {
		t.Fatalf("expected ErrInvalidDimensions for rows=0, got %v", err)
	}

	// Valid resize
	if err := pump.Resize(120, 40); err != nil {
		t.Fatalf("Resize failed: %v", err)
	}

	if term.lastCols.Load() != 120 || term.lastRows.Load() != 40 {
		t.Fatalf("expected 120x40, got %dx%d", term.lastCols.Load(), term.lastRows.Load())
	}
}

func TestPump_FlowControl_Backpressure(t *testing.T) {
	term := newMockTerminal(nil)
	sink := newMockSink()

	// Window is 100 bytes, buffer size is 50 bytes
	cfg := Config{
		BufferSize:  50,
		MaxInFlight: 100,
	}

	pump, err := NewPump(context.Background(), term, sink, cfg)
	if err != nil {
		t.Fatalf("NewPump failed: %v", err)
	}

	if err := pump.Start(); err != nil {
		t.Fatalf("Start failed: %v", err)
	}

	// Write 150 bytes in total (first 100 bytes should reach sink, then pump pauses)
	chunk1 := bytes.Repeat([]byte("A"), 50)
	chunk2 := bytes.Repeat([]byte("B"), 50)
	chunk3 := bytes.Repeat([]byte("C"), 50)

	go func() {
		_, _ = term.stdoutWriter.Write(chunk1)
		_, _ = term.stdoutWriter.Write(chunk2)
		// Small delay to verify pause at 100 bytes
		time.Sleep(50 * time.Millisecond)
		_, _ = term.stdoutWriter.Write(chunk3)
		_ = term.stdoutWriter.Close()
	}()

	// Wait until 100 bytes are in sink
	deadline := time.Now().Add(time.Second)
	for len(sink.GetOutput()) < 100 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}

	if len(sink.GetOutput()) != 100 {
		t.Fatalf("expected 100 bytes before ack, got %d", len(sink.GetOutput()))
	}

	// Verify backpressure: in-flight is 100 >= 100, so pump is paused
	time.Sleep(50 * time.Millisecond)
	if len(sink.GetOutput()) > 100 {
		t.Fatalf("pump should be paused by backpressure, but received %d bytes", len(sink.GetOutput()))
	}

	// Now Acknowledge 50 bytes!
	pump.Acknowledge(50)

	// Pump unblocks and reads the next 50 bytes (chunk3)
	deadline = time.Now().Add(time.Second)
	for len(sink.GetOutput()) < 150 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}

	if len(sink.GetOutput()) != 150 {
		t.Fatalf("expected total 150 bytes after ack, got %d", len(sink.GetOutput()))
	}

	// Now acknowledge remaining bytes so reader can unblock and read EOF
	pump.Acknowledge(100)

	select {
	case <-sink.closedCh:
		// Done
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for EOF closure after final Ack")
	}
}

func TestPump_ClientClose(t *testing.T) {
	term := newMockTerminal(nil)
	sink := newMockSink()
	pump, _ := NewPump(context.Background(), term, sink, DefaultConfig())

	if err := pump.Start(); err != nil {
		t.Fatalf("Start failed: %v", err)
	}

	// Close from client side
	if err := pump.Close("user_closed"); err != nil {
		t.Fatalf("Close failed: %v", err)
	}

	select {
	case <-pump.Done():
		// Success
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for pump.Done()")
	}

	if !pump.IsClosed() {
		t.Fatalf("expected IsClosed() to be true")
	}
	if pump.CloseReason() != "user_closed" {
		t.Fatalf("expected closeReason 'user_closed', got %q", pump.CloseReason())
	}
	if sink.closeReason != "user_closed" {
		t.Fatalf("expected sink closeReason 'user_closed', got %q", sink.closeReason)
	}

	// Subsequent WriteInput should fail with ErrPumpClosed
	if err := pump.WriteInput([]byte("abc")); err != ErrPumpClosed {
		t.Fatalf("expected ErrPumpClosed, got %v", err)
	}

	// Subsequent Resize should fail with ErrPumpClosed
	if err := pump.Resize(80, 24); err != ErrPumpClosed {
		t.Fatalf("expected ErrPumpClosed, got %v", err)
	}
}

func TestPump_ExitCodeExtraction(t *testing.T) {
	// Simulate an exit error with code 127
	simulatedErr := errors.New("exit status 127")
	term := newMockTerminal(simulatedErr)
	sink := newMockSink()

	pump, _ := NewPump(context.Background(), term, sink, DefaultConfig())
	_ = pump.Start()

	// Close stdout to trigger exit
	_ = term.stdoutWriter.Close()

	select {
	case <-pump.Done():
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for pump to terminate")
	}

	exitCode, exited := pump.ExitStatus()
	if !exited {
		t.Fatalf("expected hasExited to be true")
	}
	// Since simulatedErr is not *ssh.ExitError, fallback code is -1
	if exitCode != -1 {
		t.Fatalf("expected exitCode -1 for non-ssh error, got %d", exitCode)
	}
}

// ----------------------------------------------------------------------------
// End-to-end integration test with real SSH TerminalSession & mock SSH server
// ----------------------------------------------------------------------------

func TestPump_WithRealSSHClientAndTerminal(t *testing.T) {
	// 1. Create mock in-process SSH server
	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate host key: %v", err)
	}
	hostSigner, err := ssh.NewSignerFromKey(hostPriv)
	if err != nil {
		t.Fatalf("failed to create host signer: %v", err)
	}

	serverConfig := &ssh.ServerConfig{
		PasswordCallback: func(conn ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
			if conn.User() == "alice" && string(pass) == "secret" {
				return nil, nil
			}
			return nil, fmt.Errorf("auth rejected")
		},
	}
	serverConfig.AddHostKey(hostSigner)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen: %v", err)
	}
	defer ln.Close()

	var serverCols atomic.Uint32
	var serverRows atomic.Uint32

	go func() {
		tcpConn, err := ln.Accept()
		if err != nil {
			return
		}
		defer tcpConn.Close()

		sConn, chans, reqs, err := ssh.NewServerConn(tcpConn, serverConfig)
		if err != nil {
			return
		}
		defer sConn.Close()

		go ssh.DiscardRequests(reqs)

		for newChan := range chans {
			if newChan.ChannelType() != "session" {
				_ = newChan.Reject(ssh.UnknownChannelType, "unsupported")
				continue
			}

			channel, requests, err := newChan.Accept()
			if err != nil {
				continue
			}

			go func(ch ssh.Channel, inReqs <-chan *ssh.Request) {
				defer ch.Close()
				for req := range inReqs {
					switch req.Type {
					case "pty-req":
						_ = req.Reply(true, nil)
					case "shell":
						_ = req.Reply(true, nil)
						// Echo stdin back to stdout
						go func() {
							_, _ = io.Copy(ch, ch)
						}()
					case "window-change":
						if len(req.Payload) >= 8 {
							serverCols.Store(binary.BigEndian.Uint32(req.Payload[0:4]))
							serverRows.Store(binary.BigEndian.Uint32(req.Payload[4:8]))
						}
						_ = req.Reply(true, nil)
					default:
						_ = req.Reply(false, nil)
					}
				}
			}(channel, requests)
		}
	}()

	// 2. Connect via sshclient.Dial
	port := ln.Addr().(*net.TCPAddr).Port
	cfg := sshclient.Config{
		Host:           "127.0.0.1",
		Port:           port,
		Username:       "alice",
		AuthType:       sshclient.AuthTypePassword,
		Password:       "secret",
		ConnectTimeout: 2 * time.Second,
	}

	client, err := sshclient.Dial(context.Background(), &cfg, nil)
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer client.Close()

	// 3. Open terminal session
	termSession, err := client.OpenTerminal(sshclient.DefaultTerminalConfig())
	if err != nil {
		t.Fatalf("OpenTerminal failed: %v", err)
	}
	defer termSession.Close()

	// 4. Attach pty.Pump
	sink := newMockSink()
	pump, err := NewPump(context.Background(), termSession, sink, DefaultConfig())
	if err != nil {
		t.Fatalf("NewPump failed: %v", err)
	}

	if err := pump.Start(); err != nil {
		t.Fatalf("pump.Start failed: %v", err)
	}

	// 5. Send keystrokes through pump and receive echoed output
	msg := []byte("echo hello-pump\n")
	if err := pump.WriteInput(msg); err != nil {
		t.Fatalf("WriteInput failed: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for len(sink.GetOutput()) < len(msg) && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}

	if !bytes.Contains(sink.GetOutput(), []byte("hello-pump")) {
		t.Fatalf("expected echoed output containing 'hello-pump', got %q", string(sink.GetOutput()))
	}

	// 6. Test window resize through pump
	if err := pump.Resize(140, 50); err != nil {
		t.Fatalf("Resize failed: %v", err)
	}
	time.Sleep(50 * time.Millisecond)

	if serverCols.Load() != 140 || serverRows.Load() != 50 {
		t.Errorf("expected server cols=140, rows=50; got cols=%d, rows=%d", serverCols.Load(), serverRows.Load())
	}

	// 7. Close pump and verify graceful termination
	if err := pump.Close("session_ended"); err != nil {
		t.Fatalf("pump.Close failed: %v", err)
	}

	select {
	case <-pump.Done():
	case <-time.After(time.Second):
		t.Fatalf("timed out waiting for pump.Done()")
	}

	if !pump.IsClosed() {
		t.Errorf("expected pump to be closed")
	}
}
