package sshclient

import (
	"errors"
	"io"
	"sync"

	"golang.org/x/crypto/ssh"
)

// TerminalConfig specifies parameters for requesting a remote pseudo-terminal (PTY).
type TerminalConfig struct {
	Term  string            // Terminal emulation type (default: "xterm-256color")
	Cols  uint32            // Terminal width in columns (default: 80)
	Rows  uint32            // Terminal height in rows (default: 24)
	Modes ssh.TerminalModes // PTY terminal mode settings
}

// DefaultTerminalConfig returns a standard TerminalConfig matching modern web terminals (xterm-256color, 80x24).
func DefaultTerminalConfig() TerminalConfig {
	return TerminalConfig{
		Term: "xterm-256color",
		Cols: 80,
		Rows: 24,
		Modes: ssh.TerminalModes{
			ssh.ECHO:          1,     // enable echoing
			ssh.TTY_OP_ISPEED: 14400, // input speed = 14.4kbaud
			ssh.TTY_OP_OSPEED: 14400, // output speed = 14.4kbaud
		},
	}
}

// TerminalSession encapsulates an active SSH pseudo-terminal session.
// It provides direct access to stdin, stdout, and stderr streams, and supports
// live window resizing.
type TerminalSession struct {
	session   *ssh.Session
	stdinPipe io.WriteCloser
	stdout    io.Reader
	stderr    io.Reader
	closeOnce sync.Once
}

// Stdin returns the write stream connected to the remote shell's standard input.
func (t *TerminalSession) Stdin() io.WriteCloser {
	return t.stdinPipe
}

// Stdout returns the read stream connected to the remote shell's standard output.
func (t *TerminalSession) Stdout() io.Reader {
	return t.stdout
}

// Stderr returns the read stream connected to the remote shell's standard error.
func (t *TerminalSession) Stderr() io.Reader {
	return t.stderr
}

// Resize changes the remote pseudo-terminal's window dimensions.
// Corresponds to the Vert.x -> Engine `resize` envelope message.
func (t *TerminalSession) Resize(cols, rows uint32) error {
	if t.session == nil {
		return errors.New("terminal session is not active")
	}
	return t.session.WindowChange(int(rows), int(cols))
}

// Wait blocks until the remote command or interactive shell exits.
func (t *TerminalSession) Wait() error {
	if t.session == nil {
		return nil
	}
	return t.session.Wait()
}

// Close terminates the terminal session and releases allocated remote PTY resources.
func (t *TerminalSession) Close() error {
	var err error
	t.closeOnce.Do(func() {
		if t.stdinPipe != nil {
			_ = t.stdinPipe.Close()
		}
		if t.session != nil {
			err = t.session.Close()
		}
	})
	return err
}

// RawSession returns the underlying *ssh.Session for advanced operations.
func (t *TerminalSession) RawSession() *ssh.Session {
	return t.session
}
