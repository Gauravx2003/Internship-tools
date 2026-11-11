package control

import (
	"crypto/subtle"
	"net/http"
	"strings"
)

// HeaderEngineToken is the HTTP header used by Vert.x to authenticate with the Go session engine.
const HeaderEngineToken = "X-Engine-Token"

// requireToken is middleware that guards endpoints against unauthorized callers.
// It verifies the X-Engine-Token header, ?token= query parameter, or Authorization Bearer token
// using constant-time comparison to prevent timing attacks.
func requireToken(token string, next http.Handler) http.Handler {
	want := []byte(token)

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tokenStr := r.Header.Get(HeaderEngineToken)
		if tokenStr == "" {
			tokenStr = r.URL.Query().Get("token")
		}
		if tokenStr == "" && strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
			tokenStr = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		}

		got := []byte(tokenStr)
		if subtle.ConstantTimeCompare(want, got) != 1 {
			WriteJSON(w, http.StatusUnauthorized, map[string]any{
				"error": map[string]any{
					"code":    "UNAUTHORIZED",
					"message": "Missing or invalid engine token",
				},
			})
			return
		}

		next.ServeHTTP(w, r)
	})
}
