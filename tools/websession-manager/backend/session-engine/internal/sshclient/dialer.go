package sshclient

import (
	"context"
	"net"
	"time"

	"golang.org/x/crypto/ssh"
)

// SessionDialer defines the interface for establishing the underlying network connection
// to a remote SSH host.
//
// In the MVP, DirectDialer connects directly to the target host via TCP (PRD FR-E7).
// Post-MVP, an AgentDialer can implement this interface to route connections through
// an outbound agent tunnel without opening firewall ports (PRD NFR-A1).
type SessionDialer interface {
	Dial(ctx context.Context, network, addr string) (net.Conn, error)
}

// DirectDialer connects directly to the target host over TCP.
type DirectDialer struct {
	Timeout   time.Duration
	KeepAlive time.Duration
}

// NewDirectDialer creates a DirectDialer with the specified connection timeout.
func NewDirectDialer(timeout time.Duration) *DirectDialer {
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	return &DirectDialer{
		Timeout:   timeout,
		KeepAlive: 30 * time.Second,
	}
}

// Dial establishes a direct TCP connection to the destination address,
// respecting context cancellation and configured timeouts.
func (d *DirectDialer) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	dialer := &net.Dialer{
		Timeout:   d.Timeout,
		KeepAlive: d.KeepAlive,
	}
	return dialer.DialContext(ctx, network, addr)
}

// DialSSH provides a convenience helper for generating a basic ssh.ClientConfig
// with password authentication (maintained for backwards compatibility).
func DialSSH(username, password string) (*ssh.ClientConfig, error) {
	return &ssh.ClientConfig{
		User: username,
		Auth: []ssh.AuthMethod{
			ssh.Password(password),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         5 * time.Second,
	}, nil
}
