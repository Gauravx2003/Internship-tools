package com.motadata.websession.modules.engine.stdio;

import com.motadata.websession.modules.engine.model.EngineEnvelope;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * GoProcessInstance wraps an operating system child process running the Go Session Engine.
 * It manages asynchronous reads from stdout/stderr, and asynchronous sequential writes
 * to stdin over dedicated background threads so the Vert.x Event Loop is never blocked.
 */
public class GoProcessInstance
{

    /**
     * SessionContext encapsulates all callback handlers and metadata for a single session
     * multiplexed over this process instance, eliminating redundant parallel maps.
     */
    private static class SessionContext
    {
        volatile Handler<EngineEnvelope> messageHandler;
        volatile Handler<Void> closeHandler;
        volatile Handler<Throwable> errorHandler;

        SessionContext(Handler<EngineEnvelope> messageHandler)
        {
            this.messageHandler = messageHandler;
        }
    }

    private final Vertx vertx;

    private final Process process;

    private final long pid;

    private final int maxCapacity;

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();

    private final BufferedWriter stdinWriter;

    private final ExecutorService writeExecutor;

    private final Thread stdoutThread;

    private final Thread stderrThread;

    private final AtomicBoolean terminated = new AtomicBoolean(false);

    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());

    private Consumer<GoProcessInstance> onTerminatedCallback;

    public GoProcessInstance(Vertx vertx, Process process, int maxCapacity)
    {
        this.vertx = vertx;
        this.process = process;
        this.pid = process.pid();
        this.maxCapacity = maxCapacity > 0 ? maxCapacity : 5;

        this.stdinWriter = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.writeExecutor = Executors.newSingleThreadExecutor(r ->
        {
            Thread t = new Thread(r, "go-process-stdin-writer-" + pid);
            t.setDaemon(true);
            return t;
        });

        this.stdoutThread = new Thread(this::readStdoutLoop, "go-process-stdout-reader-" + pid);
        this.stdoutThread.setDaemon(true);
        this.stdoutThread.start();

        this.stderrThread = new Thread(this::readStderrLoop, "go-process-stderr-reader-" + pid);
        this.stderrThread.setDaemon(true);
        this.stderrThread.start();
    }

    public long getPid()
    {
        return pid;
    }

    public int getMaxCapacity()
    {
        return maxCapacity;
    }

    public int getActiveSessionCount()
    {
        return sessions.size();
    }

    public Set<String> getActiveSessionIds()
    {
        return Set.copyOf(sessions.keySet());
    }

    public long getLastActivityTime()
    {
        return lastActivityTime.get();
    }

    public boolean hasCapacity()
    {
        return !terminated.get() && process.isAlive() && sessions.size() < maxCapacity;
    }

    public boolean isAlive()
    {
        return !terminated.get() && process.isAlive();
    }

    public boolean hasSession(String sessionId)
    {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public void setOnTerminated(Consumer<GoProcessInstance> onTerminatedCallback)
    {
        this.onTerminatedCallback = onTerminatedCallback;
    }

    public void registerSession(String sessionId, Handler<EngineEnvelope> messageHandler)
    {
        if (sessionId == null)
        {
            return;
        }
        sessions.put(sessionId, new SessionContext(messageHandler));
        lastActivityTime.set(System.currentTimeMillis());
    }

    public void removeSession(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty())
        {
            lastActivityTime.set(System.currentTimeMillis());
        }
    }

    public void onSessionClose(String sessionId, Handler<Void> closeHandler)
    {
        if (sessionId != null && closeHandler != null)
        {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx != null)
            {
                ctx.closeHandler = closeHandler;
            }
        }
    }

    public void onSessionError(String sessionId, Handler<Throwable> errorHandler)
    {
        if (sessionId != null && errorHandler != null)
        {
            SessionContext ctx = sessions.get(sessionId);
            if (ctx != null)
            {
                ctx.errorHandler = errorHandler;
            }
        }
    }

    /**
     * Enqueues an envelope to be written to child stdin over the background worker thread.
     * Completes the returned Future on the Vert.x Event Loop context.
     */
    public Future<Void> writeEnvelope(EngineEnvelope envelope)
    {
        if (terminated.get() || !process.isAlive())
        {
            return Future.failedFuture(new IllegalStateException("Go engine process (PID " + pid + ") is terminated"));
        }

        lastActivityTime.set(System.currentTimeMillis());
        Promise<Void> promise = Promise.promise();
        writeExecutor.submit(() ->
        {
            try
            {
                String jsonLine = envelope.encode();
                synchronized (stdinWriter)
                {
                    stdinWriter.write(jsonLine);
                    stdinWriter.newLine();
                    stdinWriter.flush();
                }
                vertx.runOnContext(v -> promise.complete());
            }
            catch (Exception ex)
            {
                vertx.runOnContext(v -> promise.fail(ex));
            }
        });

        return promise.future();
    }

    public void destroy()
    {
        try
        {
            stdinWriter.close();
        }
        catch (Exception ignored)
        {
        }

        if (process.isAlive())
        {
            process.destroyForcibly();
        }

        handleProcessTerminated();
    }

    private void readStdoutLoop()
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while (!terminated.get() && (line = reader.readLine()) != null)
            {
                String clean = line.trim();
                if (clean.isEmpty())
                {
                    continue;
                }

                try
                {
                    JsonObject json = new JsonObject(clean);
                    EngineEnvelope envelope = EngineEnvelope.fromJson(json);

                    if (envelope != null)
                    {
                        String sessionId = envelope.getSessionId();
                        SessionContext ctx = sessions.get(sessionId);

                        if (ctx != null && ctx.messageHandler != null)
                        {
                            // Transition onto Vert.x Event Loop thread
                            vertx.runOnContext(v -> ctx.messageHandler.handle(envelope));
                        }

                        if ("closed".equals(envelope.getType()))
                        {
                            handleSessionClosed(sessionId);
                        }
                    }
                }
                catch (Exception e)
                {
                    System.err.println("[GoProcess-" + pid + "] Error parsing stdout JSON: " + e.getMessage() + " | raw: " + clean);
                }
            }
        }
        catch (IOException ignored)
        {
        }
        finally
        {
            handleProcessTerminated();
        }
    }

    private void readStderrLoop()
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                System.err.println("[GoEngine-" + pid + "-stderr] " + line);
            }
        }
        catch (IOException ignored)
        {
        }
    }

    private void handleSessionClosed(String sessionId)
    {
        if (sessionId == null)
        {
            return;
        }

        SessionContext ctx = sessions.remove(sessionId);

        if (sessions.isEmpty())
        {
            lastActivityTime.set(System.currentTimeMillis());
        }

        if (ctx != null && ctx.closeHandler != null)
        {
            vertx.runOnContext(v -> ctx.closeHandler.handle(null));
        }
    }

    private void handleProcessTerminated()
    {
        if (terminated.compareAndSet(false, true))
        {
            writeExecutor.shutdownNow();

            Map<String, SessionContext> remaining = new HashMap<>(sessions);
            sessions.clear();

            for (Map.Entry<String, SessionContext> entry : remaining.entrySet())
            {
                SessionContext ctx = entry.getValue();
                if (ctx.closeHandler != null)
                {
                    vertx.runOnContext(v -> ctx.closeHandler.handle(null));
                }

                if (ctx.errorHandler != null)
                {
                    vertx.runOnContext(v -> ctx.errorHandler.handle(new IOException("Go engine process (PID " + pid + ") terminated")));
                }
            }

            if (onTerminatedCallback != null)
            {
                onTerminatedCallback.accept(this);
            }
        }
    }
}
