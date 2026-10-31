package com.motadata.websession.http;

import com.motadata.websession.middleware.CentralErrorHandler;
import com.motadata.websession.middleware.RequestIdMiddleware;
import com.motadata.websession.modules.auth.handler.AuthRouter;
import com.motadata.websession.modules.credentials.handler.CredentialRouter;
import com.motadata.websession.modules.engine.channel.EngineChannel;
import com.motadata.websession.modules.engine.channel.EngineSessionChannel;
import com.motadata.websession.modules.hosts.handler.HostRouter;
import com.motadata.websession.modules.keys.handler.KeyRouter;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

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

        // WebSocket endpoint for Postman testing to trace data flow end-to-end:
        // Postman -> Vert.x -> Go Session Engine -> Vert.x -> Postman
        if (engineChannel != null)
        {
            router.route("/ws/test-engine").handler(ctx ->
            {
                HttpServerRequest req = ctx.request();

                req.toWebSocket()
                    .onSuccess(clientWs ->
                    {
                        String clientAddr = clientWs.remoteAddress() != null ? clientWs.remoteAddress().toString() : "unknown";
                        System.out.println("================================================================================");
                        System.out.println(" [DATA-FLOW] Postman connected WebSocket to Vert.x (/ws/test-engine)");
                        System.out.println(" Remote client: " + clientAddr);
                        System.out.println("================================================================================");

                        AtomicReference<EngineSessionChannel> engineSessionRef = new AtomicReference<>();

                        clientWs.textMessageHandler(text ->
                        {
                            System.out.println("\n[DATA-FLOW: STEP 1] Postman -> Vert.x:");
                            System.out.println("   Received payload from Postman: " + text);

                            try
                            {
                                JsonObject json = new JsonObject(text);
                                String sessionId = json.getString("session.id", json.getString("sessionId", "postman-session-" + System.currentTimeMillis()));
                                String type = json.getString("type", "open");
                                JsonObject payload = json.getJsonObject("payload", new JsonObject());

                                EngineSessionChannel current = engineSessionRef.get();

                                if (current == null || !current.isConnected())
                                {
                                    System.out.println("[DATA-FLOW: STEP 2a] Vert.x EngineChannel -> Connecting to Go Engine (ws://localhost:8081/session)...");

                                    engineChannel.openSession(sessionId, fromEngine ->
                                    {
                                        System.out.println("\n[DATA-FLOW: STEP 3] Go Engine -> Vert.x:");
                                        System.out.println("   Received response from Go Engine: " + fromEngine.encode());

                                        System.out.println("[DATA-FLOW: STEP 4] Vert.x -> Postman:");
                                        System.out.println("   Forwarding response back to Postman: " + fromEngine.encode());

                                        clientWs.writeTextMessage(fromEngine.encode());
                                    })
                                    .onSuccess(newSession ->
                                    {
                                        engineSessionRef.set(newSession);
                                        System.out.println("[DATA-FLOW: STEP 2b] Vert.x -> Go Engine:");
                                        System.out.println("   Sending '" + type + "' envelope over internal WebSocket to Go Engine...");
                                        newSession.send(type, payload);
                                    })
                                    .onFailure(err ->
                                    {
                                        System.err.println("[DATA-FLOW ERROR] Could not connect to Go Engine: " + err.getMessage());
                                        clientWs.writeTextMessage(new JsonObject()
                                            .put("type", "error")
                                            .put("message", "Could not connect to Go Engine: " + err.getMessage())
                                            .encode());
                                    });
                                }
                                else
                                {
                                    System.out.println("[DATA-FLOW: STEP 2b] Vert.x -> Go Engine:");
                                    System.out.println("   Sending '" + type + "' envelope over existing session to Go Engine...");
                                    current.send(type, payload);
                                }
                            }
                            catch (Exception e)
                            {
                                System.err.println("[DATA-FLOW ERROR] Invalid JSON from Postman: " + e.getMessage());
                                clientWs.writeTextMessage(new JsonObject()
                                    .put("type", "error")
                                    .put("message", "Invalid JSON: " + e.getMessage())
                                    .encode());
                            }
                        });

                        clientWs.closeHandler(v ->
                        {
                            System.out.println("[DATA-FLOW] Postman client closed WebSocket connection");
                            EngineSessionChannel ch = engineSessionRef.get();
                            if (ch != null)
                            {
                                ch.close();
                            }
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
}
