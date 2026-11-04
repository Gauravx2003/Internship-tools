package stdio

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gaurav/web-session/session-engine/internal/protocol"
)

// Common errors
var (
	ErrServerClosed     = errors.New("stdio server is closed")
	ErrNilHandler       = errors.New("handler function cannot be nil")
	ErrEmptyMessageType = errors.New("message type cannot be empty")
)

// DefaultMaxMessageSize is the largest single inbound message (one line of JSON)
// the server accepts. Bigger lines are dropped with a PROTOCOL_ERROR.
const DefaultMaxMessageSize = 1 << 20 // 1 MiB

// CodedError lets a handler choose the error code that is sent to Vert.x.
// Any other error returned by a handler is reported as INTERNAL.
//
//	return &stdio.CodedError{Code: protocol.ErrCodeAuthFailed, Err: err}
type CodedError struct {
	Code string
	Err  error
}

func (e *CodedError) Error() string {
	if e.Err == nil {
		return e.Code
	}
	return e.Err.Error()
}

func (e *CodedError) Unwrap() error { return e.Err }

// HandlerFunc is the signature for handling an inbound envelope from Vert.x.
type HandlerFunc func(ctx context.Context, env *protocol.Envelope) error

// OutputSink defines the outbound streaming interface for terminal output and session closure.
type OutputSink interface {
	SendOutput(data []byte) error
	SendClosed(reason string, exitStatus int) error
}

type handlerEntry struct {
	fn    HandlerFunc
	async bool
}

// Server coordinates bidirectional communication between Vert.x and this session engine
// process over standard I/O pipes (stdin for inbound, stdout for outbound).
//
//   - Inbound messages arrive on stdin as one JSON envelope per line.
//   - Outbound messages are written to stdout, one JSON envelope per line.
//   - Logs go to stderr only. Nothing else may ever be written to stdout.
//
// Handler model (important):
//
//	On(...)       handler runs inside the reader loop. It MUST return quickly and
//	              never wait for another inbound message. Use for input, resize,
//	              ack, hostkey_decision, close.
//	OnAsync(...)  handler runs in its own goroutine, so it may block (SSH dial,
//	              waiting for a host-key decision). Use for open and deploy_key.
//
// If a long-running handler ran inside the reader loop, the loop could not read
// the message that would unblock it (for example hostkey_decision) or the close
// message, and the engine would deadlock.
type Server struct {
	reader         io.Reader
	writer         io.Writer
	writeMu        sync.Mutex
	seq            int64 // guarded by writeMu, so seq order == write order
	handlers       map[string]handlerEntry
	handlersMu     sync.RWMutex
	defaultHandler HandlerFunc
	sessionID      string
	sessionIDMu    sync.RWMutex
	maxMsgSize     int
	wg             sync.WaitGroup // async handlers in flight
	ctx            context.Context
	cancel         context.CancelFunc
	done           chan struct{}
	closeOnce      sync.Once
	closed         atomic.Bool
	logger         *log.Logger
}

// Ensure *Server implements OutputSink at compile time.
var _ OutputSink = (*Server)(nil)

// Option configures a Server instance.
type Option func(*Server)

// WithReader overrides the inbound stream (defaults to os.Stdin).
func WithReader(r io.Reader) Option {
	return func(s *Server) {
		if r != nil {
			s.reader = r
		}
	}
}

// WithWriter overrides the outbound stream (defaults to os.Stdout).
func WithWriter(w io.Writer) Option {
	return func(s *Server) {
		if w != nil {
			s.writer = w
		}
	}
}

// WithContext supplies a root context for the server lifecycle.
func WithContext(ctx context.Context) Option {
	return func(s *Server) {
		if ctx != nil {
			s.cancel() // release the default context created in NewServer
			s.ctx, s.cancel = context.WithCancel(ctx)
		}
	}
}

// WithSessionID pre-assigns the session identifier.
func WithSessionID(sessionID string) Option {
	return func(s *Server) {
		s.sessionID = strings.TrimSpace(sessionID)
	}
}

