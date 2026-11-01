package sshclient

import (
	"context"
	"net"
	"strconv"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// Client represents an active SSH client connection to a remote server.
// It manages the underlying network connection, SSH protocol handshake,
// periodic keepalives, and pseudo-terminal (PTY) allocation.
type Client struct {
	sshClient     *ssh.Client
	netConn       net.Conn
	keepAliveDone chan struct{}
	closeOnce     sync.Once
	mu            sync.RWMutex
	closed        bool
}

// Dial establishes an SSH connection to the remote host specified in Config,
// using the provided SessionDialer (or DirectDialer if dialer is nil).
// Sensitive credentials in Config are automatically zeroed upon return.
func Dial(ctx context.Context, cfg *Config, dialer SessionDialer) (*Client, error) {
	if cfg == nil {
		return nil, NewSSHError(ErrCodeInternal, "ssh config cannot be nil", nil)
	}

	// Securely zero secrets from memory upon exit
	defer cfg.ZeroSecrets()

	clientConfig, err := cfg.BuildClientConfig()
	if err != nil {
		return nil, ClassifyError(err)
	}

	port := cfg.Port
	if port <= 0 {
		port = 22
	}
	addr := net.JoinHostPort(cfg.Host, strconv.Itoa(port))

	if dialer == nil {
		dialer = NewDirectDialer(cfg.ConnectTimeout)
	}

	// 1. Dial underlying transport connection
	netConn, err := dialer.Dial(ctx, "tcp", addr)
	if err != nil {
		return nil, ClassifyError(err)
	}

	// 2. Perform SSH client handshake respecting context timeout
	if deadline, ok := ctx.Deadline(); ok {
		_ = netConn.SetDeadline(deadline)
	} else if cfg.ConnectTimeout > 0 {
		_ = netConn.SetDeadline(time.Now().Add(cfg.ConnectTimeout))
	}

	sshConn, chans, reqs, err := ssh.NewClientConn(netConn, addr, clientConfig)
	if err != nil {
		_ = netConn.Close()
		return nil, ClassifyError(err)
	}

	// Clear handshake deadline once connected
	_ = netConn.SetDeadline(time.Time{})

	sshClient := ssh.NewClient(sshConn, chans, reqs)

	keepAliveInterval := cfg.KeepAliveInterval
	if keepAliveInterval < 0 {
		keepAliveInterval = 0
	} else if keepAliveInterval == 0 {
		keepAliveInterval = 30 * time.Second
	}

	c := &Client{
		sshClient:     sshClient,
		netConn:       netConn,
		keepAliveDone: make(chan struct{}),
	}

	if keepAliveInterval > 0 {
		go c.startKeepAlive(keepAliveInterval)
	}

	return c, nil
}

// OpenTerminal requests a pseudo-terminal (PTY) and starts an interactive shell
// on the remote server using the provided terminal configuration.
func (c *Client) OpenTerminal(termCfg TerminalConfig) (*TerminalSession, error) {
	c.mu.RLock()
	if c.closed {
		c.mu.RUnlock()
		return nil, NewSSHError(ErrCodeInternal, "ssh client is closed", net.ErrClosed)
	}
	c.mu.RUnlock()

	session, err := c.sshClient.NewSession()
	if err != nil {
		return nil, ClassifyError(err)
	}

	if termCfg.Term == "" {
		termCfg.Term = "xterm-256color"
	}
	if termCfg.Cols <= 0 {
		termCfg.Cols = 80
	}
	if termCfg.Rows <= 0 {
		termCfg.Rows = 24
	}
	if termCfg.Modes == nil {
		termCfg.Modes = ssh.TerminalModes{
			ssh.ECHO:          1,
			ssh.TTY_OP_ISPEED: 14400,
			ssh.TTY_OP_OSPEED: 14400,
		}
	}

	// Request PTY
	if err := session.RequestPty(termCfg.Term, int(termCfg.Rows), int(termCfg.Cols), termCfg.Modes); err != nil {
		_ = session.Close()
		return nil, NewSSHError(ErrCodePTYFailed, "failed to request pseudo-terminal", err)
	}

	// Create stdin/stdout/stderr pipes
	stdinPipe, err := session.StdinPipe()
	if err != nil {
		_ = session.Close()
		return nil, NewSSHError(ErrCodePTYFailed, "failed to open stdin pipe", err)
	}

	stdoutPipe, err := session.StdoutPipe()
	if err != nil {
		_ = session.Close()
		return nil, NewSSHError(ErrCodePTYFailed, "failed to open stdout pipe", err)
	}

	stderrPipe, err := session.StderrPipe()
	if err != nil {
		_ = session.Close()
		return nil, NewSSHError(ErrCodePTYFailed, "failed to open stderr pipe", err)
	}

	// Start remote shell
	if err := session.Shell(); err != nil {
		_ = session.Close()
		return nil, NewSSHError(ErrCodePTYFailed, "failed to start remote shell", err)
	}

	return &TerminalSession{
		session:   session,
		stdinPipe: stdinPipe,
		stdout:    stdoutPipe,
		stderr:    stderrPipe,
	}, nil
}

// startKeepAlive sends periodic keepalive requests to the remote server.
func (c *Client) startKeepAlive(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-c.keepAliveDone:
			return
		case <-ticker.C:
			// Send OpenSSH keepalive request
			_, _, err := c.sshClient.SendRequest("keepalive@openssh.com", true, nil)
			if err != nil {
				_ = c.Close()
				return
			}
		}
	}
}

// Close gracefully terminates the SSH connection, stops background keepalives,
// and closes the underlying network transport. Close is idempotent and thread-safe.
func (c *Client) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.mu.Lock()
		c.closed = true
		c.mu.Unlock()

		close(c.keepAliveDone)

		if c.sshClient != nil {
			err = c.sshClient.Close()
		}
		if c.netConn != nil {
			_ = c.netConn.Close()
		}
	})
	return err
}

// IsClosed reports whether the client has been closed.
func (c *Client) IsClosed() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.closed
}

// RawSSHClient returns the underlying *ssh.Client for advanced operations.
func (c *Client) RawSSHClient() *ssh.Client {
	return c.sshClient
}

// Conn returns the underlying net.Conn network transport.
func (c *Client) Conn() net.Conn {
	return c.netConn
}
