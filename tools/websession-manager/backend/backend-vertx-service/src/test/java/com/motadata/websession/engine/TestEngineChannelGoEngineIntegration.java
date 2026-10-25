package com.motadata.websession.engine;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.engine.channel.EngineChannel;
import com.motadata.websession.modules.engine.channel.EngineSessionChannel;
import com.motadata.websession.modules.engine.channel.WebSocketEngineChannel;
import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@ExtendWith(VertxExtension.class)
public class TestEngineChannelGoEngineIntegration
{

    private static final int GO_ENGINE_PORT = 9099;

    private static final String GO_ENGINE_HOST = "127.0.0.1";

    private static final String GO_ENGINE_AUTH_TOKEN = "real-go-engine-secret-token";

    private static Process goEngineProcess;

    @BeforeAll
    static void startGoEngine() throws Exception
    {
        // Path to compiled Go engine binary
        File bin = new File("../session-engine/bin/session-engine");

        if (!bin.exists())
        {
            // Build binary if not already built
            ProcessBuilder pb = new ProcessBuilder("go", "build", "-o", "bin/session-engine", "cmd/engine/main.go");
            pb.directory(new File("../session-engine"));
            pb.inheritIO();
            Process p = pb.start();
            boolean ok = p.waitFor(30, TimeUnit.SECONDS);
            if (!ok || p.exitValue() != 0)
            {
                throw new IllegalStateException("Failed to build Go session-engine binary");
            }
        }

        ProcessBuilder startPb = new ProcessBuilder(bin.getAbsolutePath());
        startPb.directory(new File("../session-engine"));
        startPb.environment().put("ENGINE_HOST", GO_ENGINE_HOST);
        startPb.environment().put("ENGINE_PORT", String.valueOf(GO_ENGINE_PORT));
        startPb.environment().put("ENGINE_AUTH_TOKEN", GO_ENGINE_AUTH_TOKEN);
        startPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        startPb.redirectError(ProcessBuilder.Redirect.INHERIT);

        goEngineProcess = startPb.start();

        // Wait until Go engine /health responds 200 OK
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest healthReq = HttpRequest.newBuilder()
            .uri(URI.create("http://" + GO_ENGINE_HOST + ":" + GO_ENGINE_PORT + "/health"))
            .header("X-Engine-Token", GO_ENGINE_AUTH_TOKEN)
            .timeout(Duration.ofSeconds(1))
            .GET()
            .build();

        boolean ready = false;
        long deadline = System.currentTimeMillis() + 10000;

        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                HttpResponse<String> resp = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200)
                {
                    ready = true;
                    break;
                }
            }
            catch (Exception ignored)
            {
            }
            Thread.sleep(100);
        }

        if (!ready)
        {
            if (goEngineProcess.isAlive())
            {
                goEngineProcess.destroyForcibly();
            }
            throw new IllegalStateException("Timed out waiting for Go session engine to become ready on port " + GO_ENGINE_PORT);
        }
    }

    @AfterAll
    static void stopGoEngine()
    {
        if (goEngineProcess != null && goEngineProcess.isAlive())
        {
            goEngineProcess.destroyForcibly();
        }
    }

    @Test
    void testVertxAndGoEngineWebSocketCommunication(Vertx vertx, VertxTestContext testContext)
    {
        AppConfig config = new AppConfig(
            8888, "localhost", 5432, "websession_db", "websession_app", "",
            30, false, "encryptionKey", GO_ENGINE_HOST, GO_ENGINE_PORT, GO_ENGINE_AUTH_TOKEN
        );

        EngineChannel engineChannel = new WebSocketEngineChannel(vertx, config);

        String testSessionId = "e2e-vertx-go-" + System.currentTimeMillis();

        CopyOnWriteArrayList<EngineEnvelope> receivedEnvelopes = new CopyOnWriteArrayList<>();

        engineChannel.openSession(testSessionId, env ->
        {
            System.out.println("[TEST] Vert.x received envelope from Go Engine: " + env);
            receivedEnvelopes.add(env);
        })
        .compose(sessionChannel ->
        {
            testContext.verify(() ->
            {
                Assertions.assertNotNull(sessionChannel);
                Assertions.assertEquals(testSessionId, sessionChannel.getSessionId());
                Assertions.assertTrue(sessionChannel.isConnected(), "WebSocket connection to Go Engine should be active");
            });

            // 1. Send "open" command envelope from Vert.x to Go Engine
            JsonObject openPayload = new JsonObject()
                .put("host", "127.0.0.1")
                .put("port", 22)
                .put("username", "testuser");

            System.out.println("[TEST] Vert.x sending 'open' command to Go Engine...");
            return sessionChannel.send("open", openPayload);
        })
        .onComplete(testContext.succeeding(v ->
        {
            // Wait briefly for Go Engine to respond with state envelope
            vertx.setTimer(300, id ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertFalse(receivedEnvelopes.isEmpty(), "Vert.x should have received response envelope from Go Engine");

                    EngineEnvelope stateEnv = receivedEnvelopes.get(0);
                    Assertions.assertEquals("state", stateEnv.getType());
                    Assertions.assertEquals(testSessionId, stateEnv.getSessionId());
                    Assertions.assertEquals("connected", stateEnv.getPayload().getString("state"));

                    // 2. Now send "close" command to Go Engine
                    EngineSessionChannel channel = engineChannel.getSessionChannel(testSessionId);
                    Assertions.assertNotNull(channel);

                    channel.send("close", new JsonObject().put("reason", "session_complete"))
                        .onComplete(ar ->
                        {
                            vertx.setTimer(300, id2 ->
                            {
                                testContext.verify(() ->
                                {
                                    // Should have received closed envelope as second message
                                    Assertions.assertTrue(receivedEnvelopes.size() >= 2);
                                    EngineEnvelope closedEnv = receivedEnvelopes.get(1);
                                    Assertions.assertEquals("closed", closedEnv.getType());
                                    Assertions.assertEquals(testSessionId, closedEnv.getSessionId());

                                    // Clean up channel
                                    engineChannel.closeSession(testSessionId)
                                        .compose(v2 -> engineChannel.close())
                                        .onComplete(closeAr -> testContext.completeNow());
                                });
                            });
                        });
                });
            });
        }));
    }
}
