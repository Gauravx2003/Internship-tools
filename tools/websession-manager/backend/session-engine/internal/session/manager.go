package session

import (
	"context"
	"fmt"
	"sync"
)

// DefaultMaxSessions is the default ceiling on concurrent sessions.
const DefaultMaxSessions = 50

// Manager tracks the engine's list of open sessions.
// It starts new sessions, closes finished ones, counts active ones,
// enforces a maximum session limit, and closes everything neatly when the engine stops.
//
// Manager implements control.SessionTracker via ActiveSessionCount().
type Manager struct {
	mu          sync.RWMutex
	sessions    map[string]*Session
	maxSessions int
	closed      bool
	parentCtx   context.Context
}

// NewManager initializes a new Session Manager with a maximum session limit.
// If maxSessions <= 0, DefaultMaxSessions is used.
func NewManager(maxSessions int) *Manager {
	return NewManagerWithContext(context.Background(), maxSessions)
}

// NewManagerWithContext initializes a Session Manager tied to a parent context.
func NewManagerWithContext(ctx context.Context, maxSessions int) *Manager {
	if maxSessions <= 0 {
		maxSessions = DefaultMaxSessions
	}
	if ctx == nil {
		ctx = context.Background()
	}

	return &Manager{
		sessions:    make(map[string]*Session),
		maxSessions: maxSessions,
		parentCtx:   ctx,
	}
}

// MaxSessions returns the configured maximum session limit.
func (m *Manager) MaxSessions() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.maxSessions
}

// ActiveSessionCount returns the number of currently active sessions.
// Satisfies the control.SessionTracker interface.
func (m *Manager) ActiveSessionCount() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.sessions)
}

// StartSession creates, registers, and returns a new active session.
// It returns ErrManagerClosed if the engine is shutting down,
// ErrSessionLimitReached if active count is at or exceeds maxSessions,
// or ErrSessionAlreadyExists if the session ID is already registered.
func (m *Manager) StartSession(id string) (*Session, error) {
	if id == "" {
		return nil, ErrEmptySessionID
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	if m.closed {
		return nil, ErrManagerClosed
	}

	if len(m.sessions) >= m.maxSessions {
		return nil, fmt.Errorf("%w: limit of %d reached", ErrSessionLimitReached, m.maxSessions)
	}

	if _, exists := m.sessions[id]; exists {
		return nil, fmt.Errorf("%w: session ID %q", ErrSessionAlreadyExists, id)
	}

	sess := newSession(id, m.parentCtx)
	m.sessions[id] = sess
	return sess, nil
}

// CloseSession closes a finished or specified session and removes it from tracking.
// If the session is not found, ErrSessionNotFound is returned.
func (m *Manager) CloseSession(id string, reason string) error {
	m.mu.Lock()
	sess, exists := m.sessions[id]
	if !exists {
		m.mu.Unlock()
		return fmt.Errorf("%w: session ID %q", ErrSessionNotFound, id)
	}
	delete(m.sessions, id)
	m.mu.Unlock()

	sess.Close(reason)
	return nil
}

// GetSession retrieves an active session by its ID.
func (m *Manager) GetSession(id string) (*Session, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	sess, exists := m.sessions[id]
	return sess, exists
}

// ListSessionIDs returns a snapshot list of all currently active session IDs.
func (m *Manager) ListSessionIDs() []string {
	m.mu.RLock()
	defer m.mu.RUnlock()

	ids := make([]string, 0, len(m.sessions))
	for id := range m.sessions {
		ids = append(ids, id)
	}
	return ids
}

// CloseAll closes all open sessions neatly with the given reason and prevents
// new sessions from starting. Used when the engine is told to stop.
func (m *Manager) CloseAll(reason string) error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil
	}
	m.closed = true

	active := make([]*Session, 0, len(m.sessions))
	for _, sess := range m.sessions {
		active = append(active, sess)
	}
	m.sessions = make(map[string]*Session)
	m.mu.Unlock()

	for _, sess := range active {
		sess.Close(reason)
	}
	return nil
}

// IsClosed reports whether the session manager has been stopped.
func (m *Manager) IsClosed() bool {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.closed
}
