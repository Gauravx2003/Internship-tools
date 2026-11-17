package com.motadata.websession.engine;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.engine.channel.StdioPipeEngineChannel;
import com.motadata.websession.modules.engine.stdio.GoProcessInstance;
import com.motadata.websession.modules.engine.stdio.StdioProcessPool;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ExtendWith(VertxExtension.class)
public class TestStdioProcessPoolAndEngineChannel
{

    private static String binaryPath;

    @BeforeAll
    static void ensureBinaryExists() throws Exception
    {
        File bin = new File("../session-engine/bin/session-engine");
        if (!bin.exists())
        {
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
        binaryPath = bin.getAbsolutePath();
    }

    @Test
    void testSingleSessionOpenAndPingPong(Vertx vertx, VertxTestContext testContext)
    {
        AppConfig config = new AppConfig(
            8888, "localhost", 5432, "test", "test", "test",
            15, 7, false, "key", 5, binaryPath
        );

        StdioPipeEngineChannel channel = new StdioPipeEngineChannel(vertx, config);
        String sessionId = "stdio-test-single-" + System.currentTimeMillis();

        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch pongLatch = new CountDownLatch(1);

        channel.openSession(sessionId, env ->
        {
            if ("state".equals(env.getType()))
            {
                JsonObject payload = env.getPayload();
                if ("connected".equals(payload.getString("state")))
                {
                    connectedLatch.countDown();
                }
            }
            else if ("pong".equals(env.getType()))
            {
                pongLatch.countDown();
            }
        }).onComplete(testContext.succeeding(sessionCh ->
        {
            testContext.verify(() -> Assertions.assertNotNull(sessionCh));

            // Send 'open' payload with mock localhost host
            sessionCh.send("open", new JsonObject().put("host", "localhost"))
                .onComplete(testContext.succeeding(v ->
                {
                    vertx.executeBlocking(() ->
                    {
                        boolean connected = connectedLatch.await(5, TimeUnit.SECONDS);
                        Assertions.assertTrue(connected, "Expected 'connected' state from Go stdio engine");

                        // Now send ping
                        sessionCh.send("ping", new JsonObject()).toCompletionStage().toCompletableFuture().get();

                        boolean pong = pongLatch.await(5, TimeUnit.SECONDS);
                        Assertions.assertTrue(pong, "Expected 'pong' envelope from Go stdio engine");

                        // Close session
                        sessionCh.close().toCompletionStage().toCompletableFuture().get();

                        channel.close().toCompletionStage().toCompletableFuture().get();
                        return null;
                    }).onComplete(testContext.succeeding(res -> testContext.completeNow()));
                }));
        }));
    }

    @Test
    void testMultiplexingAndDynamicProcessAllocation(Vertx vertx, VertxTestContext testContext)
    {
        // Set max sessions per process = 3 to test the rollover threshold quickly
        int capN = 3;
        AppConfig config = new AppConfig(
            8888, "localhost", 5432, "test", "test", "test",
            15, 7, false, "key", capN, binaryPath
        );

        StdioProcessPool pool = new StdioProcessPool(vertx, config);
        StdioPipeEngineChannel channel = new StdioPipeEngineChannel(vertx, config, pool);

        // Open 3 sessions (sess-1, sess-2, sess-3)
        // All 3 should be placed on the SAME Go process (Process 1)
        channel.openSession("sess-1", env -> {})
            .compose(s1 -> channel.openSession("sess-2", env -> {}))
            .compose(s2 -> channel.openSession("sess-3", env -> {}))
            .onComplete(testContext.succeeding(s3 ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(1, pool.getActiveProcessCount(), "Should have exactly 1 Go process running");
                    Assertions.assertEquals(3, pool.getTotalActiveSessionCount(), "Total sessions should be 3");

                    GoProcessInstance proc1 = pool.getProcessForSession("sess-1");
                    GoProcessInstance proc2 = pool.getProcessForSession("sess-2");
                    GoProcessInstance proc3 = pool.getProcessForSession("sess-3");

                    Assertions.assertNotNull(proc1);
                    Assertions.assertEquals(proc1.getPid(), proc2.getPid(), "sess-1 and sess-2 should share process");
                    Assertions.assertEquals(proc1.getPid(), proc3.getPid(), "sess-1 and sess-3 should share process");
                    Assertions.assertEquals(3, proc1.getActiveSessionCount());
                    Assertions.assertFalse(proc1.hasCapacity(), "Process 1 should now be at full capacity (3/3)");
                });

                // Open 4th session (sess-4) -> Process 1 is full, MUST dynamically spawn Process 2
                channel.openSession("sess-4", env -> {})
                    .onComplete(testContext.succeeding(s4 ->
                    {
                        testContext.verify(() ->
                        {
                            Assertions.assertEquals(2, pool.getActiveProcessCount(), "Should have spawned 2nd Go process");
                            Assertions.assertEquals(4, pool.getTotalActiveSessionCount(), "Total sessions should be 4");

                            GoProcessInstance proc1 = pool.getProcessForSession("sess-1");
                            GoProcessInstance proc4 = pool.getProcessForSession("sess-4");

                            Assertions.assertNotNull(proc4);
                            Assertions.assertNotEquals(proc1.getPid(), proc4.getPid(), "sess-4 must be on a NEW process");
                            Assertions.assertEquals(1, proc4.getActiveSessionCount());
                            Assertions.assertTrue(proc4.hasCapacity());
                        });

                        // Release sess-2 from Process 1 -> Process 1 now has capacity (2/3)
                        channel.closeSession("sess-2")
                            .onComplete(testContext.succeeding(v ->
                            {
                                testContext.verify(() ->
                                {
                                    GoProcessInstance proc1 = pool.getProcessForSession("sess-1");
                                    Assertions.assertEquals(2, proc1.getActiveSessionCount(), "Process 1 should now have 2 sessions");
                                    Assertions.assertTrue(proc1.hasCapacity(), "Process 1 should now have spare capacity");
                                    Assertions.assertNull(pool.getProcessForSession("sess-2"));
                                });

                                // Open sess-5 -> Should reuse Process 1 because it now has capacity!
                                channel.openSession("sess-5", env -> {})
                                    .onComplete(testContext.succeeding(s5 ->
                                    {
                                        testContext.verify(() ->
                                        {
                                            Assertions.assertEquals(2, pool.getActiveProcessCount(), "Should still be 2 processes (no new spawn)");
                                            Assertions.assertEquals(4, pool.getTotalActiveSessionCount());

                                            GoProcessInstance proc1 = pool.getProcessForSession("sess-1");
                                            GoProcessInstance proc5 = pool.getProcessForSession("sess-5");

                                            Assertions.assertNotNull(proc5);
                                            Assertions.assertEquals(proc1.getPid(), proc5.getPid(), "sess-5 must route to Process 1 which had available slot!");
                                        });

                                        channel.close().onComplete(testContext.succeeding(done -> testContext.completeNow()));
                                    }));
                            }));
                    }));
            }));
    }

    @Test
    void testProcessCrashResilience(Vertx vertx, VertxTestContext testContext)
    {
        AppConfig config = new AppConfig(
            8888, "localhost", 5432, "test", "test", "test",
            15, 7, false, "key", 5, binaryPath
        );

        StdioProcessPool pool = new StdioProcessPool(vertx, config);
        StdioPipeEngineChannel channel = new StdioPipeEngineChannel(vertx, config, pool);

        String sess1 = "crash-sess-1";

        channel.openSession(sess1, env -> {})
            .onComplete(testContext.succeeding(session1 ->
            {
                GoProcessInstance proc1 = pool.getProcessForSession(sess1);
                Assertions.assertNotNull(proc1);

                session1.onClose(v ->
                {
                    // Process terminated -> verify opening new session creates new process
                    String sess2 = "crash-sess-2";
                    channel.openSession(sess2, env -> {})
                        .onComplete(testContext.succeeding(session2 ->
                        {
                            testContext.verify(() ->
                            {
                                GoProcessInstance proc2 = pool.getProcessForSession(sess2);
                                Assertions.assertNotNull(proc2);
                                Assertions.assertTrue(proc2.isAlive());
                                Assertions.assertNotEquals(proc1.getPid(), proc2.getPid(), "New process PID should differ from crashed process PID");
                            });
                            channel.close().onComplete(testContext.succeeding(done -> testContext.completeNow()));
                        }));
                });

                // Force kill the Go process to simulate unexpected crash
                proc1.destroy();
            }));
    }

    @Test
    void testIdleReaper(Vertx vertx, VertxTestContext testContext)
    {
        AppConfig config = new AppConfig(
            8888, "localhost", 5432, "test", "test", "test",
            15, 7, false, "key", 5, binaryPath
        );

        // Set idle timeout to 150ms
        StdioProcessPool pool = new StdioProcessPool(vertx, config, 150L);
        StdioPipeEngineChannel channel = new StdioPipeEngineChannel(vertx, config, pool);

        channel.openSession("idle-sess-1", env -> {})
            .compose(s1 -> channel.closeSession("idle-sess-1"))
            .onComplete(testContext.succeeding(v ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(1, pool.getActiveProcessCount(), "Process should initially exist");
                    Assertions.assertEquals(0, pool.getTotalActiveSessionCount(), "Session count should be 0");
                });

                // Wait 600ms to comfortably exceed idleTimeout (150ms), then trigger reapIdleProcesses()
                vertx.setTimer(600, timerId ->
                {
                    pool.reapIdleProcesses();
                    testContext.verify(() ->
                    {
                        Assertions.assertEquals(0, pool.getActiveProcessCount(), "Idle process should have been reaped");
                    });
                    channel.close().onComplete(testContext.succeeding(done -> testContext.completeNow()));
                });
            }));
    }
}
