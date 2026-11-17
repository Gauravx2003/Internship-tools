package com.motadata.websession.modules.engine.channel;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.engine.model.EngineEnvelope;
import com.motadata.websession.modules.engine.stdio.GoProcessInstance;
import com.motadata.websession.modules.engine.stdio.StdioProcessPool;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standard I/O Pipe implementation of EngineChannel connecting Vert.x to Go Session Engine processes.
 * Multiplexes up to N sessions per process (default N=5) and routes dynamically.
 */
public class StdioPipeEngineChannel implements EngineChannel
{

    private final Vertx vertx;

    private final AppConfig config;

    private final StdioProcessPool processPool;

    private final Map<String, EngineSessionChannel> activeChannels = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public StdioPipeEngineChannel(Vertx vertx, AppConfig config)
    {
        this.vertx = vertx;
        this.config = config;
        this.processPool = new StdioProcessPool(vertx, config);
    }

    public StdioPipeEngineChannel(Vertx vertx, AppConfig config, StdioProcessPool pool)
    {
        this.vertx = vertx;
        this.config = config;
        this.processPool = pool;
    }

    @Override
    public Future<EngineSessionChannel> openSession(String sessionId, Handler<EngineEnvelope> messageHandler)
    {
        if (closed.get())
        {
            return Future.failedFuture(new IllegalStateException("StdioPipeEngineChannel is closed"));
        }

        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.failedFuture(new IllegalArgumentException("sessionId cannot be null or empty"));
        }

        String cleanSessionId = sessionId.trim();

        // Check if channel already exists and is active
        EngineSessionChannel existing = activeChannels.get(cleanSessionId);
        if (existing != null && existing.isConnected())
        {
            return Future.succeededFuture(existing);
        }

        return processPool.acquireProcessForSession(cleanSessionId, messageHandler)
            .map(proc ->
            {
                StdioPipeSessionChannel channel = new StdioPipeSessionChannel(cleanSessionId, proc, processPool);

                channel.onClose(v ->
                {
                    activeChannels.remove(cleanSessionId);
                    processPool.releaseSession(cleanSessionId);
                });

                activeChannels.put(cleanSessionId, channel);
                return (EngineSessionChannel) channel;
            });
    }

    @Override
    public Future<Void> closeSession(String sessionId)
    {
        if (sessionId == null)
        {
            return Future.succeededFuture();
        }

        String cleanSessionId = sessionId.trim();
        EngineSessionChannel channel = activeChannels.remove(cleanSessionId);
        processPool.releaseSession(cleanSessionId);

        if (channel != null)
        {
            return channel.close();
        }

        return Future.succeededFuture();
    }

    @Override
    public boolean hasActiveSession(String sessionId)
    {
        if (sessionId == null)
        {
            return false;
        }

        EngineSessionChannel channel = activeChannels.get(sessionId.trim());
        return channel != null && channel.isConnected();
    }

    @Override
    public EngineSessionChannel getSessionChannel(String sessionId)
    {
        if (sessionId == null)
        {
            return null;
        }

        return activeChannels.get(sessionId.trim());
    }

    @Override
    public Future<Void> close()
    {
        if (closed.compareAndSet(false, true))
        {
            for (EngineSessionChannel channel : activeChannels.values())
            {
                channel.close();
            }
            activeChannels.clear();
            return processPool.close();
        }

        return Future.succeededFuture();
    }

    public StdioProcessPool getProcessPool()
    {
        return processPool;
    }

    public JsonObject getStatusJson()
    {
        return processPool.getStatusJson();
    }

    /**
     * StdioPipeSessionChannel represents an active session communicating over a Go process's stdio pipes.
     */
    public static class StdioPipeSessionChannel implements EngineSessionChannel
    {

        private final String sessionId;

        private final GoProcessInstance processInstance;

        private final StdioProcessPool pool;

        private final AtomicLong seqCounter = new AtomicLong(0);

        private final AtomicBoolean isClosed = new AtomicBoolean(false);

        private Handler<Void> customCloseHandler;

        private Handler<Throwable> customErrorHandler;

        public StdioPipeSessionChannel(String sessionId, GoProcessInstance processInstance, StdioProcessPool pool)
        {
            this.sessionId = sessionId;
            this.processInstance = processInstance;
            this.pool = pool;

            processInstance.onSessionClose(sessionId, v ->
            {
                if (isClosed.compareAndSet(false, true))
                {
                    pool.releaseSession(sessionId);
                    if (customCloseHandler != null)
                    {
                        customCloseHandler.handle(v);
                    }
                }
            });

            processInstance.onSessionError(sessionId, err ->
            {
                if (customErrorHandler != null)
                {
                    customErrorHandler.handle(err);
                }
            });
        }

        @Override
        public String getSessionId()
        {
            return sessionId;
        }

        @Override
        public Future<Void> send(EngineEnvelope envelope)
        {
            if (isClosed.get() || !processInstance.isAlive())
            {
                return Future.failedFuture(new IllegalStateException("Session channel is closed"));
            }

            if (envelope == null)
            {
                return Future.failedFuture(new IllegalArgumentException("Envelope cannot be null"));
            }

            if (envelope.getSeq() <= 0)
            {
                envelope.setSeq(seqCounter.incrementAndGet());
            }

            if (envelope.getSessionId() == null || envelope.getSessionId().isEmpty())
            {
                envelope.setSessionId(sessionId);
            }

            return processInstance.writeEnvelope(envelope);
        }

        @Override
        public Future<Void> send(String type, JsonObject payload)
        {
            EngineEnvelope envelope = EngineEnvelope.create(
                type,
                sessionId,
                seqCounter.incrementAndGet(),
                payload != null ? payload : new JsonObject()
            );

            return send(envelope);
        }

        @Override
        public Future<Void> close()
        {
            if (isClosed.compareAndSet(false, true))
            {
                // Send explicit close envelope to the Go engine
                EngineEnvelope closeEnv = EngineEnvelope.create(
                    "close",
                    sessionId,
                    seqCounter.incrementAndGet(),
                    new JsonObject().put("reason", "closed_by_client")
                );

                processInstance.writeEnvelope(closeEnv);
                processInstance.removeSession(sessionId);
                pool.releaseSession(sessionId);

                if (customCloseHandler != null)
                {
                    customCloseHandler.handle(null);
                }
            }

            return Future.succeededFuture();
        }

        @Override
        public boolean isConnected()
        {
            return !isClosed.get() && processInstance.isAlive();
        }

        @Override
        public void onClose(Handler<Void> closeHandler)
        {
            this.customCloseHandler = closeHandler;
        }

        @Override
        public void onError(Handler<Throwable> errorHandler)
        {
            this.customErrorHandler = errorHandler;
        }

        public GoProcessInstance getProcessInstance()
        {
            return processInstance;
        }
    }
}
