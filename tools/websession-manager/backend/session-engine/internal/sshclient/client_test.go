package sshclient

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// mockSSHServer implements an in-process RFC 4253 SSH server for testing.
type mockSSHServer struct {
	listener        net.Listener
	addr            string
	port            int
	keepAliveCount  atomic.Int64
	windowChangeCnt atomic.Int64
	lastCols        atomic.Uint32
	lastRows        atomic.Uint32
	closeOnce       sync.Once
	stopCh          chan struct{}
}

func startMockSSHServer(t *testing.T, bobPubKey, charliePubKey ssh.PublicKey) *mockSSHServer {
	t.Helper()

	// 1. Generate host key
	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("failed to generate host key: %v", err)
	}
	hostSigner, err := ssh.NewSignerFromKey(hostPriv)
	if err != nil {
		t.Fatalf("failed to create host signer: %v", err)
	}

	server := &mockSSHServer{
		stopCh: make(chan struct{}),
	}

	// 2. Configure server auth callbacks
	serverConfig := &ssh.ServerConfig{
		PasswordCallback: func(conn ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
			if conn.User() == "alice" && string(pass) == "secret123" {
				return nil, nil
			}
			return nil, fmt.Errorf("password rejected for %s", conn.User())
		},
		PublicKeyCallback: func(conn ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if conn.User() == "bob" && bobPubKey != nil && bytes.Equal(key.Marshal(), bobPubKey.Marshal()) {
				return nil, nil
			}
			if conn.User() == "charlie" && charliePubKey != nil && bytes.Equal(key.Marshal(), charliePubKey.Marshal()) {
				return nil, nil
			}
			return nil, fmt.Errorf("public key rejected for %s", conn.User())
		},
	}
	serverConfig.AddHostKey(hostSigner)

	// 3. Bind TCP listener on ephemeral port
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("failed to listen on tcp: %v", err)
	}
	server.listener = ln
	server.addr = ln.Addr().String()

	tcpAddr := ln.Addr().(*net.TCPAddr)
	server.port = tcpAddr.Port

	go func() {
		for {
			tcpConn, err := ln.Accept()
			if err != nil {
				return
			}

			go server.handleConn(tcpConn, serverConfig)
		}
	}()

	return server
}

func (s *mockSSHServer) handleConn(tcpConn net.Conn, config *ssh.ServerConfig) {
	sConn, chans, reqs, err := ssh.NewServerConn(tcpConn, config)
	if err != nil {
		_ = tcpConn.Close()
		return
	}
	defer sConn.Close()

	// Handle global requests (e.g. keepalive@openssh.com)
	go func() {
		for req := range reqs {
			if req.Type == "keepalive@openssh.com" {
				s.keepAliveCount.Add(1)
				if req.WantReply {
					_ = req.Reply(true, nil)
				}
			} else if req.WantReply {
				_ = req.Reply(false, nil)
			}
		}
	}()

	// Handle incoming channels
	for newChan := range chans {
		if newChan.ChannelType() != "session" {
			_ = newChan.Reject(ssh.UnknownChannelType, "unsupported channel")
			continue
		}

		channel, requests, err := newChan.Accept()
		if err != nil {
			continue
		}

		go func(ch ssh.Channel, reqList <-chan *ssh.Request) {
			defer ch.Close()
			for req := range reqList {
				switch req.Type {
				case "pty-req":
					if req.WantReply {
						_ = req.Reply(true, nil)
					}
				case "shell":
					if req.WantReply {
						_ = req.Reply(true, nil)
					}
					// Echo loop: copy stdin back to stdout
					go func() {
						_, _ = io.Copy(ch, ch)
					}()
				case "window-change":
					if len(req.Payload) >= 8 {
						cols := binary.BigEndian.Uint32(req.Payload[0:4])
						rows := binary.BigEndian.Uint32(req.Payload[4:8])
						s.lastCols.Store(cols)
						s.lastRows.Store(rows)
						s.windowChangeCnt.Add(1)
					}
					if req.WantReply {
						_ = req.Reply(true, nil)
					}
				default:
					if req.WantReply {
						_ = req.Reply(false, nil)
					}
				}
			}
		}(channel, requests)
	}
}

