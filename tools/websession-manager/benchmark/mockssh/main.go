package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"

	"golang.org/x/crypto/ssh"
)

func main() {
	port := flag.Int("port", 2222, "Port to listen on")
	flag.Parse()

	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		log.Fatalf("Failed to generate ed25519 host key: %v", err)
	}
	hostSigner, err := ssh.NewSignerFromKey(hostPriv)
	if err != nil {
		log.Fatalf("Failed to create signer: %v", err)
	}

	config := &ssh.ServerConfig{
		PasswordCallback: func(conn ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
			if string(pass) == "benchpass" || string(pass) == "secret123" {
				return nil, nil
			}
			return nil, fmt.Errorf("invalid password")
		},
		PublicKeyCallback: func(conn ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			return nil, nil // Accept all public keys for benchmarking
		},
	}
	config.AddHostKey(hostSigner)

	listener, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", *port))
	if err != nil {
		log.Fatalf("Failed to bind on 127.0.0.1:%d: %v", *port, err)
	}
	defer listener.Close()

	log.Printf("[mock-ssh] Benchmarking SSH Server listening on 127.0.0.1:%d", *port)

	var activeConns atomic.Int64

	sigCtx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		for {
			tcpConn, err := listener.Accept()
			if err != nil {
				return
			}
			activeConns.Add(1)
			go func(c net.Conn) {
				defer activeConns.Add(-1)
				handleConn(c, config)
			}(tcpConn)
		}
	}()

	<-sigCtx.Done()
	log.Println("[mock-ssh] Stopping Mock SSH server...")
}

func handleConn(tcpConn net.Conn, config *ssh.ServerConfig) {
	defer tcpConn.Close()

	sConn, chans, reqs, err := ssh.NewServerConn(tcpConn, config)
	if err != nil {
		return
	}
	defer sConn.Close()

	go func() {
		for req := range reqs {
			if req.Type == "keepalive@openssh.com" && req.WantReply {
				_ = req.Reply(true, nil)
			} else if req.WantReply {
				_ = req.Reply(false, nil)
			}
		}
	}()

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
					// Terminal echo loop: echo input back to output
					go func() {
						_, _ = io.Copy(ch, ch)
					}()
				case "window-change":
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
