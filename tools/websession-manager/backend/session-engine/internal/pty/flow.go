package pty

import (
	"context"
	"sync"
)

// FlowController manages in-flight byte tracking and backpressure enforcement
// between the remote PTY output and Vert.x / browser consumers.
//
// When terminal output is sent to Vert.x, inFlight increases. If inFlight
// reaches maxInFlight, WaitIfFull pauses reading from the remote terminal
// until Vert.x sends an "ack" with consumed bytes.
type FlowController struct {
	mu          sync.Mutex
	maxInFlight int64
	inFlight    int64
	waitCh      chan struct{}
	closeCh     chan struct{}
	closed      bool
}

// NewFlowController initializes a FlowController with the specified maximum in-flight window.
// If maxInFlight <= 0, flow control backpressure is disabled.
func NewFlowController(maxInFlight int64) *FlowController {
	return &FlowController{
		maxInFlight: maxInFlight,
		waitCh:      make(chan struct{}),
		closeCh:     make(chan struct{}),
	}
}

// AddInFlight records newly emitted output bytes.
func (fc *FlowController) AddInFlight(n int64) {
	if fc.maxInFlight <= 0 || n <= 0 {
		return
	}
	fc.mu.Lock()
	defer fc.mu.Unlock()
	fc.inFlight += n
}

// WaitIfFull blocks if unacknowledged in-flight bytes exceed or equal maxInFlight.
// It unblocks when an Ack decreases in-flight bytes below the threshold,
// the pump is closed, or the given context is canceled.
func (fc *FlowController) WaitIfFull(ctx context.Context) error {
	if fc.maxInFlight <= 0 {
		return nil
	}

	for {
		fc.mu.Lock()
		if fc.closed {
			fc.mu.Unlock()
			return ErrPumpClosed
		}
		if fc.inFlight < fc.maxInFlight {
			fc.mu.Unlock()
			return nil
		}
		ch := fc.waitCh
		fc.mu.Unlock()

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-fc.closeCh:
			return ErrPumpClosed
		case <-ch:
			// waitCh closed after Ack; loop to verify inFlight < maxInFlight
		}
	}
}

// Ack records bytes acknowledged by the consumer, reducing in-flight bytes
// and unblocking the reader if the window opened up.
func (fc *FlowController) Ack(n int64) {
	if fc.maxInFlight <= 0 || n <= 0 {
		return
	}
	fc.mu.Lock()
	defer fc.mu.Unlock()

	if fc.closed {
		return
	}

	fc.inFlight -= n
	if fc.inFlight < 0 {
		fc.inFlight = 0
	}

	if fc.inFlight < fc.maxInFlight {
		select {
		case <-fc.waitCh:
			// Already closed
		default:
			close(fc.waitCh)
		}
		fc.waitCh = make(chan struct{})
	}
}

// InFlight returns the current number of unacknowledged in-flight bytes.
func (fc *FlowController) InFlight() int64 {
	fc.mu.Lock()
	defer fc.mu.Unlock()
	return fc.inFlight
}

// MaxInFlight returns the configured ceiling for in-flight bytes.
func (fc *FlowController) MaxInFlight() int64 {
	return fc.maxInFlight
}

// Close unblocks any goroutines currently waiting in WaitIfFull.
func (fc *FlowController) Close() {
	fc.mu.Lock()
	defer fc.mu.Unlock()

	if fc.closed {
		return
	}
	fc.closed = true

	close(fc.closeCh)
	select {
	case <-fc.waitCh:
	default:
		close(fc.waitCh)
	}
}
