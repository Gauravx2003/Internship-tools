package pty

import (
	"context"
	"errors"
	"io"
	"sync"

	"golang.org/x/crypto/ssh"
)

// Terminal defines the minimal interface required for the PTY pump to interact
// with an active remote terminal.
// This interface is satisfied directly by *sshclient.TerminalSession.
type Terminal interface {
	Stdin() io.WriteCloser
	Stdout() io.Reader
	Resize(cols, rows uint32) error
}

// Waiter is an optional interface implemented by terminals that can report
// command / interactive shell exit status (e.g. *sshclient.TerminalSession).
type Waiter interface {
	Wait() error
}

// OutputSink represents the outbound carrier sink for receiving terminal output
// and session termination events (e.g., Vert.x WebSocket channel or event bus).
type OutputSink interface {
	// SendOutput forwards a chunk of terminal output bytes.
	SendOutput(data []byte) error

	// SendClosed notifies the recipient that the terminal session has closed,
	// supplying the termination reason and process exit code.
	SendClosed(reason string, exitStatus int) error
}

// OutputSinkFuncs is a functional adapter implementing OutputSink.
type OutputSinkFuncs struct {
	OnOutput func(data []byte) error
	OnClosed func(reason string, exitStatus int) error
}

func (s OutputSinkFuncs) SendOutput(data []byte) error {
	if s.OnOutput != nil {
		return s.OnOutput(data)
	}
	return nil
}

func (s OutputSinkFuncs) SendClosed(reason string, exitStatus int) error {
	if s.OnClosed != nil {
		return s.OnClosed(reason, exitStatus)
	}
	return nil
}

// Config specifies operating parameters for the PTY pump.
type Config struct {
	// BufferSize is the byte capacity allocated for each read from terminal stdout.
	// Default is 4096 (4KB).
	BufferSize int

	// MaxInFlight is the maximum unacknowledged bytes allowed before backpressure pauses reads.
	// Default is 65536 (64KB). Set <= 0 to disable backpressure.
	MaxInFlight int64
}

// DefaultConfig returns the standard production configuration for PTY pumping.
func DefaultConfig() Config {
	return Config{
		BufferSize:  4096,
		MaxInFlight: 64 * 1024,
	}
}

// Pump acts as the bidirectional carrier between Vert.x and the remote host's PTY.
// It manages:
//  1. Streaming user input (keystrokes) into remote Stdin.
//  2. Reading remote terminal Stdout and pushing chunks to OutputSink.
//  3. Backpressure enforcement via FlowController (pausing reads when unacknowledged output exceeds threshold).
//  4. Window resize forwarding to remote PTY.
//  5. Process exit monitoring and exitStatus extraction.
type Pump struct {
	ctx         context.Context
	cancel      context.CancelFunc
	term        Terminal
	sink        OutputSink
	cfg         Config
	flow        *FlowController
	startOnce   sync.Once
	closeOnce   sync.Once
	doneCh      chan struct{}
	mu          sync.RWMutex
	stdinMu     sync.Mutex
	started     bool
	closed      bool
	closeReason string
	exitStatus  int
	hasExited   bool
}

// NewPump initializes a new Pump instance with the given terminal, output sink, and config.
func NewPump(ctx context.Context, term Terminal, sink OutputSink, cfg Config) (*Pump, error) {
	if term == nil {
		return nil, ErrNilTerminal
	}
	if sink == nil {
		return nil, ErrNilSink
	}

	if cfg.BufferSize <= 0 {
		cfg.BufferSize = 4096
	}
	if cfg.MaxInFlight == 0 {
		cfg.MaxInFlight = 64 * 1024
	}

	if ctx == nil {
		ctx = context.Background()
	}
	pumpCtx, cancel := context.WithCancel(ctx)

	return &Pump{
		ctx:        pumpCtx,
		cancel:     cancel,
		term:       term,
		sink:       sink,
		cfg:        cfg,
		flow:       NewFlowController(cfg.MaxInFlight),
		doneCh:     make(chan struct{}),
		exitStatus: 0,
	}, nil
}

// Start begins asynchronous pumping of terminal output in a background goroutine.
// Start is idempotent and returns ErrPumpAlreadyStarted on subsequent calls.
func (p *Pump) Start() error {
	p.mu.Lock()
	if p.started {
		p.mu.Unlock()
		return ErrPumpAlreadyStarted
	}
	if p.closed {
		p.mu.Unlock()
		return ErrPumpClosed
	}
	p.started = true
	p.mu.Unlock()

	go p.outputLoop()

	return nil
}