func (s *mockSSHServer) Close() {
	s.closeOnce.Do(func() {
		close(s.stopCh)
		if s.listener != nil {
			_ = s.listener.Close()
		}
	})
}

func TestClient_Dial_PasswordAuth_And_Terminal(t *testing.T) {
	server := startMockSSHServer(t, nil, nil)
	defer server.Close()

	cfg := Config{
		Host:              "127.0.0.1",
		Port:              server.port,
		Username:          "alice",
		AuthType:          AuthTypePassword,
		Password:          "secret123",
		ConnectTimeout:    3 * time.Second,
		KeepAliveInterval: 1 * time.Second,
	}

	ctx := context.Background()
	client, err := Dial(ctx, &cfg, nil)
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer client.Close()

	if client.IsClosed() {
		t.Fatalf("client should not be closed")
	}

	// Test Terminal allocation and shell
	termCfg := DefaultTerminalConfig()
	term, err := client.OpenTerminal(termCfg)
	if err != nil {
		t.Fatalf("OpenTerminal failed: %v", err)
	}
	defer term.Close()

	// Write into stdin and read back from echoed stdout
	inputMsg := "echo test message\n"
	if _, err := term.Stdin().Write([]byte(inputMsg)); err != nil {
		t.Fatalf("failed to write to terminal stdin: %v", err)
	}

	readBuf := make([]byte, len(inputMsg))
	readDone := make(chan error, 1)
	go func() {
		_, err := io.ReadFull(term.Stdout(), readBuf)
		readDone <- err
	}()

	select {
	case err := <-readDone:
		if err != nil {
			t.Fatalf("failed to read from terminal stdout: %v", err)
		}
		if string(readBuf) != inputMsg {
			t.Fatalf("terminal echo mismatch: got %q, want %q", string(readBuf), inputMsg)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out reading from terminal stdout")
	}

	// Test Resize
	if err := term.Resize(132, 43); err != nil {
		t.Fatalf("Resize failed: %v", err)
	}

	// Wait briefly for window-change message to reach mock server
	time.Sleep(50 * time.Millisecond)
	if server.windowChangeCnt.Load() == 0 {
		t.Errorf("expected server to receive window-change request")
	}
	if server.lastCols.Load() != 132 || server.lastRows.Load() != 43 {
		t.Errorf("expected cols=132, rows=43; got cols=%d, rows=%d", server.lastCols.Load(), server.lastRows.Load())
	}
}

func TestClient_Dial_KeyAuth(t *testing.T) {
	bobKeyBytes := generateTestEd25519Key(t)
	signer, err := ssh.ParsePrivateKey(bobKeyBytes)
	if err != nil {
		t.Fatalf("failed to parse bob private key: %v", err)
	}

	server := startMockSSHServer(t, signer.PublicKey(), nil)
	defer server.Close()

	cfg := Config{
		Host:           "127.0.0.1",
		Port:           server.port,
		Username:       "bob",
		AuthType:       AuthTypeKey,
		PrivateKey:     bobKeyBytes,
		ConnectTimeout: 3 * time.Second,
	}

	client, err := Dial(context.Background(), &cfg, nil)
	if err != nil {
		t.Fatalf("Dial with key auth failed: %v", err)
	}
	defer client.Close()

	if client.RawSSHClient() == nil {
		t.Errorf("expected RawSSHClient to be non-nil")
	}
}

func TestClient_Dial_KeyPassphraseAuth(t *testing.T) {
	passphrase := "decryption-passphrase-99"
	charlieKeyBytes := generateTestEncryptedRSAKey(t, passphrase)
	signer, err := ssh.ParsePrivateKeyWithPassphrase(charlieKeyBytes, []byte(passphrase))
	if err != nil {
		t.Fatalf("failed to parse charlie encrypted key: %v", err)
	}

	server := startMockSSHServer(t, nil, signer.PublicKey())
	defer server.Close()

	cfg := Config{
		Host:           "127.0.0.1",
		Port:           server.port,
		Username:       "charlie",
		AuthType:       AuthTypeKeyPassphrase,
		PrivateKey:     charlieKeyBytes,
		Passphrase:     passphrase,
		ConnectTimeout: 3 * time.Second,
	}

	client, err := Dial(context.Background(), &cfg, nil)
	if err != nil {
		t.Fatalf("Dial with passphrase key auth failed: %v", err)
	}
	defer client.Close()
}

func TestClient_Dial_AuthFailed(t *testing.T) {
	server := startMockSSHServer(t, nil, nil)
	defer server.Close()

	cfg := Config{
		Host:           "127.0.0.1",
		Port:           server.port,
		Username:       "alice",
		AuthType:       AuthTypePassword,
		Password:       "WRONG_PASSWORD",
		ConnectTimeout: 2 * time.Second,
	}

	_, err := Dial(context.Background(), &cfg, nil)
	if err == nil {
		t.Fatalf("expected error dialing with invalid password, got nil")
	}

	classified := ClassifyError(err)
	if classified.Code != ErrCodeAuthFailed {
		t.Errorf("expected ErrCodeAuthFailed, got %q", classified.Code)
	}
}

func TestClient_Dial_ConnectionRefused(t *testing.T) {
	// Pick an address/port that is definitely not listening
	cfg := Config{
		Host:           "127.0.0.1",
		Port:           54321,
		Username:       "alice",
		AuthType:       AuthTypePassword,
		Password:       "any",
		ConnectTimeout: 1 * time.Second,
	}

	_, err := Dial(context.Background(), &cfg, nil)
	if err == nil {
		t.Fatalf("expected connection refused error, got nil")
	}

	classified := ClassifyError(err)
	if classified.Code != ErrCodeConnectionRefused {
		t.Errorf("expected ErrCodeConnectionRefused, got %q", classified.Code)
	}
}

func TestClient_Dial_Timeout(t *testing.T) {
	// Create context that is already canceled
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	cfg := Config{
		Host:           "127.0.0.1",
		Port:           22,
		Username:       "alice",
		AuthType:       AuthTypePassword,
		Password:       "secret",
		ConnectTimeout: 1 * time.Second,
	}

	_, err := Dial(ctx, &cfg, nil)
	if err == nil {
		t.Fatalf("expected context cancellation error, got nil")
	}

	classified := ClassifyError(err)
	if classified.Code != ErrCodeConnectTimeout {
		t.Errorf("expected ErrCodeConnectTimeout, got %q", classified.Code)
	}
}

func TestClient_Dial_ZeroesSecrets(t *testing.T) {
	server := startMockSSHServer(t, nil, nil)
	defer server.Close()

	key := []byte("temporary-private-key-data")
	cfg := Config{
		Host:           "127.0.0.1",
		Port:           server.port,
		Username:       "alice",
		AuthType:       AuthTypePassword,
		Password:       "secret123",
		PrivateKey:     key,
		Passphrase:     "my-passphrase",
		ConnectTimeout: 3 * time.Second,
	}

	client, err := Dial(context.Background(), &cfg, nil)
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer client.Close()

	// Secrets in cfg should now be cleared
	if cfg.Password != "" {
		t.Errorf("expected Password to be wiped, got %q", cfg.Password)
	}
	if cfg.Passphrase != "" {
		t.Errorf("expected Passphrase to be wiped, got %q", cfg.Passphrase)
	}
	if cfg.PrivateKey != nil {
		t.Errorf("expected PrivateKey to be nil, got %v", cfg.PrivateKey)
	}
}

func TestClient_KeepAlive_Execution(t *testing.T) {
	server := startMockSSHServer(t, nil, nil)
	defer server.Close()

	cfg := Config{
		Host:              "127.0.0.1",
		Port:              server.port,
		Username:          "alice",
		AuthType:          AuthTypePassword,
		Password:          "secret123",
		ConnectTimeout:    2 * time.Second,
		KeepAliveInterval: 50 * time.Millisecond, // rapid keepalive for test
	}

	client, err := Dial(context.Background(), &cfg, nil)
	if err != nil {
		t.Fatalf("Dial failed: %v", err)
	}
	defer client.Close()

	// Wait for multiple keepalives to fire
	time.Sleep(180 * time.Millisecond)

	count := server.keepAliveCount.Load()
	if count < 2 {
		t.Errorf("expected at least 2 keepalives to fire, received %d", count)
	}
}
