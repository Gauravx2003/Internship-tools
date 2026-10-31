package com.motadata.websession.modules.engine.channel;

import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;

/**
 * EngineSessionChannel represents an active two-way communication channel between Vert.x
 * and the Go Session Engine for a specific session.
 */
public interface EngineSessionChannel
{

    /**
     * The unique session identifier associated with this channel.
     */
    String getSessionId();

    /**
     * Sends a protocol envelope message to the Go engine.
     */
    Future<Void> send(EngineEnvelope envelope);

    /**
     * Constructs and sends an envelope with the given message type and payload.
     * The sequence number is automatically incremented.
     */
    Future<Void> send(String type, JsonObject payload);

    /**
     * Closes the session channel.
     */
    Future<Void> close();

    /**
     * Returns true if the underlying WebSocket connection is open and active.
     */
    boolean isConnected();

    /**
     * Registers a callback invoked when the underlying channel closes.
     */
    void onClose(Handler<Void> closeHandler);

    /**
     * Registers a callback invoked when an error occurs on the channel.
     */
    void onError(Handler<Throwable> errorHandler);
}
