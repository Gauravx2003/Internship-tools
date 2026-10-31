package com.motadata.websession.modules.engine.channel;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket implementation of EngineChannel connecting Vert.x to the Go Session Engine.
 */
public class WebSocketEngineChannel implements EngineChannel
{

    private final Vertx vertx;

    private final AppConfig config;

    private final WebSocketClient webSocketClient;

    private final Map<String, EngineSessionChannel> activeChannels = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public WebSocketEngineChannel(Vertx vertx, AppConfig config)
    {
        this.vertx = vertx;
        this.config = config;
        this.webSocketClient = vertx.createWebSocketClient();
    }

    @Override
    public Future<EngineSessionChannel> openSession(String sessionId, Handler<EngineEnvelope> messageHandler)
    {
        if (closed.get())
        {
            return Future.failedFuture(new IllegalStateException("EngineChannel is closed"));
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

        String host = config.getEngineHost();

        int port = config.getEnginePort();

        String token = config.getEngineAuthToken();

        String uri = "/session";

        if (token != null && !token.trim().isEmpty())
        {
            uri += "?token=" + URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
        }

        WebSocketConnectOptions options = new WebSocketConnectOptions()
            .setHost(host)
            .setPort(port)
            .setURI(uri);

        if (token != null && !token.trim().isEmpty())
        {
            options.addHeader("X-Engine-Token", token.trim());

        }

        return webSocketClient.connect(options)
            .map(ws ->
            {
                WebSocketSessionChannel channel = new WebSocketSessionChannel(cleanSessionId, ws, messageHandler);

                channel.onClose(v -> activeChannels.remove(cleanSessionId));

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

        EngineSessionChannel channel = activeChannels.remove(sessionId.trim());

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

            return webSocketClient.close();
        }

        return Future.succeededFuture();
    }

    /**
     * Inner implementation of EngineSessionChannel over a Vert.x WebSocket.
     */
    private static class WebSocketSessionChannel implements EngineSessionChannel
    {

        private final String sessionId;

        private final WebSocket ws;

        private final AtomicLong seqCounter = new AtomicLong(0);

        private Handler<Void> customCloseHandler;

        private Handler<Throwable> customErrorHandler;

        public WebSocketSessionChannel(String sessionId, WebSocket ws, Handler<EngineEnvelope> messageHandler)
        {
            this.sessionId = sessionId;
            this.ws = ws;

            ws.textMessageHandler(text ->
            {
                try
                {
                  //System.out.println("Received text message: " + text);

                    JsonObject json = new JsonObject(text);

                    EngineEnvelope envelope = EngineEnvelope.fromJson(json);

                  //System.out.println("Envelope receieved: " + envelope);

                    if (envelope != null && messageHandler != null)
                    {
                        messageHandler.handle(envelope);
                    }
                }
                catch (Exception e)
                {
                    // If raw non-JSON text, wrap in generic envelope
                    if (messageHandler != null)
                    {
                        EngineEnvelope fallback = EngineEnvelope.create(
                            "message",
                            sessionId,

                            seqCounter.incrementAndGet(),
                            new JsonObject().put("raw", text)

                        );

                        messageHandler.handle(fallback);
                    }
                }
            });

            ws.closeHandler(v ->
            {
                if (customCloseHandler != null)
                {
                    customCloseHandler.handle(v);
                }
            });

            ws.exceptionHandler(err ->
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

            return ws.writeTextMessage(envelope.encode());
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

            return ws.writeTextMessage(envelope.encode());
        }

        @Override
        public Future<Void> close()
        {
            if (!ws.isClosed())
            {
                return ws.close();
            }

            return Future.succeededFuture();
        }

        @Override
        public boolean isConnected()
        {
            return !ws.isClosed();
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
    }
}
