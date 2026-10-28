package control

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"sync"
	"time"
)

// Server represents the control HTTP server of the Go Session Engine.
// It serves as the front door for Vert.x Engine Manager to perform health checks,
// query active session count, and trigger graceful engine shutdown.
type Server struct {
	cfg          Config
	tracker      SessionTracker
	startedAt    time.Time
	shutdownOnce sync.Once
	shutdownReq  chan struct{}
	readyOnce    sync.Once
	readyCh      chan struct{}
	http         *http.Server
	listenerMu   sync.RWMutex
	listener     net.Listener
	handler      http.Handler
}

// NewServer initializes and configures a new Server instance.
func NewServer(cfg Config, tracker SessionTracker) *Server {
	if tracker == nil {
		tracker = NoopSessionTracker{}
	}

	s := &Server{
		cfg:         cfg,
		tracker:     tracker,
		startedAt:   time.Now().UTC(),
		shutdownReq: make(chan struct{}),
		readyCh:     make(chan struct{}),
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", s.health)
	mux.HandleFunc("GET /status", s.status)
	mux.HandleFunc("POST /shutdown", s.shutdown)

	s.handler = requireToken(cfg.Token, mux)

	s.http = &http.Server{
		Addr:    cfg.Addr,
		Handler: s.handler,
	}

	return s
}

// Handler returns the underlying http.Handler (used primarily for unit testing).
func (s *Server) Handler() http.Handler {
	return s.handler
}

// Ready returns a channel that is closed once the server has bound its listener and started serving.
func (s *Server) Ready() <-chan struct{} {
	return s.readyCh
}

// Addr returns the bound network address of the server, or the configured address if not yet bound.
func (s *Server) Addr() string {
	s.listenerMu.RLock()
	defer s.listenerMu.RUnlock()
	if s.listener != nil {
		return s.listener.Addr().String()
	}
	return s.cfg.Addr
}

// ShutdownReq signals the server to initiate graceful shutdown.
func (s *Server) ShutdownReq() {
	s.shutdownOnce.Do(func() {
		close(s.shutdownReq)
	})
}

// Run starts the control server and blocks until ctx is canceled, a shutdown request
// is received via /shutdown, or a fatal network error occurs.
func (s *Server) Run(ctx context.Context) error {
	ln, err := net.Listen("tcp", s.cfg.Addr)
	if err != nil {
		return fmt.Errorf("control server failed to bind to %s: %w", s.cfg.Addr, err)
	}

	s.listenerMu.Lock()
	s.listener = ln
	s.listenerMu.Unlock()

	s.readyOnce.Do(func() {
		close(s.readyCh)
	})

	log.Printf("[control-server] Listening on http://%s", ln.Addr().String())

	errCh := make(chan error, 1)
	go func() {
		if err := s.http.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
		log.Println("[control-server] Shutdown triggered by context cancellation")
	case <-s.shutdownReq:
		log.Println("[control-server] Shutdown triggered by /shutdown endpoint")
	}

	timeout := s.cfg.ShutdownTimeout
	if timeout <= 0 {
		timeout = 10 * time.Second
	}
	shutCtx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	log.Println("[control-server] Gracefully shutting down HTTP server...")
	return s.http.Shutdown(shutCtx)
}
