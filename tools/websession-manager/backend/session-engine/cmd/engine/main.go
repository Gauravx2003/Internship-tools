package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gaurav/web-session/session-engine/internal/control"
	"github.com/gaurav/web-session/session-engine/internal/session"
)

func main() {
	log.Println("[session-engine] Starting Go Session Engine...")

	cfg, err := control.LoadConfig()
	if err != nil {
		log.Fatalf("[session-engine] Failed to load configuration: %v", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Initialize the Session Manager with the configured max sessions limit and root context.
	sessionMgr := session.NewManagerWithContext(ctx, cfg.MaxSessions)

	// Inject session manager into control server as SessionTracker.
	// This enables GET /status to report real active session counts.
	server := control.NewServer(cfg, sessionMgr)

	log.Printf("[session-engine] Control server configured on %s (max sessions limit: %d)", cfg.Addr, sessionMgr.MaxSessions())
	if err := server.Run(ctx); err != nil {
		log.Fatalf("[session-engine] Control server error: %v", err)
	}

	// Close all active sessions neatly when the engine stops.
	log.Println("[session-engine] Closing all active sessions...")
	_ = sessionMgr.CloseAll("engine_shutdown")

	log.Println("[session-engine] Control server and session manager stopped cleanly.")
}
