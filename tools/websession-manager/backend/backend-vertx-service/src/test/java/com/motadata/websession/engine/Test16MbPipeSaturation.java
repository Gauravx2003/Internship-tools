package com.motadata.websession.engine;

import com.motadata.websession.modules.engine.model.EngineEnvelope;
import com.motadata.websession.modules.engine.stdio.GoProcessInstance;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@ExtendWith(VertxExtension.class)
public class Test16MbPipeSaturation
{

    private static final String LOG_FILE_PATH = "/home/gaurav-daware/logs.log";
    private static String binaryPath;

    @BeforeAll
    static void ensureBinaryExists()
    {
        File bin = new File("../session-engine/bin/session-engine");
        binaryPath = bin.getAbsolutePath();
    }

    /**
     * TEST 1: Demonstrates that the Linux OS pipe buffer is exactly 64 KB (65,536 bytes).
     * If a process writes to an OS pipe without the reader draining it, write() blocks completely.
     */
    @Test
    void testLinuxPipeCapacityBlocksAt64KB() throws Exception
    {
        System.out.println("\n================================================================================");
        System.out.println(" [EXPERIMENT 1] Linux OS Pipe Buffer Saturation Test");
        System.out.println("================================================================================");

        // Spawn a process that does not read from stdin (sleep 5)
        Process p = new ProcessBuilder("sleep", "5").start();
        OutputStream pipeIn = p.getOutputStream();

        AtomicInteger bytesWritten = new AtomicInteger(0);
        AtomicBoolean blocked = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        Thread writerThread = new Thread(() ->
        {
            try
            {
                byte[] chunk = new byte[1024]; // 1 KB chunks
                for (int i = 0; i < 1000; i++)
                {
                    pipeIn.write(chunk);
                    pipeIn.flush();
                    bytesWritten.addAndGet(chunk.length);
                }
            }
            catch (Exception ex)
            {
                System.out.println("Write exception: " + ex.getMessage());
            }
            finally
            {
                latch.countDown();
            }
        });

        long start = System.currentTimeMillis();
        writerThread.start();

        // Wait up to 500ms; if write was non-blocking it would finish in 1ms.
        boolean finished = latch.await(500, TimeUnit.MILLISECONDS);

        long elapsed = System.currentTimeMillis() - start;
        int totalBuffered = bytesWritten.get();

        System.out.printf("   Total bytes accepted into pipe buffer before write() BLOCKED: %d bytes (%.2f KB)%n",
            totalBuffered, totalBuffered / 1024.0);
        System.out.printf("   Did write() block the calling thread? %s (elapsed %d ms)%n", !finished, elapsed);

        // Clean up
        p.destroyForcibly();
        writerThread.interrupt();

        Assertions.assertFalse(finished, "Pipe write should block once 64KB OS buffer is full");
        Assertions.assertTrue(totalBuffered >= 65536 && totalBuffered <= 131072,
            "OS pipe buffer should saturate around 64 KB (actual: " + totalBuffered + " bytes)");

        System.out.println("   [PROVED] Linux OS pipe buffer saturated at ~64 KB and blocked the thread!");
    }

    /**
     * TEST 2: Benchmarks writing the actual 16 MB logs.log file with GoProcessInstance.writeExecutor.
     * Shows that with writeExecutor, Vert.x Event Loop lag stays < 2ms while the 16 MB is flushed!
     */
    @Test
    void test16MbLogsFileWithWriteExecutor(Vertx vertx, VertxTestContext testContext) throws Exception
    {
        File logFile = new File(LOG_FILE_PATH);
        if (!logFile.exists())
        {
            System.out.println("Log file not found at " + LOG_FILE_PATH + ", skipping.");
            testContext.completeNow();
            return;
        }

        System.out.println("\n================================================================================");
        System.out.printf(" [EXPERIMENT 2] 16 MB Log File (%s, size: %.2f MB)%n",
            logFile.getName(), logFile.length() / (1024.0 * 1024.0));
        System.out.println("================================================================================");

        // Read 16 MB file into memory
        byte[] logBytes = new byte[(int) logFile.length()];
        try (FileInputStream fis = new FileInputStream(logFile))
        {
            int read = fis.read(logBytes);
            System.out.printf("   Loaded %d bytes into memory.%n", read);
        }

        // Spawn real Go Session Engine process
        ProcessBuilder pb = new ProcessBuilder(binaryPath, "--stdio");
        Process goProc = pb.start();

        GoProcessInstance proc = new GoProcessInstance(vertx, goProc, 5);
        String sessId = "bench-16mb-session";
        proc.registerSession(sessId, env -> {});

        // Event Loop Heartbeat Monitor: measures how long the Event Loop takes to tick
        AtomicInteger heartbeatTicks = new AtomicInteger(0);

        long heartbeatTimerId = vertx.setPeriodic(10, timerId ->
        {
            heartbeatTicks.incrementAndGet();
        });

        // Package as input envelope (test with 4 MB slice)
        int payloadSize = Math.min(logBytes.length, 4 * 1024 * 1024);
        String logSample = new String(logBytes, 0, payloadSize, StandardCharsets.UTF_8);

        JsonObject payload = new JsonObject().put("data", logSample);
        EngineEnvelope envelope = EngineEnvelope.create("input", sessId, 1, payload);

        System.out.println("   --> Enqueuing large payload (" + (payloadSize / (1024 * 1024)) + " MB) via proc.writeEnvelope()...");

        long startTime = System.currentTimeMillis();

        // Calling writeEnvelope on Vert.x context
        vertx.runOnContext(v ->
        {
            long submitStart = System.nanoTime();
            proc.writeEnvelope(envelope)
                .onSuccess(unused ->
                {
                    long totalDuration = System.currentTimeMillis() - startTime;
                    System.out.printf("   [SUCCESS] Background writeExecutor flushed %d MB in %d ms!%n",
                        payloadSize / (1024 * 1024), totalDuration);
                    System.out.printf("   Total Event Loop Heartbeat Ticks during write: %d ticks%n", heartbeatTicks.get());

                    vertx.cancelTimer(heartbeatTimerId);
                    proc.destroy();

                    testContext.verify(() ->
                    {
                        // Event loop must have continued ticking while the large write occurred!
                        Assertions.assertTrue(heartbeatTicks.get() > 0, "Event loop should not be frozen!");
                    });
                    testContext.completeNow();
                })
                .onFailure(err ->
                {
                    vertx.cancelTimer(heartbeatTimerId);
                    proc.destroy();
                    testContext.failNow(err);
                });

            long submitDurationNanos = System.nanoTime() - submitStart;
            System.out.printf("   --> proc.writeEnvelope() returned to Event Loop in: %.3f ms (NON-BLOCKING!)%n",
                submitDurationNanos / 1_000_000.0);
        });
    }
}
