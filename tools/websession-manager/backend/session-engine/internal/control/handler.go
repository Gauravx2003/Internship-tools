package control

import (
	"encoding/json"
	"net/http"
	"time"
)

// WriteJSON sends a JSON response with status code and Content-Type header.
func WriteJSON(w http.ResponseWriter, statusCode int, v interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(v)
}

// health handles "GET /health" - answers "are you alive?".
func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"alive":  true,
	})
}

// status handles "GET /status" - answers "how many sessions are running?" + server uptime.
func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	activeCount := 0
	if s.tracker != nil {
		activeCount = s.tracker.ActiveSessionCount()
	}

	WriteJSON(w, http.StatusOK, map[string]any{
		"status":         "running",
		"activeSessions": activeCount,
		"uptimeSeconds":  time.Since(s.startedAt).Seconds(),
		"startedAt":      s.startedAt.UTC().Format(time.RFC3339),
	})
}

// shutdown handles "POST /shutdown" - answers "please shut down".
func (s *Server) shutdown(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, map[string]any{
		"status":  "shutting_down",
		"message": "Shutdown request accepted",
	})
	s.ShutdownReq()
}
