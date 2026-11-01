package sshclient

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"syscall"

	"golang.org/x/crypto/ssh"
)

// ErrorCode represents engine-standard error codes defined in Module-And-API.md (§4.1).
type ErrorCode string

const (
	ErrCodeDNSFailed         ErrorCode = "DNS_FAILED"
	ErrCodeConnectTimeout    ErrorCode = "CONNECT_TIMEOUT"
	ErrCodeConnectionRefused ErrorCode = "CONNECTION_REFUSED"
	ErrCodeAuthFailed        ErrorCode = "AUTH_FAILED"
	ErrCodeHostKeyMismatch   ErrorCode = "HOSTKEY_MISMATCH"
	ErrCodeHostKeyRejected   ErrorCode = "HOSTKEY_REJECTED"
	ErrCodePTYFailed         ErrorCode = "PTY_FAILED"
	ErrCodeInternal          ErrorCode = "INTERNAL"
)

// SSHError encapsulates an engine error code, human-readable message, and underlying cause.
type SSHError struct {
	Code    ErrorCode
	Message string
	Err     error
}

func (e *SSHError) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("[%s] %s: %v", e.Code, e.Message, e.Err)
	}
	return fmt.Sprintf("[%s] %s", e.Code, e.Message)
}

func (e *SSHError) Unwrap() error {
	return e.Err
}

// NewSSHError constructs a new SSHError with the given code, message, and underlying error.
func NewSSHError(code ErrorCode, message string, err error) *SSHError {
	return &SSHError{
		Code:    code,
		Message: message,
		Err:     err,
	}
}

// ClassifyError inspects network, crypto/ssh, and OS errors and maps them to
// standardized engine error codes.
func ClassifyError(err error) *SSHError {
	if err == nil {
		return nil
	}

	var sshErr *SSHError
	if errors.As(err, &sshErr) {
		return sshErr
	}

	// 1. DNS resolution failure
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return NewSSHError(ErrCodeDNSFailed, "failed to resolve host name", err)
	}

	// 2. Connection refused
	if errors.Is(err, syscall.ECONNREFUSED) || strings.Contains(strings.ToLower(err.Error()), "connection refused") {
		return NewSSHError(ErrCodeConnectionRefused, "remote host refused connection", err)
	}

	// 3. Connect timeout and cancellation
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return NewSSHError(ErrCodeConnectTimeout, "connection timed out or canceled", err)
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return NewSSHError(ErrCodeConnectTimeout, "connection timed out", err)
	}
	errMsgLower := strings.ToLower(err.Error())
	if strings.Contains(errMsgLower, "i/o timeout") ||
		strings.Contains(errMsgLower, "timed out") ||
		strings.Contains(errMsgLower, "deadline exceeded") ||
		strings.Contains(errMsgLower, "context canceled") {
		return NewSSHError(ErrCodeConnectTimeout, "connection timed out", err)
	}

	// 4. Host key verification errors
	if strings.Contains(errMsgLower, "host key mismatch") || strings.Contains(errMsgLower, "hostkey mismatch") {
		return NewSSHError(ErrCodeHostKeyMismatch, "server host key does not match trusted key", err)
	}
	if strings.Contains(errMsgLower, "host key verification failed") || strings.Contains(errMsgLower, "hostkey rejected") {
		return NewSSHError(ErrCodeHostKeyRejected, "server host key rejected by verifier", err)
	}

	// 5. Authentication errors
	var passphraseMissingErr *ssh.PassphraseMissingError
	if errors.As(err, &passphraseMissingErr) {
		return NewSSHError(ErrCodeAuthFailed, "private key is encrypted and requires a passphrase", err)
	}
	if strings.Contains(errMsgLower, "unable to authenticate") ||
		strings.Contains(errMsgLower, "no supported methods remain") ||
		strings.Contains(errMsgLower, "password authentication failed") ||
		strings.Contains(errMsgLower, "publickey") ||
		strings.Contains(errMsgLower, "auth failed") {
		return NewSSHError(ErrCodeAuthFailed, "ssh authentication failed: invalid credentials", err)
	}

	// 6. PTY / Session allocation errors
	if strings.Contains(errMsgLower, "request-pty") ||
		strings.Contains(errMsgLower, "pty allocation") ||
		strings.Contains(errMsgLower, "failed to request pty") ||
		strings.Contains(errMsgLower, "failed to start shell") {
		return NewSSHError(ErrCodePTYFailed, "failed to allocate pseudo-terminal or shell", err)
	}

	// 7. Generic / Internal error fallback
	return NewSSHError(ErrCodeInternal, "internal ssh error", err)
}