// WithLogger overrides the logger (defaults to stderr).
func WithLogger(l *log.Logger) Option {
	return func(s *Server) {
		if l != nil {
			s.logger = l
		}
	}
}

// WithMaxMessageSize sets the largest accepted inbound line in bytes.
func WithMaxMessageSize(n int) Option {
	return func(s *Server) {
		if n > 0 {
			s.maxMsgSize = n
		}
	}
}

// NewServer initializes a new stdio Server configured with functional options.
func NewServer(opts ...Option) *Server {
	ctx, cancel := context.WithCancel(context.Background())

	s := &Server{
		reader:     os.Stdin,
		writer:     os.Stdout,
		handlers:   make(map[string]handlerEntry),
		maxMsgSize: DefaultMaxMessageSize,
		ctx:        ctx,
		cancel:     cancel,
		done:       make(chan struct{}),
		logger:     log.New(os.Stderr, "[stdio-server] ", log.LstdFlags|log.Lmsgprefix),
	}

	for _, opt := range opts {
		opt(s)
	}

	return s
}

func (s *Server) register(msgType string, handler HandlerFunc, async bool) error {
	msgType = strings.TrimSpace(msgType)
	if msgType == "" {
		return ErrEmptyMessageType
	}
	if handler == nil {
		return ErrNilHandler
	}

	s.handlersMu.Lock()
	defer s.handlersMu.Unlock()
	s.handlers[msgType] = handlerEntry{fn: handler, async: async}
	return nil
}

// On registers a handler that runs inside the reader loop. It must be quick and
// must never wait for another inbound message.
func (s *Server) On(msgType string, handler HandlerFunc) error {
	return s.register(msgType, handler, false)
}

// OnAsync registers a handler that runs in its own goroutine, so it may block
// (SSH dial, waiting for a host-key decision, and so on). Use it for "open" and
// "deploy_key". The handler should watch ctx.Done() and return when it fires.
func (s *Server) OnAsync(msgType string, handler HandlerFunc) error {
	return s.register(msgType, handler, true)
}

// SetDefaultHandler registers a fallback handler for unmapped message types.
// It runs inside the reader loop, like On.
func (s *Server) SetDefaultHandler(handler HandlerFunc) {
	s.handlersMu.Lock()
	defer s.handlersMu.Unlock()
	s.defaultHandler = handler
}

// SessionID returns the active session identifier.
func (s *Server) SessionID() string {
	s.sessionIDMu.RLock()
	defer s.sessionIDMu.RUnlock()
	return s.sessionID
}

// SetSessionID assigns or updates the session identifier.
func (s *Server) SetSessionID(id string) {
	s.sessionIDMu.Lock()
	defer s.sessionIDMu.Unlock()
	s.sessionID = strings.TrimSpace(id)
}

// Context returns the cancellation context bound to the server lifecycle.
func (s *Server) Context() context.Context {
	return s.ctx
}

// IsClosed reports whether the server has been closed.
func (s *Server) IsClosed() bool {
	return s.closed.Load()
}

// Done returns a channel that is closed when the server terminates.
func (s *Server) Done() <-chan struct{} {
	return s.done
}

// Close closes the server and cancels its context. Run returns as soon as this
// happens, even if stdin is still open and nobody is sending anything.
//
// Order matters: after Close, Send returns ErrServerClosed. So send the final
// "closed" message first, then call Close.
func (s *Server) Close() error {
	s.closeOnce.Do(func() {
		s.closed.Store(true)
		s.cancel()
		close(s.done)
	})
	return nil
}

// Wait blocks until all async handlers have returned, or the timeout passes.
// It returns true if they all finished. Call it after Run returns, so that
// cleanup code (closing SSH, flushing the log) gets time to finish before the
// process exits.
func (s *Server) Wait(timeout time.Duration) bool {
	finished := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(finished)
	}()
	select {
	case <-finished:
		return true
	case <-time.After(timeout):
		return false
	}
}

type readResult struct {
	line    []byte
	tooLong bool
	err     error
}

