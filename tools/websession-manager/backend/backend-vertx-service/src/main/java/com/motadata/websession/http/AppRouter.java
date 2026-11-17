package com.motadata.websession.http;

import com.motadata.websession.middleware.CentralErrorHandler;
import com.motadata.websession.middleware.RequestIdMiddleware;
import com.motadata.websession.modules.auth.handler.AuthRouter;
import com.motadata.websession.modules.credentials.handler.CredentialRouter;
import com.motadata.websession.modules.engine.channel.EngineChannel;
import com.motadata.websession.modules.engine.channel.EngineSessionChannel;
import com.motadata.websession.modules.engine.channel.StdioPipeEngineChannel;
import com.motadata.websession.modules.hosts.handler.HostRouter;
import com.motadata.websession.modules.keys.handler.KeyRouter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AppRouter
{

    public static Router create(
        Vertx vertx,
        AuthRouter authRouter,
        HostRouter hostRouter,
        CredentialRouter credentialRouter,
        KeyRouter keyRouter)
    {
        return create(vertx, authRouter, hostRouter, credentialRouter, keyRouter, null);
    }

    public static Router create(
        Vertx vertx,
        AuthRouter authRouter,
        HostRouter hostRouter,
        CredentialRouter credentialRouter,
        KeyRouter keyRouter,
        EngineChannel engineChannel)
    {
        Router router = Router.router(vertx);

        router.route().handler(new RequestIdMiddleware());

        router.route().failureHandler(new CentralErrorHandler());

        // Engine Status endpoint to inspect running Go processes and their sessions
        Handler engineStatusHandler = ctx ->
        {
            if (engineChannel instanceof StdioPipeEngineChannel stdioChannel)
            {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(stdioChannel.getStatusJson().encodePrettily());
            }
            else
            {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(new JsonObject()
                        .put("status", "UP")
                        .put("transport", "stdio")
                        .encodePrettily()
                    );
            }
        };

        router.get("/engine/status").handler(engineStatusHandler);
        router.get("/api/v1/engine/status").handler(engineStatusHandler);

        // WebSocket endpoint for Postman / web client testing to trace data flow end-to-end:
        // Client -> Vert.x -> Go Session Engine -> Vert.x -> Client
        if (engineChannel != null)
        {
            router.get("/ws/test-engine").handler(ctx ->
            {
                HttpServerRequest req = ctx.request();
                req.toWebSocket()
                    .onSuccess(clientWs ->
                    {
                        System.out.println("[DATA-FLOW] Client connected WebSocket to Vert.x (/ws/test-engine)");

                        // Track active sessions over this WebSocket connection
                        Map<String, EngineSessionChannel> sessionChannels = new ConcurrentHashMap<>();

                        clientWs.textMessageHandler(text ->
                        {
                            try
                            {
                                JsonObject json = new JsonObject(text);
                                String sessionId = json.getString("session.id", json.getString("sessionId", "postman-session-" + System.currentTimeMillis()));
                                String type = json.getString("type", "open");
                                JsonObject payload = json.getJsonObject("payload", new JsonObject());

                                EngineSessionChannel current = sessionChannels.get(sessionId);

                                if (current == null || !current.isConnected())
                                {
                                    System.out.println("[DATA-FLOW: STEP 2a] Vert.x EngineChannel -> Opening session " + sessionId + " with Go Engine...");

                                    engineChannel.openSession(sessionId, fromEngine ->
                                    {
                                        System.out.println("\n[DATA-FLOW: STEP 3] Go Engine -> Vert.x:");
                                        System.out.println("   Received response from Go Engine: " + fromEngine.encode());

                                        System.out.println("[DATA-FLOW: STEP 4] Vert.x -> Client:");
                                        System.out.println("   Forwarding response back to Client: " + fromEngine.encode());

                                        clientWs.writeTextMessage(fromEngine.encode());

                                        if ("closed".equals(fromEngine.getType()))
                                        {
                                            sessionChannels.remove(sessionId);
                                        }
                                    })
                                    .onSuccess(newSession ->
                                    {
                                        sessionChannels.put(sessionId, newSession);
                                        newSession.onClose(v -> sessionChannels.remove(sessionId));

                                        System.out.println("[DATA-FLOW: STEP 2b] Vert.x -> Go Engine:");
                                        System.out.println("   Sending '" + type + "' envelope to Go Engine for session " + sessionId + "...");
                                        newSession.send(type, payload);
                                    })
                                    .onFailure(err ->
                                    {
                                        System.err.println("[DATA-FLOW ERROR] Could not connect to Go Engine: " + err.getMessage());
                                        clientWs.writeTextMessage(new JsonObject()
                                            .put("type", "error")
                                            .put("sessionId", sessionId)
                                            .put("message", "Could not connect to Go Engine: " + err.getMessage())
                                            .encode());
                                    });
                                }
                                else
                                {
                                    System.out.println("[DATA-FLOW: STEP 2b] Vert.x -> Go Engine:");
                                    System.out.println("   Sending '" + type + "' envelope to Go Engine for session " + sessionId + "...");
                                    current.send(type, payload);
                                }
                            }
                            catch (Exception e)
                            {
                                System.err.println("[DATA-FLOW ERROR] Invalid JSON from Client: " + e.getMessage());
                                clientWs.writeTextMessage(new JsonObject()
                                    .put("type", "error")
                                    .put("message", "Invalid JSON: " + e.getMessage())
                                    .encode());
                            }
                        });

                        clientWs.closeHandler(v ->
                        {
                            System.out.println("[DATA-FLOW] Client closed WebSocket connection");
                            for (EngineSessionChannel ch : sessionChannels.values())
                            {
                                ch.close();
                            }
                            sessionChannels.clear();
                        });
                    })
                    .onFailure(ctx::fail);
            });
        }

        router.route().handler(BodyHandler.create());

        router.get("/health").handler(ctx ->
        {
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("status", "UP")
                    .put("timestamp", Instant.now().toString())
                    .encode()
                );
        });

        Router authSubRouter = authRouter.create(vertx);
        router.route("/api/v1/auth/*").subRouter(authSubRouter);
        router.route("/api/auth/*").subRouter(authSubRouter);

        router.route("/api/v1/hosts/*").subRouter(hostRouter.create(vertx));

        router.route("/api/v1/credentials/*").subRouter(credentialRouter.create(vertx));

        router.route("/api/v1/keys/*").subRouter(keyRouter.create(vertx));

        return router;
    }

    private interface Handler extends io.vertx.core.Handler<io.vertx.ext.web.RoutingContext> {}
}