// WriteInput writes input bytes (user keystrokes, commands) directly into remote terminal stdin.
// It is thread-safe and safe for concurrent calls.
func (p *Pump) WriteInput(data []byte) error {
	if len(data) == 0 {
		return nil
	}

	p.mu.RLock()
	if p.closed {
		p.mu.RUnlock()
		return ErrPumpClosed
	}
	p.mu.RUnlock()

	p.stdinMu.Lock()
	defer p.stdinMu.Unlock()

	if p.IsClosed() {
		return ErrPumpClosed
	}

	stdin := p.term.Stdin()
	if stdin == nil {
		return ErrPumpClosed
	}

	_, err := stdin.Write(data)
	return err
}

// Resize dispatches a terminal window resize request to the remote PTY.
// Returns ErrInvalidDimensions if cols or rows is zero.
func (p *Pump) Resize(cols, rows uint32) error {
	if cols == 0 || rows == 0 {
		return ErrInvalidDimensions
	}

	if p.IsClosed() {
		return ErrPumpClosed
	}

	return p.term.Resize(cols, rows)
}

// Acknowledge signals that the consumer has rendered or processed bytesConsumed bytes.
// This reduces unacknowledged in-flight count, unblocking the output reader if it was paused.
func (p *Pump) Acknowledge(bytesConsumed int64) {
	p.flow.Ack(bytesConsumed)
}

// outputLoop runs in a background goroutine, reading chunks from remote Stdout and
// delivering them to OutputSink subject to flow-control backpressure.
func (p *Pump) outputLoop() {
	defer func() {
		// Wait for process exit status if available
		exitStatus := 0
		if waiter, ok := p.term.(Waiter); ok {
			if err := waiter.Wait(); err != nil {
				var exitErr *ssh.ExitError
				if errors.As(err, &exitErr) {
					exitStatus = exitErr.ExitStatus()
				} else {
					exitStatus = -1
				}
			}
		}

		p.terminate("exit", exitStatus)
	}()

	buf := make([]byte, p.cfg.BufferSize)
	stdout := p.term.Stdout()

	for {
		// 1. Check backpressure before reading next chunk
		if err := p.flow.WaitIfFull(p.ctx); err != nil {
			return
		}

		// 2. Read from remote terminal
		n, err := stdout.Read(buf)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])

			// 3. Record emitted bytes in flow controller
			p.flow.AddInFlight(int64(n))

			// 4. Forward to outbound sink
			if sendErr := p.sink.SendOutput(chunk); sendErr != nil {
				return
			}

		}

		if err != nil {
			// EOF or pipe closure signals shell termination
			return
		}
	}
}

// Close gracefully stops the pump, cancels background operations,
// closes the remote terminal, and notifies the sink.
func (p *Pump) Close(reason string) error {
	if reason == "" {
		reason = "closed"
	}
	p.terminate(reason, 0)
	return nil
}

// terminate coordinates idempotent teardown of the pump.
func (p *Pump) terminate(reason string, exitStatus int) {
	p.closeOnce.Do(func() {
		p.mu.Lock()
		p.closed = true
		p.closeReason = reason
		p.exitStatus = exitStatus
		p.hasExited = true
		p.mu.Unlock()

		p.cancel()
		p.flow.Close()

		// Close remote terminal stdin
		if stdin := p.term.Stdin(); stdin != nil {
			_ = stdin.Close()
		}

		// Close underlying terminal if it implements io.Closer
		if closer, ok := p.term.(io.Closer); ok {
			_ = closer.Close()
		}

		// Send closed notification to sink
		_ = p.sink.SendClosed(reason, exitStatus)

		close(p.doneCh)
	})
}

// Done returns a channel that is closed when the pump has terminated.
func (p *Pump) Done() <-chan struct{} {
	return p.doneCh
}

// Wait blocks until the pump completes and returns nil.
func (p *Pump) Wait() error {
	<-p.doneCh
	return nil
}

// IsClosed reports whether the pump has been closed or terminated.
func (p *Pump) IsClosed() bool {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.closed
}

// CloseReason returns the reason recorded when the pump was terminated.
func (p *Pump) CloseReason() string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.closeReason
}

// ExitStatus returns the process exit code and true if the process has exited.
func (p *Pump) ExitStatus() (int, bool) {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.exitStatus, p.hasExited
}

// FlowController returns the pump's active flow controller (useful for diagnostics & tests).
func (p *Pump) FlowController() *FlowController {
	return p.flow
}
