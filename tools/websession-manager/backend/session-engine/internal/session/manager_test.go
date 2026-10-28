package session

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestManager_StartAndCount(t *testing.T) {
	mgr := NewManager(10)

	if mgr.ActiveSessionCount() != 0 {
		t.Fatalf("expected 0 initial sessions, got %d", mgr.ActiveSessionCount())
	}

	sess, err := mgr.StartSession("sess-1")
	if err != nil {
		t.Fatalf("failed to start session: %v", err)
	}

	if sess.ID() != "sess-1" {
		t.Errorf("expected session ID 'sess-1', got %s", sess.ID())
	}

	if mgr.ActiveSessionCount() != 1 {
		t.Errorf("expected active count 1, got %d", mgr.ActiveSessionCount())
	}

	foundSess, exists := mgr.GetSession("sess-1")
	if !exists || foundSess != sess {
		t.Errorf("failed to retrieve session by ID")
	}
}

func TestManager_EnforceMaxSessions(t *testing.T) {
	limit := 3
	mgr := NewManager(limit)

	for i := 1; i <= limit; i++ {
		_, err := mgr.StartSession(fmt.Sprintf("sess-%d", i))
		if err != nil {
			t.Fatalf("expected session %d to start: %v", i, err)
		}
	}

	if mgr.ActiveSessionCount() != limit {
		t.Fatalf("expected %d active sessions, got %d", limit, mgr.ActiveSessionCount())
	}

	// Starting one more must exceed limit
	_, err := mgr.StartSession("sess-overflow")
	if err == nil {
		t.Fatal("expected error on exceeding session limit, got nil")
	}

	if !errors.Is(err, ErrSessionLimitReached) {
		t.Errorf("expected ErrSessionLimitReached, got %v", err)
	}

	if mgr.ActiveSessionCount() != limit {
		t.Errorf("active session count should remain %d, got %d", limit, mgr.ActiveSessionCount())
	}
}

func TestManager_DuplicateSession(t *testing.T) {
	mgr := NewManager(10)

	_, err := mgr.StartSession("duplicate-id")
	if err != nil {
		t.Fatalf("failed to start first session: %v", err)
	}

	_, err = mgr.StartSession("duplicate-id")
	if err == nil {
		t.Fatal("expected error for duplicate session ID, got nil")
	}

	if !errors.Is(err, ErrSessionAlreadyExists) {
		t.Errorf("expected ErrSessionAlreadyExists, got %v", err)
	}
}

func TestManager_EmptySessionID(t *testing.T) {
	mgr := NewManager(10)

	_, err := mgr.StartSession("")
	if !errors.Is(err, ErrEmptySessionID) {
		t.Errorf("expected ErrEmptySessionID, got %v", err)
	}
}

func TestManager_CloseSession(t *testing.T) {
	mgr := NewManager(10)

	sess, err := mgr.StartSession("sess-to-close")
	if err != nil {
		t.Fatalf("failed to start session: %v", err)
	}

	var cleanupRan atomic.Bool
	sess.AddCleanup(func() {
		cleanupRan.Store(true)
	})

	err = mgr.CloseSession("sess-to-close", "user_disconnect")
	if err != nil {
		t.Fatalf("failed to close session: %v", err)
	}

	if mgr.ActiveSessionCount() != 0 {
		t.Errorf("expected 0 active sessions, got %d", mgr.ActiveSessionCount())
	}

	if !sess.IsClosed() {
		t.Error("session context should be closed")
	}

	if sess.CloseReason() != "user_disconnect" {
		t.Errorf("expected reason 'user_disconnect', got %q", sess.CloseReason())
	}

	if !cleanupRan.Load() {
		t.Error("expected cleanup callback to have executed")
	}

	// Closing again should return ErrSessionNotFound
	err = mgr.CloseSession("sess-to-close", "user_disconnect")
	if !errors.Is(err, ErrSessionNotFound) {
		t.Errorf("expected ErrSessionNotFound on closing nonexistent session, got %v", err)
	}
}

