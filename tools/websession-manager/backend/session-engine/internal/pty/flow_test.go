package pty

import (
	"context"
	"testing"
	"time"
)

func TestFlowController_Disabled(t *testing.T) {
	fc := NewFlowController(0) // <= 0 disables flow control
	fc.AddInFlight(100000)

	ctx := context.Background()
	if err := fc.WaitIfFull(ctx); err != nil {
		t.Fatalf("expected nil when disabled, got %v", err)
	}

	fc.Ack(50000)
}

func TestFlowController_BackpressureAndAck(t *testing.T) {
	fc := NewFlowController(100) // max 100 bytes

	ctx := context.Background()

	// Initial wait should not block (inFlight == 0)
	if err := fc.WaitIfFull(ctx); err != nil {
		t.Fatalf("initial WaitIfFull failed: %v", err)
	}

	// Add 50 bytes -> 50 < 100 -> does not block
	fc.AddInFlight(50)
	if fc.InFlight() != 50 {
		t.Fatalf("expected inFlight 50, got %d", fc.InFlight())
	}
	if err := fc.WaitIfFull(ctx); err != nil {
		t.Fatalf("WaitIfFull with 50/100 failed: %v", err)
	}

	// Add 60 bytes -> inFlight == 110 >= 100 -> blocks!
	fc.AddInFlight(60)
	if fc.InFlight() != 110 {
		t.Fatalf("expected inFlight 110, got %d", fc.InFlight())
	}

	unblocked := make(chan struct{})
	go func() {
		err := fc.WaitIfFull(ctx)
		if err != nil {
			t.Errorf("WaitIfFull error: %v", err)
		}
		close(unblocked)
	}()

	// Ensure it is blocked
	select {
	case <-unblocked:
		t.Fatalf("WaitIfFull should have blocked on 110 >= 100")
	case <-time.After(50 * time.Millisecond):
		// Expected
	}

	// Ack 20 bytes -> inFlight == 90 < 100 -> should unblock!
	fc.Ack(20)

	select {
	case <-unblocked:
		// Success! Unblocked after Ack
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("WaitIfFull failed to unblock after Ack")
	}

	if fc.InFlight() != 90 {
		t.Errorf("expected inFlight 90, got %d", fc.InFlight())
	}
}

func TestFlowController_ContextCancelled(t *testing.T) {
	fc := NewFlowController(50)
	fc.AddInFlight(50) // reaches ceiling

	ctx, cancel := context.WithCancel(context.Background())

	errCh := make(chan error, 1)
	go func() {
		errCh <- fc.WaitIfFull(ctx)
	}()

	// Brief wait to ensure it is blocked
	time.Sleep(20 * time.Millisecond)
	cancel() // cancel context

	select {
	case err := <-errCh:
		if err != context.Canceled {
			t.Fatalf("expected context.Canceled, got %v", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("timed out waiting for context cancellation")
	}
}

func TestFlowController_CloseUnblocks(t *testing.T) {
	fc := NewFlowController(50)
	fc.AddInFlight(50)

	errCh := make(chan error, 1)
	go func() {
		errCh <- fc.WaitIfFull(context.Background())
	}()

	time.Sleep(20 * time.Millisecond)
	fc.Close()

	select {
	case err := <-errCh:
		if err != ErrPumpClosed {
			t.Fatalf("expected ErrPumpClosed, got %v", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatalf("timed out waiting for Close to unblock")
	}

	// Subsequent WaitIfFull should return ErrPumpClosed immediately
	if err := fc.WaitIfFull(context.Background()); err != ErrPumpClosed {
		t.Fatalf("expected ErrPumpClosed on closed controller, got %v", err)
	}
}