// readLine reads one line of at most max bytes. A longer line is read to its end
// and thrown away (tooLong is true), so memory stays bounded and the next line
// is read normally.
func readLine(br *bufio.Reader, max int) (line []byte, tooLong bool, err error) {
	var buf []byte
	for {
		chunk, isPrefix, e := br.ReadLine()
		if e != nil {
			return nil, false, e
		}
		if !tooLong {
			if len(buf)+len(chunk) > max {
				tooLong = true
				buf = nil
			} else {
				buf = append(buf, chunk...)
			}
		}
		if !isPrefix {
			return buf, tooLong, nil
		}
	}
}

// readLoop pumps lines from the reader into out. It runs in its own goroutine so
// that Run can stop waiting when the context is cancelled. If it is blocked in a
// read at that moment it stays blocked until the process exits, which is fine for
// a process that is about to exit anyway.
func (s *Server) readLoop(out chan<- readResult) {
	defer close(out)
	br := bufio.NewReaderSize(s.reader, 64*1024)
	for {
		line, tooLong, err := readLine(br, s.maxMsgSize)
		res := readResult{line: line, tooLong: tooLong, err: err}
		select {
		case out <- res:
		case <-s.ctx.Done():
			return
		}
		if err != nil {
			return
		}
	}
}

// Run executes the inbound loop: it reads newline-delimited JSON envelopes and
// dispatches them to registered handlers. It returns when stdin reaches EOF
// (Vert.x closed the pipe or died), the context is cancelled (Close was called),
// or a read error occurs. On return the server is closed and its context is
// cancelled, so every handler watching ctx can clean up.
func (s *Server) Run() error {
	defer s.Close()

	lines := make(chan readResult)
	go s.readLoop(lines)

	for {
		select {
		case <-s.ctx.Done():
			return nil
		case res, ok := <-lines:
			if !ok {
				return nil
			}

			if res.err != nil {
				if errors.Is(res.err, io.EOF) || errors.Is(res.err, os.ErrClosed) || errors.Is(res.err, io.ErrClosedPipe) {
					s.logger.Println("inbound pipe closed (EOF)")
					return nil
				}
				s.logger.Printf("error reading from inbound pipe: %v", res.err)
				return res.err
			}
			s.dispatch(res)
		}
	}
}

func shorten(str string, n int) string {
	if len(str) > n {
		return str[:n] + "..."
	}
	return str
}

// dispatch parses one inbound line and routes it to the right handler.
func (s *Server) dispatch(res readResult) {
	if res.tooLong {
		s.logger.Printf("inbound message larger than %d bytes was dropped", s.maxMsgSize)
		_ = s.SendError(protocol.ErrCodeProtocolError, fmt.Sprintf("message exceeds %d bytes", s.maxMsgSize))
		return
	}

	data := bytes.TrimSpace(res.line)
	if len(data) == 0 {
		return
	}

	var env protocol.Envelope
	if err := json.Unmarshal(data, &env); err != nil {
		// The raw line is deliberately NOT logged: an "open" message carries
		// the password or private key.
		s.logger.Printf("invalid envelope JSON (%d bytes): %v", len(data), err)
		_ = s.SendError(protocol.ErrCodeProtocolError, fmt.Sprintf("invalid envelope JSON: %v", err))
		return
	}
	if env.Type == "" {
		_ = s.SendError(protocol.ErrCodeProtocolError, "message type is missing")
		return
	}

	// Capture session ID if not set yet
	if s.SessionID() == "" && env.SessionID != "" {
		s.SetSessionID(env.SessionID)
	}

	s.handlersMu.RLock()
	entry, exists := s.handlers[env.Type]
	defaultHandler := s.defaultHandler
	s.handlersMu.RUnlock()

	switch {
	case exists && entry.async:
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.invoke(entry.fn, &env)
		}()
	case exists:
		s.invoke(entry.fn, &env)
	case defaultHandler != nil:
		s.invoke(defaultHandler, &env)
	default:
		s.logger.Printf("no handler registered for message type %q", shorten(env.Type, 64))
		_ = s.SendError(protocol.ErrCodeProtocolError, fmt.Sprintf("unknown message type %q", shorten(env.Type, 64)))
	}
}