func TestManager_CloseAll(t *testing.T) {
	mgr := NewManager(10)

	var cleanupCount atomic.Int32
	for i := 1; i <= 5; i++ {
		sess, err := mgr.StartSession(fmt.Sprintf("sess-%d", i))
		if err != nil {
			t.Fatalf("failed to start session %d: %v", i, err)
		}
		sess.AddCleanup(func() {
			cleanupCount.Add(1)
		})
	}

	if mgr.ActiveSessionCount() != 5 {
		t.Fatalf("expected 5 active sessions, got %d", mgr.ActiveSessionCount())
	}

	// Close all neatly
	err := mgr.CloseAll("engine_shutdown")
	if err != nil {
		t.Fatalf("CloseAll returned error: %v", err)
	}

	if mgr.ActiveSessionCount() != 0 {
		t.Errorf("expected 0 active sessions after CloseAll, got %d", mgr.ActiveSessionCount())
	}

	if !mgr.IsClosed() {
		t.Error("manager should report IsClosed=true")
	}

	if cleanupCount.Load() != 5 {
		t.Errorf("expected 5 cleanup hooks to run, got %d", cleanupCount.Load())
	}

	// Attempting to start new session after CloseAll must fail
	_, err = mgr.StartSession("new-session-after-close")
	if !errors.Is(err, ErrManagerClosed) {
		t.Errorf("expected ErrManagerClosed, got %v", err)
	}
}

func TestManager_ListSessionIDs(t *testing.T) {
	mgr := NewManager(10)

	_, _ = mgr.StartSession("sess-a")
	_, _ = mgr.StartSession("sess-b")
	_, _ = mgr.StartSession("sess-c")

	ids := mgr.ListSessionIDs()
	if len(ids) != 3 {
		t.Fatalf("expected 3 IDs, got %d", len(ids))
	}

	idMap := make(map[string]bool)
	for _, id := range ids {
		idMap[id] = true
	}

	for _, expected := range []string{"sess-a", "sess-b", "sess-c"} {
		if !idMap[expected] {
			t.Errorf("missing session ID %s in ListSessionIDs", expected)
		}
	}
}

func TestManager_Concurrency(t *testing.T) {
	mgr := NewManager(100)
	var wg sync.WaitGroup

	numGoroutines := 20
	sessionsPerGoroutine := 5

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func(gID int) {
			defer wg.Done()
			for s := 0; s < sessionsPerGoroutine; s++ {
				sessID := fmt.Sprintf("g%d-s%d", gID, s)
				sess, err := mgr.StartSession(sessID)
				if err != nil {
					t.Errorf("goroutine %d failed to start %s: %v", gID, sessID, err)
					return
				}

				// Verify presence
				if _, ok := mgr.GetSession(sessID); !ok {
					t.Errorf("session %s not found immediately after start", sessID)
				}

				_ = mgr.ActiveSessionCount()
				time.Sleep(1 * time.Millisecond)

				// Close session
				if err := mgr.CloseSession(sessID, "test_done"); err != nil {
					t.Errorf("goroutine %d failed to close %s: %v", gID, sessID, err)
				}

				if !sess.IsClosed() {
					t.Errorf("session %s was not marked closed", sessID)
				}
			}
		}(g)
	}

	wg.Wait()

	if mgr.ActiveSessionCount() != 0 {
		t.Errorf("expected 0 active sessions after all concurrent operations, got %d", mgr.ActiveSessionCount())
	}
}

func TestManager_ParentContextCancellation(t *testing.T) {
	parentCtx, cancel := context.WithCancel(context.Background())
	mgr := NewManagerWithContext(parentCtx, 10)

	sess, err := mgr.StartSession("sess-parent-cancel")
	if err != nil {
		t.Fatalf("failed to start session: %v", err)
	}

	// Cancel parent context
	cancel()

	select {
	case <-sess.Done():
		// Session context should have been canceled
	case <-time.After(500 * time.Millisecond):
		t.Fatal("session context did not cancel when parent context was canceled")
	}
}
