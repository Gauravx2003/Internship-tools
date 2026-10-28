package session

import "errors"

var (
	// ErrSessionNotFound is returned when an operation references a session ID that is not tracked.
	ErrSessionNotFound = errors.New("session not found")

	// ErrSessionAlreadyExists is returned when attempting to start a session with an ID that is already active.
	ErrSessionAlreadyExists = errors.New("session already exists")

	// ErrSessionLimitReached is returned when the active session count reaches the configured maximum limit.
	ErrSessionLimitReached = errors.New("session limit reached")

	// ErrManagerClosed is returned when attempting to start or operate on sessions after the manager has shut down.
	ErrManagerClosed = errors.New("session manager is closed")

	// ErrEmptySessionID is returned when an empty string is provided as a session identifier.
	ErrEmptySessionID = errors.New("session id cannot be empty")
)
