package com.motadata.websession.engine;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.engine.channel.EngineChannel;
import com.motadata.websession.modules.engine.channel.EngineSessionChannel;
import com.motadata.websession.modules.engine.channel.WebSocketEngineChannel;
import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(VertxExtension.class)
public class TestWebSocketEngineChannel
{

    private HttpServer server;

    private int port;

    private final String testToken = "test-secret-token";

    private EngineChannel engineChannel;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext)
    {
        server = vertx.createHttpServer();

        server.requestHandler(req ->
        {
            if (!"/session".equals(req.path()))
            {
                req.response().setStatusCode(404).end();
                return;
            }

            // Check token in header or query
            String headerToken = req.getHeader("X-Engine-Token");
            String query = req.query();
            String queryToken = query != null && query.contains("token=" + testToken) ? testToken : null;

            if (!testToken.equals(headerToken) && !testToken.equals(queryToken))
            {
                req.response().setStatusCode(401).end();
                return;
            }

            req.toWebSocket()
                .onSuccess(ws ->
                {
                    ws.textMessageHandler(text ->
                    {
                        JsonObject json = new JsonObject(text);
                        EngineEnvelope env = EngineEnvelope.fromJson(json);

                        EngineEnvelope resp = EngineEnvelope.create(
                            "state",
                            env.getSessionId(),
                            env.getSeq() + 1,
                            new JsonObject().put("state", "connected").put("echoType", env.getType())
                        );
                        ws.writeTextMessage(resp.encode());
                    });
                })
                .onFailure(err -> req.response().setStatusCode(500).end());
        });

        server.listen(0, "127.0.0.1")
            .onComplete(testContext.succeeding(s ->
            {
                port = s.actualPort();

                AppConfig config = new AppConfig(
                    8888, "localhost", 5432, "websession_db", "websession_app", "",
                    30, false, "key", "127.0.0.1", port, testToken
                );

                engineChannel = new WebSocketEngineChannel(vertx, config);
                testContext.completeNow();
            }));
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext)
    {
        if (engineChannel != null)
        {
            engineChannel.close();
        }
        if (server != null)
        {
            server.close().onComplete(ar -> testContext.completeNow());
        }
        else
        {
            testContext.completeNow();
        }
    }

    @Test
    void testOpenSessionAndExchangeMessages(Vertx vertx, VertxTestContext testContext)
    {
        String sessionId = "sess-100";
        AtomicReference<EngineEnvelope> receivedEnv = new AtomicReference<>();

        engineChannel.openSession(sessionId, env ->
        {
            receivedEnv.set(env);
        })
        .compose(sessionChannel ->
        {
            testContext.verify(() ->
            {
                Assertions.assertNotNull(sessionChannel);
                Assertions.assertEquals(sessionId, sessionChannel.getSessionId());
                Assertions.assertTrue(sessionChannel.isConnected());
                Assertions.assertTrue(engineChannel.hasActiveSession(sessionId));
            });

            // Send an open envelope
            JsonObject openPayload = new JsonObject().put("host", "10.0.0.1").put("port", 22);
            return sessionChannel.send("open", openPayload);
        })
        .onComplete(testContext.succeeding(v ->
        {
            // Give brief moment for reply
            vertx.setTimer(150, id ->
            {
                testContext.verify(() ->
                {
                    EngineEnvelope resp = receivedEnv.get();
                    Assertions.assertNotNull(resp, "Expected response from engine");
                    Assertions.assertEquals("state", resp.getType());
                    Assertions.assertEquals(sessionId, resp.getSessionId());
                    Assertions.assertEquals("connected", resp.getPayload().getString("state"));
                    Assertions.assertEquals("open", resp.getPayload().getString("echoType"));

                    // Close session
                    engineChannel.closeSession(sessionId)
                        .onComplete(ar ->
                        {
                            Assertions.assertFalse(engineChannel.hasActiveSession(sessionId));
                            testContext.completeNow();
                        });
                });
            });
        }));
    }

    @Test
    void testInvalidTokenRejected(Vertx vertx, VertxTestContext testContext)
    {
        AppConfig invalidConfig = new AppConfig(
            8888, "localhost", 5432, "websession_db", "websession_app", "",
            30, false, "key", "127.0.0.1", port, "wrong-token"
        );

        EngineChannel badChannel = new WebSocketEngineChannel(vertx, invalidConfig);

        badChannel.openSession("sess-reject", env -> {})
            .onComplete(testContext.failing(err ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertNotNull(err);
                    testContext.completeNow();
                });
            }));
    }
}
