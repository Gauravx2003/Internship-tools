package com.motadata.websession.modules.engine.channel;

import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;

/**
 * Transport-neutral interface for communication between the Vert.x service
 * and the Go Session Engine.
 *
 * All Vert.x modules (such as terminal-gateway or key-deployer) communicate
 * with the engine exclusively through this interface, shielding upper layers
 * from transport-specific details (WebSocket now, message queue later).
 */
public interface EngineChannel
{

    /**
     * Opens an engine communication channel for the specified session.
     *
     * @param sessionId      The unique session ID.
     * @param messageHandler Handler called whenever an envelope message is received from the engine.
     * @return Future resolving to an active EngineSessionChannel.
     */
    Future<EngineSessionChannel> openSession(String sessionId, Handler<EngineEnvelope> messageHandler);

    /**
     * Closes the session channel associated with the given sessionId.
     */
    Future<Void> closeSession(String sessionId);

    /**
     * Checks if an active session channel is open for the given sessionId.
     */
    boolean hasActiveSession(String sessionId);

    /**
     * Retrieves the active EngineSessionChannel for the given sessionId if present.
     */
    EngineSessionChannel getSessionChannel(String sessionId);

    /**
     * Shuts down all active channels and releases underlying client resources.
     */
    Future<Void> close();
}
