package control

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

type mockTracker struct {
	count int
}

func (m mockTracker) ActiveSessionCount() int {
	return m.count
}

func testConfig() Config {
	return Config{
		Addr:            "127.0.0.1:0",
		Token:           "test-secret-token",
		ShutdownTimeout: 2 * time.Second,
	}
}

func TestHealthEndpoint_Success(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, nil)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set(HeaderEngineToken, cfg.Token)
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to parse JSON response: %v", err)
	}

	if body["alive"] != true {
		t.Errorf("expected alive=true, got %v", body["alive"])
	}
	if body["status"] != "ok" {
		t.Errorf("expected status=ok, got %v", body["status"])
	}
}

func TestAuth_MissingToken(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, nil)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected status 401, got %d", rec.Code)
	}

	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to parse JSON response: %v", err)
	}

	errMap, ok := body["error"].(map[string]any)
	if !ok || errMap["code"] != "UNAUTHORIZED" {
		t.Errorf("expected error.code=UNAUTHORIZED, got %v", body)
	}
}

func TestAuth_InvalidToken(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, nil)

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set(HeaderEngineToken, "wrong-token")
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected status 401, got %d", rec.Code)
	}
}

func TestStatusEndpoint(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, mockTracker{count: 7})

	req := httptest.NewRequest(http.MethodGet, "/status", nil)
	req.Header.Set(HeaderEngineToken, cfg.Token)
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to parse JSON response: %v", err)
	}

	if body["status"] != "running" {
		t.Errorf("expected status=running, got %v", body["status"])
	}
	if val, ok := body["activeSessions"].(float64); !ok || int(val) != 7 {
		t.Errorf("expected activeSessions=7, got %v", body["activeSessions"])
	}
	if _, ok := body["uptimeSeconds"].(float64); !ok {
		t.Errorf("expected uptimeSeconds as float, got %v", body["uptimeSeconds"])
	}
	if _, ok := body["startedAt"].(string); !ok {
		t.Errorf("expected startedAt as ISO timestamp string, got %v", body["startedAt"])
	}
}

func TestShutdownEndpoint(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, nil)

	req := httptest.NewRequest(http.MethodPost, "/shutdown", nil)
	req.Header.Set(HeaderEngineToken, cfg.Token)
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}

	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("failed to parse JSON response: %v", err)
	}

	if body["status"] != "shutting_down" {
		t.Errorf("expected status=shutting_down, got %v", body["status"])
	}

	// Verify shutdownReq channel was closed
	select {
	case <-srv.shutdownReq:
		// success
	case <-time.After(100 * time.Millisecond):
		t.Fatal("shutdownReq channel was not closed")
	}
}

func TestMethodNotAllowed(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, nil)

	// POST /health should be rejected as method not allowed
	req := httptest.NewRequest(http.MethodPost, "/health", nil)
	req.Header.Set(HeaderEngineToken, cfg.Token)
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected status 405 Method Not Allowed, got %d", rec.Code)
	}
}

func TestNotFound(t *testing.T) {
	cfg := testConfig()
	srv := NewServer(cfg, nil)

	req := httptest.NewRequest(http.MethodGet, "/unknown-endpoint", nil)
	req.Header.Set(HeaderEngineToken, cfg.Token)
	rec := httptest.NewRecorder()

	srv.Handler().ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected status 404, got %d", rec.Code)
	}
}

func TestLoadConfig_Defaults(t *testing.T) {
	os.Unsetenv("ENGINE_HOST")
	os.Unsetenv("ENGINE_PORT")
	os.Unsetenv("ENGINE_ADDR")
	os.Unsetenv("ENGINE_AUTH_TOKEN")
	os.Unsetenv("ENGINE_TOKEN")
	os.Unsetenv("ENGINE_MAX_SESSIONS")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Host != "127.0.0.1" {
		t.Errorf("expected host 127.0.0.1, got %s", cfg.Host)
	}
	if cfg.Port != 8081 {
		t.Errorf("expected port 8081, got %d", cfg.Port)
	}
	if cfg.Addr != "127.0.0.1:8081" {
		t.Errorf("expected addr 127.0.0.1:8081, got %s", cfg.Addr)
	}
	if cfg.Token != "dev-secret-engine-token-change-in-prod" {
		t.Errorf("expected default dev token, got %s", cfg.Token)
	}
	if cfg.MaxSessions != 50 {
		t.Errorf("expected default maxSessions 50, got %d", cfg.MaxSessions)
	}
}

func TestLoadConfig_EnvOverrides(t *testing.T) {
	t.Setenv("ENGINE_HOST", "0.0.0.0")
	t.Setenv("ENGINE_PORT", "9099")
	t.Setenv("ENGINE_AUTH_TOKEN", "custom-token-xyz")
	t.Setenv("ENGINE_MAX_SESSIONS", "25")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Host != "0.0.0.0" {
		t.Errorf("expected host 0.0.0.0, got %s", cfg.Host)
	}
	if cfg.Port != 9099 {
		t.Errorf("expected port 9099, got %d", cfg.Port)
	}
	if cfg.Addr != "0.0.0.0:9099" {
		t.Errorf("expected addr 0.0.0.0:9099, got %s", cfg.Addr)
	}
	if cfg.Token != "custom-token-xyz" {
		t.Errorf("expected token custom-token-xyz, got %s", cfg.Token)
	}
	if cfg.MaxSessions != 25 {
		t.Errorf("expected maxSessions 25, got %d", cfg.MaxSessions)
	}
}

func TestServer_RunAndShutdownLifecycle(t *testing.T) {
	cfg := Config{
		Addr:            "127.0.0.1:0", // ephemeral port
		Token:           "lifecycle-token",
		ShutdownTimeout: 2 * time.Second,
	}

	srv := NewServer(cfg, mockTracker{count: 3})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	runErrCh := make(chan error, 1)
	go func() {
		runErrCh <- srv.Run(ctx)
	}()

	// Wait for listener to be ready without busy-polling
	select {
	case <-srv.Ready():
		// server is listening
	case <-time.After(2 * time.Second):
		t.Fatal("server did not become ready within timeout")
	}

	addr := srv.Addr()

	// 1. Check health
	client := &http.Client{Timeout: 1 * time.Second}
	req, err := http.NewRequest(http.MethodGet, fmt.Sprintf("http://%s/health", addr), nil)
	if err != nil {
		t.Fatalf("failed to create request: %v", err)
	}
	req.Header.Set(HeaderEngineToken, cfg.Token)

	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("health check failed: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected status 200, got %d", resp.StatusCode)
	}

	// 2. Trigger shutdown via POST /shutdown
	shutReq, err := http.NewRequest(http.MethodPost, fmt.Sprintf("http://%s/shutdown", addr), nil)
	if err != nil {
		t.Fatalf("failed to create shutdown request: %v", err)
	}
	shutReq.Header.Set(HeaderEngineToken, cfg.Token)

	shutResp, err := client.Do(shutReq)
	if err != nil {
		t.Fatalf("shutdown request failed: %v", err)
	}
	defer shutResp.Body.Close()
	if shutResp.StatusCode != http.StatusOK {
		t.Fatalf("expected status 200 for shutdown, got %d", shutResp.StatusCode)
	}

	// 3. Verify srv.Run exits cleanly
	select {
	case err := <-runErrCh:
		if err != nil {
			t.Fatalf("srv.Run returned error: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down within timeout")
	}
}
