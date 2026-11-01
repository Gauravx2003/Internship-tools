package sshclient

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestDirectDialer_Success(t *testing.T) {
	// Start an ephemeral TCP listener
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to create tcp listener: %v", err)
	}
	defer ln.Close()

	accepted := make(chan net.Conn, 1)
	go func() {
		conn, err := ln.Accept()
		if err == nil {
			accepted <- conn
		}
	}()

	dialer := NewDirectDialer(2 * time.Second)
	ctx := context.Background()

	clientConn, err := dialer.Dial(ctx, "tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("DirectDialer.Dial failed: %v", err)
	}
	defer clientConn.Close()

	serverConn := <-accepted
	defer serverConn.Close()

	// Verify write from client to server
	msg := []byte("hello-ssh-dialer")
	if _, err := clientConn.Write(msg); err != nil {
		t.Fatalf("client write failed: %v", err)
	}

	buf := make([]byte, len(msg))
	if _, err := serverConn.Read(buf); err != nil {
		t.Fatalf("server read failed: %v", err)
	}

	if string(buf) != string(msg) {
		t.Fatalf("received %q, want %q", string(buf), string(msg))
	}
}

func TestDirectDialer_ContextCancelled(t *testing.T) {
	dialer := NewDirectDialer(5 * time.Second)
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	_, err := dialer.Dial(ctx, "tcp", "127.0.0.1:9999")
	if err == nil {
		t.Fatalf("expected error dialing with canceled context, got nil")
	}
}

func TestDialSSH_Helper(t *testing.T) {
	cfg, err := DialSSH("testuser", "testpass")
	if err != nil {
		t.Fatalf("DialSSH returned unexpected error: %v", err)
	}
	if cfg.User != "testuser" {
		t.Errorf("expected User to be testuser, got %s", cfg.User)
	}
	if len(cfg.Auth) != 1 {
		t.Errorf("expected 1 auth method, got %d", len(cfg.Auth))
	}
	if cfg.Timeout != 5*time.Second {
		t.Errorf("expected timeout 5s, got %v", cfg.Timeout)
	}
}
