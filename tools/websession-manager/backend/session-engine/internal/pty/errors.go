package pty

import "errors"

var (
	// ErrPumpClosed is returned when an operation is attempted on a closed pump.
	ErrPumpClosed = errors.New("pty pump is closed")

	// ErrPumpAlreadyStarted is returned when Start() is called more than once.
	ErrPumpAlreadyStarted = errors.New("pty pump already started")

	// ErrInvalidDimensions is returned when terminal resize dimensions are non-positive.
	ErrInvalidDimensions = errors.New("terminal dimensions must be greater than zero")

	// ErrNilTerminal is returned when NewPump is invoked with a nil Terminal instance.
	ErrNilTerminal = errors.New("terminal cannot be nil")

	// ErrNilSink is returned when NewPump is invoked with a nil OutputSink instance.
	ErrNilSink = errors.New("output sink cannot be nil")
)
