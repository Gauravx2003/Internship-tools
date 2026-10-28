package control

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// SessionTracker is an abstraction for querying active session stats.
// By keeping this as an interface, the control server remains completely decoupled
// from session management implementations.
type SessionTracker interface {
	ActiveSessionCount() int
}

// NoopSessionTracker is a placeholder tracker returning 0 active sessions,
// used when a Session Manager is not provided.
type NoopSessionTracker struct{}

func (n NoopSessionTracker) ActiveSessionCount() int {
	return 0
}

// Config holds configuration parameters for the control server and engine limits.
type Config struct {
	Host            string
	Port            int
	Addr            string
	Token           string
	MaxSessions     int
	ShutdownTimeout time.Duration
}

// LoadConfig loads control server configuration from environment variables,
// falling back to defaults that match the Vert.x AppConfig.
func LoadConfig() (Config, error) {
	host := os.Getenv("ENGINE_HOST")
	if host == "" {
		host = "127.0.0.1"
	}

	portStr := os.Getenv("ENGINE_PORT")
	port := 8081
	if portStr != "" {
		if p, err := strconv.Atoi(portStr); err == nil && p > 0 {
			port = p
		}
	}

	addr := os.Getenv("ENGINE_ADDR")
	if addr == "" {
		addr = fmt.Sprintf("%s:%d", host, port)
	}

	token := os.Getenv("ENGINE_AUTH_TOKEN")
	if token == "" {
		token = os.Getenv("ENGINE_TOKEN")
	}
	if token == "" {
		token = "dev-secret-engine-token-change-in-prod"
	}

	maxSessions := 50
	if maxStr := os.Getenv("ENGINE_MAX_SESSIONS"); maxStr != "" {
		if m, err := strconv.Atoi(maxStr); err == nil && m > 0 {
			maxSessions = m
		}
	}

	return Config{
		Host:            host,
		Port:            port,
		Addr:            addr,
		Token:           token,
		MaxSessions:     maxSessions,
		ShutdownTimeout: 10 * time.Second,
	}, nil
}