// invoke runs a handler, turns a returned error into an "error" message for
// Vert.x, and turns a panic into an INTERNAL error instead of killing the process.
func (s *Server) invoke(fn HandlerFunc, env *protocol.Envelope) {
	defer func() {
		if r := recover(); r != nil {
			s.logger.Printf("panic in handler %q: %v\n%s", env.Type, r, debug.Stack())
			_ = s.SendError(protocol.ErrCodeInternal, "internal error")
		}
	}()

	if err := fn(s.ctx, env); err != nil {
		s.logger.Printf("handler for %q failed: %v", env.Type, err)
		code := protocol.ErrCodeInternal
		var ce *CodedError
		if errors.As(err, &ce) && ce.Code != "" {
			code = ce.Code
		}
		_ = s.SendError(code, err.Error())
	}
}

// Send thread-safely writes an envelope to the outbound stream as a single line of JSON terminated by '\n'.
// The sequence number is assigned while the write lock is held, so seq order is always write order.
func (s *Server) Send(env *protocol.Envelope) error {
	if s.IsClosed() {
		return ErrServerClosed
	}
	if env == nil {
		return errors.New("cannot send nil envelope")
	}

	if env.V == 0 {
		env.V = protocol.ProtocolVersion
	}
	if env.Ts == "" {
		env.Ts = time.Now().UTC().Format(time.RFC3339)
	}
	if env.SessionID == "" {
		env.SessionID = s.SessionID()
	}

	s.writeMu.Lock()
	defer s.writeMu.Unlock()

	s.seq++
	env.Seq = s.seq

	data, err := json.Marshal(env)
	if err != nil {
		s.seq-- // nothing was written, keep the sequence gap-free
		return fmt.Errorf("failed to marshal envelope: %w", err)
	}

	if _, err := s.writer.Write(append(data, '\n')); err != nil {
		return fmt.Errorf("failed to write envelope to stdout: %w", err)
	}

	if flusher, ok := s.writer.(interface{ Flush() error }); ok {
		_ = flusher.Flush()
	}

	return nil
}

// SendPayload sends an envelope with the given message type and payload object.
func (s *Server) SendPayload(msgType string, payload any) error {
	env, err := protocol.NewEnvelopeWithPayload(msgType, s.SessionID(), 0, payload)
	if err != nil {
		return err
	}
	return s.Send(env)
}

// SendState emits a "state" envelope (e.g. connecting, authenticating, connected).
func (s *Server) SendState(state, message string) error {
	return s.SendPayload(protocol.TypeState, protocol.StatePayload{
		State:   state,
		Message: message,
	})
}

// SendHostkeyChallenge prompts Vert.x with the remote host fingerprint for trust verification.
func (s *Server) SendHostkeyChallenge(keyType, fingerprint string) error {
	return s.SendPayload(protocol.TypeHostkeyChallenge, protocol.HostkeyChallengePayload{
		KeyType:     keyType,
		Fingerprint: fingerprint,
	})
}

// SendOutput emits a terminal stdout chunk to Vert.x.
// Satisfies OutputSink.SendOutput.
func (s *Server) SendOutput(data []byte) error {
	return s.SendPayload(protocol.TypeOutput, protocol.NewOutputPayload(data))
}

// SendError emits an error code and human-readable message.
func (s *Server) SendError(code, message string) error {
	return s.SendPayload(protocol.TypeError, protocol.ErrorPayload{
		Code:    code,
		Message: message,
	})
}

// SendClosed notifies Vert.x that the session has terminated.
// Satisfies OutputSink.SendClosed. Call it before Close.
func (s *Server) SendClosed(reason string, exitStatus int) error {
	return s.SendPayload(protocol.TypeClosed, protocol.ClosedPayload{
		Reason:     reason,
		ExitStatus: exitStatus,
	})
}

// SendResult emits the outcome of a one-shot operation (e.g. deploy_key).
func (s *Server) SendResult(success bool, message string) error {
	return s.SendPayload(protocol.TypeResult, protocol.ResultPayload{
		Success: success,
		Message: message,
	})
}
