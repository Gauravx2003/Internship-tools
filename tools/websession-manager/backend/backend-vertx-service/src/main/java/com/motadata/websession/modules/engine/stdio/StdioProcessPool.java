package com.motadata.websession.modules.engine.stdio;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StdioProcessPool manages a dynamic pool of Go Session Engine processes.
 * It enforces a ceiling of N sessions per process (default N=5) and dynamically
 * routes new sessions to existing processes with available capacity before spawning new ones.
 * Also reaps processes that have been idle with 0 active sessions for longer than the idle timeout.
 */
public class StdioProcessPool
{

    private final Vertx vertx;

    private final AppConfig config;

    private final List<GoProcessInstance> processes = new CopyOnWriteArrayList<>();


    //For unit tests
    private final Map<String, GoProcessInstance> sessionToProcess = new ConcurrentHashMap<>();

    private final int maxSessionsPerProcess;

    private final String binaryPath;

    private final long idleTimeoutMillis;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Object allocationLock = new Object();

    private final long reaperTimerId;

    public StdioProcessPool(Vertx vertx, AppConfig config)
    {
        this(vertx, config, 60_000L);
    }

    public StdioProcessPool(Vertx vertx, AppConfig config, long idleTimeoutMillis)
    {
        this.vertx = vertx;
        this.config = config;
        this.maxSessionsPerProcess = config.getEngineMaxSessionsPerProcess();
        this.binaryPath = config.getEngineBinaryPath();
        this.idleTimeoutMillis = idleTimeoutMillis > 0 ? idleTimeoutMillis : 60_000L;

        // Run idle reaper check every 10 seconds
        this.reaperTimerId = vertx.setPeriodic(10_000L, id -> reapIdleProcesses());
    }

    /**
     * Finds an existing process with spare capacity (< N) or asynchronously spawns a new
     * process if all current processes are full.
     */
    public Future<GoProcessInstance> acquireProcessForSession(String sessionId, Handler<EngineEnvelope> messageHandler)
    {
        if (closed.get())
        {
            return Future.failedFuture(new IllegalStateException("StdioProcessPool is closed"));
        }

        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.failedFuture(new IllegalArgumentException("sessionId cannot be null or empty"));
        }

        String cleanSessionId = sessionId.trim();

        // 1. Fast path: check existing processes with capacity
        synchronized (allocationLock)
        {
            for (GoProcessInstance proc : processes)
            {
                if (proc.hasCapacity())
                {
                    proc.registerSession(cleanSessionId, messageHandler);
                    sessionToProcess.put(cleanSessionId, proc);
                    return Future.succeededFuture(proc);
                }
            }
        }

        // 2. Slow path: all existing processes at capacity (or none yet).
        // Execute process spawn on worker thread via executeBlocking to protect event loop.
        return vertx.executeBlocking(() ->
        {
            synchronized (allocationLock)
            {
                // Re-check after acquiring worker lock in case capacity became available
                for (GoProcessInstance proc : processes)
                {
                    if (proc.hasCapacity())
                    {
                        proc.registerSession(cleanSessionId, messageHandler);
                        sessionToProcess.put(cleanSessionId, proc);
                        return proc;
                    }
                }

                // Spawn new Go engine process
                GoProcessInstance newProc = spawnProcess();
                processes.add(newProc);
                newProc.registerSession(cleanSessionId, messageHandler);
                sessionToProcess.put(cleanSessionId, newProc);
                return newProc;
            }
        });
    }

    public GoProcessInstance getProcessForSession(String sessionId)
    {
        if (sessionId == null)
        {
            return null;
        }
        return sessionToProcess.get(sessionId.trim());
    }

    public void releaseSession(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }

        String cleanSessionId = sessionId.trim();
        GoProcessInstance proc = sessionToProcess.remove(cleanSessionId);

        if (proc != null)
        {
            proc.removeSession(cleanSessionId);
        }
    }

    public int getActiveProcessCount()
    {
        return processes.size();
    }

    public int getTotalActiveSessionCount()
    {
        return sessionToProcess.size();
    }

    public List<GoProcessInstance> getProcesses()
    {
        return List.copyOf(processes);
    }

    public JsonObject getStatusJson()
    {
        JsonArray procArray = new JsonArray();
        for (GoProcessInstance proc : processes)
        {
            JsonArray sessArray = new JsonArray();
            for (String sessId : proc.getActiveSessionIds())
            {
                sessArray.add(sessId);
            }

            procArray.add(new JsonObject()
                .put("pid", proc.getPid())
                .put("isAlive", proc.isAlive())
                .put("activeSessionCount", proc.getActiveSessionCount())
                .put("maxCapacity", proc.getMaxCapacity())
                .put("hasCapacity", proc.hasCapacity())
                .put("sessions", sessArray)
            );
        }

        return new JsonObject()
            .put("status", "UP")
            .put("activeProcesses", processes.size())
            .put("totalActiveSessions", sessionToProcess.size())
            .put("maxSessionsPerProcess", maxSessionsPerProcess)
            .put("processes", procArray);
    }

    /**
     * Periodically inspects running processes and terminates any that have been completely idle
     * (0 active sessions) for longer than idleTimeoutMillis.
     */
    public void reapIdleProcesses()
    {
        if (closed.get())
        {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (allocationLock)
        {
            for (GoProcessInstance proc : processes)
            {
                if (proc.getActiveSessionCount() == 0 && (now - proc.getLastActivityTime()) > idleTimeoutMillis)
                {
                    proc.destroy();
                    processes.remove(proc);
                }
            }
        }
    }

    public Future<Void> close()
    {
        if (closed.compareAndSet(false, true))
        {
            vertx.cancelTimer(reaperTimerId);
            sessionToProcess.clear();
            for (GoProcessInstance proc : processes)
            {
                proc.destroy();
            }
            processes.clear();
        }
        return Future.succeededFuture();
    }

    private GoProcessInstance spawnProcess() throws IOException
    {
        File binFile = new File(binaryPath);
        if (!binFile.exists() || !binFile.canExecute())
        {
            throw new IOException("Go session engine binary not found or not executable at: " + binaryPath);
        }

        ProcessBuilder pb = new ProcessBuilder(
            binaryPath,
            "--stdio",
            "--max-sessions=" + maxSessionsPerProcess
        );

        // Environment variables for child process
        Map<String, String> env = pb.environment();
        env.put("ENGINE_MODE", "stdio");
        env.put("ENGINE_MAX_SESSIONS_PER_PROC", String.valueOf(maxSessionsPerProcess));

        String insecureEnv = System.getenv("ENGINE_INSECURE_IGNORE_HOSTKEY");
        if (insecureEnv != null && !insecureEnv.trim().isEmpty())
        {
            env.put("ENGINE_INSECURE_IGNORE_HOSTKEY", insecureEnv.trim());
        }

        pb.redirectError(ProcessBuilder.Redirect.PIPE);

        Process process = pb.start();

        GoProcessInstance proc = new GoProcessInstance(vertx, process, maxSessionsPerProcess);
        proc.setOnTerminated(p ->
        {
            processes.remove(p);
            // Clean up all sessions that were mapped to this process
            sessionToProcess.entrySet().removeIf(entry -> entry.getValue() == p);
        });

        return proc;
    }
}
