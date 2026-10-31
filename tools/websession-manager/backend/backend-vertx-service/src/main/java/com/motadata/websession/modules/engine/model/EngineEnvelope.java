package com.motadata.websession.modules.engine.model;

import io.vertx.core.json.JsonObject;

import java.time.Instant;

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
public class EngineEnvelope
{

    public static final int PROTOCOL_VERSION = 1;

    private int version;

    private String type;

    private String sessionId;

    private long seq;

    private String ts;

    private JsonObject payload;

    public EngineEnvelope()
    {
        this.version = PROTOCOL_VERSION;
        this.ts = Instant.now().toString();
        this.payload = new JsonObject();
    }

    public EngineEnvelope(int version, String type, String sessionId, long seq, String ts, JsonObject payload)
    {
        this.version = version;
        this.type = type;
        this.sessionId = sessionId;
        this.seq = seq;
        this.ts = ts != null ? ts : Instant.now().toString();
        this.payload = payload != null ? payload : new JsonObject();
    }

    public static EngineEnvelope create(String type, String sessionId, long seq, JsonObject payload)
    {
        return new EngineEnvelope(
            PROTOCOL_VERSION,
            type,
            sessionId,
            seq,
            Instant.now().toString(),
            payload != null ? payload : new JsonObject()
        );
    }

    public static EngineEnvelope fromJson(JsonObject json)
    {
        if (json == null)
        {
            return null;
        }

        int v = json.getInteger("v", PROTOCOL_VERSION);

        String type = json.getString("type");

        String sessionId = json.getString("session.id", json.getString("sessionId"));

        long seq = json.getLong("seq", 0L);

        String ts = json.getString("ts", Instant.now().toString());



        JsonObject payload = json.getJsonObject("payload");

        if (payload == null)
        {
            payload = new JsonObject();
        }

        return new EngineEnvelope(v, type, sessionId, seq, ts, payload);
    }

    public JsonObject toJson()
    {
        return new JsonObject()
            .put("v", version)
            .put("type", type)
            .put("session.id", sessionId)
            .put("sessionId", sessionId)
            .put("seq", seq)
            .put("ts", ts)
            .put("payload", payload);

    }

    public String encode()
    {
        return toJson().encode();
    }

    public int getVersion()
    {
        return version;
    }

    public void setVersion(int version)
    {
        this.version = version;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public String getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(String sessionId)
    {
        this.sessionId = sessionId;
    }

    public long getSeq()
    {
        return seq;
    }

    public void setSeq(long seq)
    {
        this.seq = seq;
    }

    public String getTs()
    {
        return ts;
    }

    public void setTs(String ts)
    {
        this.ts = ts;
    }

    public JsonObject getPayload()
    {
        return payload;
    }

    public void setPayload(JsonObject payload)
    {
        this.payload = payload;
    }



    @Override
    public String toString()
    {
        return encode();
    }
}
