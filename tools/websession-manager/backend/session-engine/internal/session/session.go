package session

import (
	"context"
	"sync"
	"time"
)

// Session represents an individual active session tracked by the Session Manager.
// It maintains its own cancellation context, lifecycle timestamps, and cleanup callbacks.
type Session struct {
	id          string
	createdAt   time.Time
	ctx         context.Context
	cancel      context.CancelFunc
	closeOnce   sync.Once
	closedAt    time.Time
	closeReason string
	cleanups    []func()
	mu          sync.RWMutex
}

// newSession initializes a new session instance with a child context.
func newSession(id string, parentCtx context.Context) *Session {
	if parentCtx == nil {
		parentCtx = context.Background()
	}
	ctx, cancel := context.WithCancel(parentCtx)
	return &Session{
		id:        id,
		createdAt: time.Now().UTC(),
		ctx:       ctx,
		cancel:    cancel,
	}
}

// ID returns the unique session identifier.
func (s *Session) ID() string {
	return s.id
}

// CreatedAt returns the timestamp when the session was created.
func (s *Session) CreatedAt() time.Time {
	return s.createdAt
}

// Context returns the session's cancellation context.
func (s *Session) Context() context.Context {
	return s.ctx
}

// Done returns a channel that is closed when the session is closed.
func (s *Session) Done() <-chan struct{} {
	return s.ctx.Done()
}

// AddCleanup registers a cleanup function to be executed when the session is closed.
// This allows subsequent components (e.g. SSH client, PTY pump) to attach resource cleanups.
func (s *Session) AddCleanup(fn func()) {
	if fn == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cleanups = append(s.cleanups, fn)
}

// Close closes the session, runs all registered cleanup hooks, and cancels the session context.
// Close is idempotent and thread-safe.
func (s *Session) Close(reason string) {
	s.closeOnce.Do(func() {
		s.mu.Lock()
		s.closedAt = time.Now().UTC()
		s.closeReason = reason
		cleanups := make([]func(), len(s.cleanups))
		copy(cleanups, s.cleanups)
		s.mu.Unlock()

		for _, cleanup := range cleanups {
			if cleanup != nil {
				cleanup()
			}
		}

		s.cancel()
	})
}

// CloseReason returns the reason recorded when the session was closed.
func (s *Session) CloseReason() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.closeReason
}

// ClosedAt returns the time when the session was closed.
func (s *Session) ClosedAt() time.Time {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.closedAt
}

// IsClosed reports whether the session has been closed.
func (s *Session) IsClosed() bool {
	select {
	case <-s.ctx.Done():
		return true
	default:
		return false
	}
}
