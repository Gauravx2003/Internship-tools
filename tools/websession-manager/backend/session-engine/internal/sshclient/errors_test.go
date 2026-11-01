package sshclient

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"

	"golang.org/x/crypto/ssh"
)

func TestClassifyError(t *testing.T) {
	tests := []struct {
		name     string
		err      error
		wantCode ErrorCode
	}{
		{
			name:     "nil error",
			err:      nil,
			wantCode: "",
		},
		{
			name:     "already SSHError",
			err:      NewSSHError(ErrCodeAuthFailed, "already classified", nil),
			wantCode: ErrCodeAuthFailed,
		},
		{
			name:     "DNS resolution error",
			err:      &net.DNSError{Err: "no such host", Name: "invalid.domain.motadata"},
			wantCode: ErrCodeDNSFailed,
		},
		{
			name:     "Connection refused syscall",
			err:      syscall.ECONNREFUSED,
			wantCode: ErrCodeConnectionRefused,
		},
		{
			name:     "Connection refused string",
			err:      errors.New("dial tcp 127.0.0.1:2222: connect: connection refused"),
			wantCode: ErrCodeConnectionRefused,
		},
		{
			name:     "Context deadline exceeded timeout",
			err:      context.DeadlineExceeded,
			wantCode: ErrCodeConnectTimeout,
		},
		{
			name:     "i/o timeout string",
			err:      errors.New("read tcp 127.0.0.1:4455->127.0.0.1:22: i/o timeout"),
			wantCode: ErrCodeConnectTimeout,
		},
		{
			name:     "Passphrase missing error",
			err:      &ssh.PassphraseMissingError{},
			wantCode: ErrCodeAuthFailed,
		},
		{
			name:     "Unable to authenticate string",
			err:      errors.New("ssh: unable to authenticate, attempted methods [none password], no supported methods remain"),
			wantCode: ErrCodeAuthFailed,
		},
		{
			name:     "Host key mismatch string",
			err:      errors.New("ssh: host key mismatch for server 10.0.0.1"),
			wantCode: ErrCodeHostKeyMismatch,
		},
		{
			name:     "Host key rejected string",
			err:      errors.New("ssh: host key verification failed"),
			wantCode: ErrCodeHostKeyRejected,
		},
		{
			name:     "PTY allocation failure",
			err:      errors.New("ssh: request-pty failed: permission denied"),
			wantCode: ErrCodePTYFailed,
		},
		{
			name:     "Unknown/internal error fallback",
			err:      errors.New("something unexpected happened in kernel"),
			wantCode: ErrCodeInternal,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			classified := ClassifyError(tc.err)
			if tc.err == nil {
				if classified != nil {
					t.Fatalf("expected nil for nil error, got %v", classified)
				}
				return
			}

			if classified == nil {
				t.Fatalf("expected classified error, got nil")
			}
			if classified.Code != tc.wantCode {
				t.Errorf("classified.Code = %q, want %q", classified.Code, tc.wantCode)
			}
			if classified.Error() == "" {
				t.Errorf("expected non-empty Error() string")
			}
		})
	}
}

func TestSSHErrorUnwrap(t *testing.T) {
	underlying := errors.New("underlying cause")
	sshErr := NewSSHError(ErrCodeConnectionRefused, "refused", underlying)

	if !errors.Is(sshErr, underlying) {
		t.Fatalf("expected errors.Is to match underlying cause")
	}
	if sshErr.Unwrap() != underlying {
		t.Fatalf("expected Unwrap() to return underlying error")
	}
}
