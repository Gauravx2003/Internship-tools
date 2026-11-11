package control

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
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

	errObj, ok := body["error"].(map[string]any)
	if !ok || errObj["code"] != "UNAUTHORIZED" {
		t.Errorf("expected error.code UNAUTHORIZED, got %v", body["error"])
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

func TestStatusEndpoint_ReportsSessionCount(t *testing.T) {
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
	if body["activeSessions"] != float64(7) {
		t.Errorf("expected activeSessions=7, got %v", body["activeSessions"])
	}
	if _, ok := body["uptimeSeconds"]; !ok {
		t.Error("expected uptimeSeconds in response")
	}
	if _, ok := body["startedAt"]; !ok {
		t.Error("expected startedAt in response")
	}
}

func TestShutdownEndpoint_TriggersShutdown(t *testing.T) {
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

	select {
	case <-srv.shutdownReq:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("shutdownReq channel was not triggered")
	}
}

func TestLoadConfig_Defaults(t *testing.T) {
	_ = os.Unsetenv("ENGINE_HOST")
	_ = os.Unsetenv("ENGINE_PORT")
	_ = os.Unsetenv("ENGINE_ADDR")
	_ = os.Unsetenv("ENGINE_AUTH_TOKEN")
	_ = os.Unsetenv("ENGINE_TOKEN")
	_ = os.Unsetenv("ENGINE_MAX_SESSIONS")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.Host != "127.0.0.1" {
		t.Errorf("expected default host 127.0.0.1, got %s", cfg.Host)
	}
	if cfg.Port != 8081 {
		t.Errorf("expected default port 8081, got %d", cfg.Port)
	}
	if cfg.Addr != "127.0.0.1:8081" {
		t.Errorf("expected default addr 127.0.0.1:8081, got %s", cfg.Addr)
	}
	if cfg.Token != "dev-secret-engine-token-change-in-prod" {
		t.Errorf("expected default token, got %s", cfg.Token)
	}
	if cfg.MaxSessions != 50 {
		t.Errorf("expected default maxSessions 50, got %d", cfg.MaxSessions)
	}
}

func TestLoadConfig_CustomEnvironment(t *testing.T) {
	_ = os.Setenv("ENGINE_HOST", "0.0.0.0")
	_ = os.Setenv("ENGINE_PORT", "9090")
	_ = os.Setenv("ENGINE_AUTH_TOKEN", "my-custom-token")
	_ = os.Setenv("ENGINE_MAX_SESSIONS", "100")
	defer func() {
		_ = os.Unsetenv("ENGINE_HOST")
		_ = os.Unsetenv("ENGINE_PORT")
		_ = os.Unsetenv("ENGINE_AUTH_TOKEN")
		_ = os.Unsetenv("ENGINE_MAX_SESSIONS")
	}()

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	if cfg.Host != "0.0.0.0" {
		t.Errorf("expected host 0.0.0.0, got %s", cfg.Host)
	}
	if cfg.Port != 9090 {
		t.Errorf("expected port 9090, got %d", cfg.Port)
	}
	if cfg.Token != "my-custom-token" {
		t.Errorf("expected token my-custom-token, got %s", cfg.Token)
	}
	if cfg.MaxSessions != 100 {
		t.Errorf("expected maxSessions 100, got %d", cfg.MaxSessions)
	}
}

func TestServer_Lifecycle_RunAndShutdown(t *testing.T) {
	cfg := Config{
		Addr:            "127.0.0.1:0",
		Token:           "lifecycle-token",
		ShutdownTimeout: 2 * time.Second,
	}

	srv := NewServer(cfg, mockTracker{count: 0})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	runErrCh := make(chan error, 1)
	go func() {
		runErrCh <- srv.Run(ctx)
	}()

	select {
	case <-srv.Ready():
	case <-time.After(3 * time.Second):
		t.Fatal("server did not become ready in time")
	}

	addr := srv.Addr()
	client := &http.Client{Timeout: 2 * time.Second}

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

	select {
	case err := <-runErrCh:
		if err != nil {
			t.Fatalf("srv.Run returned error: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("server did not shut down within timeout")
	}
}

func TestSessionWebSocket_UpgradeAndMockOpen(t *testing.T) {
	cfg := Config{
		Addr:            "127.0.0.1:0",
		Token:           "ws-token-test",
		ShutdownTimeout: 2 * time.Second,
	}

	srv := NewServer(cfg, nil)
	ts := httptest.NewServer(srv.Handler())
	defer ts.Close()

	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/session"

	// 1. Connection without token should fail with 401
	_, resp, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err == nil {
		t.Fatal("expected dial without token to fail")
	}
	if resp != nil && resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("expected status 401, got %d", resp.StatusCode)
	}

	// 2. Connection with token in query param should succeed
	wsURLWithToken := wsURL + "?token=" + cfg.Token
	conn, resp, err := websocket.DefaultDialer.Dial(wsURLWithToken, nil)
	if err != nil {
		t.Fatalf("expected successful websocket upgrade, got: %v", err)
	}
	defer conn.Close()

	if resp.StatusCode != http.StatusSwitchingProtocols {
		t.Errorf("expected 101 Switching Protocols, got %d", resp.StatusCode)
	}

	// 3. Send "open" command envelope
	openEnv := map[string]any{
		"v":         1,
		"type":      "open",
		"sessionId": "test-session-ws",
		"payload": map[string]any{
			"host": "127.0.0.1",
		},
	}
	if err := conn.WriteJSON(openEnv); err != nil {
		t.Fatalf("failed to write open envelope: %v", err)
	}

	// Read state envelope
	var res map[string]any
	if err := conn.ReadJSON(&res); err != nil {
		t.Fatalf("failed to read response: %v", err)
	}

	if res["type"] != "state" {
		t.Fatalf("expected response type 'state', got %v", res["type"])
	}

	payload, _ := res["payload"].(map[string]any)
	if payload["state"] != "connected" {
		t.Fatalf("expected payload state 'connected', got %v", payload["state"])
	}

	// 4. Send "close" command
	closeEnv := map[string]any{
		"type":      "close",
		"sessionId": "test-session-ws",
		"payload": map[string]any{
			"reason": "test_done",
		},
	}
	if err := conn.WriteJSON(closeEnv); err != nil {
		t.Fatalf("failed to write close envelope: %v", err)
	}

	// Read closed envelope
	var closedRes map[string]any
	if err := conn.ReadJSON(&closedRes); err != nil {
		t.Fatalf("failed to read closed response: %v", err)
	}
	if closedRes["type"] != "closed" {
		t.Fatalf("expected response type 'closed', got %v", closedRes["type"])
	}
}
