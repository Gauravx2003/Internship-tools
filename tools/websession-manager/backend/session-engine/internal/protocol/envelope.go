package protocol

import "time"

/**
 * EngineEnvelope represents the standard protocol envelope exchanged between Vert.x
 * and the Go Session Engine over the internal WebSocket channel.
 *
 * Example JSON:
 * {
 *   "v": 1,
 *   "type": "open",
 *   "session.id": "550e8400-e29b-41d4-a716-446655440000",
 *   "seq": 1,
 *   "ts": "2026-10-25T11:00:00Z",
 *   "payload": { ... }
 * }
 */

type RequestStructure struct {
	Type      string    `json:"type"`
	SessionId string    `json:"sessionId"`
	Payload   ReqConfig `json:"payload"`
}

type ReqConfig struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Command  string `json:"command"`
}

type ResponseStructure struct {
	V         int        `json:"v"`
	Type      string     `json:"type"`
	SessionId string     `json:"sessionId"`
	Seq       int64      `json:"seq"`
	Ts        time.Time  `json:"ts"`
	Payload   ResPayload `json:"payload"`
}

type ResPayload struct {
	Message string `json:"message"`
	State   string `json:"state"`
}
