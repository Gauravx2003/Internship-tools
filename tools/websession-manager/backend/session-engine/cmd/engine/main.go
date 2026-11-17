package main

import (
	"bufio"
	"bytes"
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"

	"github.com/gaurav/web-session/session-engine/internal/control"
	"github.com/gaurav/web-session/session-engine/internal/session"
)

// loadDotEnv searches and loads simple KEY=VALUE pairs from .env files if present.
func loadDotEnv() {
	var candidates []string

	if exe, err := os.Executable(); err == nil {
		exeDir := filepath.Dir(exe)
		candidates = append(candidates,
			filepath.Join(exeDir, ".env"),
			filepath.Join(exeDir, "..", ".env"),
		)
	}

	candidates = append(candidates,
		".env",
		"backend/session-engine/.env",
		"tools/websession-manager/backend/session-engine/.env",
	)

	for _, p := range candidates {
		data, err := os.ReadFile(p)
		if err != nil {
			continue
		}

		scanner := bufio.NewScanner(bytes.NewReader(data))
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if len(line) == 0 || strings.HasPrefix(line, "#") {
				continue
			}
			parts := strings.SplitN(line, "=", 2)
			if len(parts) == 2 {
				k := strings.TrimSpace(parts[0])
				v := strings.Trim(strings.TrimSpace(parts[1]), "\"'")
				if os.Getenv(k) == "" {
					_ = os.Setenv(k, v)
				}
			}
		}
		log.Printf("[session-engine] Loaded environment variables from %s", p)
		break
	}
}

func main() {
	// Standard output is exclusively reserved for NDJSON protocol envelopes.
	// All diagnostic and informational logs must go to stderr.
	log.SetOutput(os.Stderr)

	// Load .env if present
	loadDotEnv()

	stdioFlag := flag.Bool("stdio", true, "Run engine in multiplexed stdio pipe mode")
	maxSessionsFlag := flag.Int("max-sessions", control.DefaultMaxSessionsPerProcess, "Maximum concurrent sessions for this process")
	flag.Parse()
	_ = stdioFlag

	log.Println("[session-engine] Starting Go Session Engine in multiplexed stdio pipe mode...")

	if os.Getenv("ENGINE_INSECURE_IGNORE_HOSTKEY") == "true" {
		log.Println("[session-engine] ⚠️ WARNING: Insecure host key verification ENABLED (ENGINE_INSECURE_IGNORE_HOSTKEY=true)")
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	maxSessions := *maxSessionsFlag
	if envMax := os.Getenv("ENGINE_MAX_SESSIONS_PER_PROC"); envMax != "" {
		if m, err := strconv.Atoi(envMax); err == nil && m > 0 {
			maxSessions = m
		}
	}

	sessionMgr := session.NewManagerWithContext(ctx, maxSessions)
	stdioServer := control.NewStdioServer(os.Stdin, os.Stdout, sessionMgr, maxSessions)

	log.Printf("[session-engine] Stdio engine listening on stdin/stdout (max sessions limit: %d)", maxSessions)
	if err := stdioServer.Run(ctx); err != nil && err != context.Canceled {
		log.Printf("[session-engine] Stdio server terminated: %v", err)
	}

	log.Println("[session-engine] Closing all active sessions on exit...")
	stdioServer.CloseAll("engine_shutdown")
	log.Println("[session-engine] Stdio engine stopped cleanly.")
}
