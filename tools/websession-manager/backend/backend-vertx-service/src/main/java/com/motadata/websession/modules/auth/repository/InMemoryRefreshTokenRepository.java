package com.motadata.websession.modules.auth.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class InMemoryRefreshTokenRepository implements RefreshTokenRepository
{

    private final Map<String, JsonObject> tokens = new ConcurrentHashMap<>();

    @Override
    public Future<JsonObject> create(JsonObject tokenRecord)
    {
        String token = tokenRecord.getString("token");
        tokens.put(token, tokenRecord.copy());
        return Future.succeededFuture(tokenRecord);
    }

    @Override
    public Future<Optional<JsonObject>> findByToken(String token)
    {
        JsonObject record = tokens.get(token);
        if (record == null)
        {
            return Future.succeededFuture(Optional.empty());
        }
        return Future.succeededFuture(Optional.of(record.copy()));
    }

    @Override
    public Future<Boolean> deleteByToken(String token)
    {
        JsonObject removed = tokens.remove(token);
        return Future.succeededFuture(removed != null);
    }

    @Override
    public Future<Integer> deleteByUserId(String userId)
    {
        AtomicInteger count = new AtomicInteger(0);
        tokens.entrySet().removeIf(entry ->
        {
            JsonObject record = entry.getValue();
            String uid = record.getString("userId", record.getString("user_id"));
            if (userId != null && userId.equals(uid))
            {
                count.incrementAndGet();
                return true;
            }
            return false;
        });
        return Future.succeededFuture(count.get());
    }

    @Override
    public Future<Integer> deleteExpired()
    {
        Instant now = Instant.now();
        AtomicInteger count = new AtomicInteger(0);
        tokens.entrySet().removeIf(entry ->
        {
            JsonObject record = entry.getValue();
            String expStr = record.getString("expiresAt", record.getString("expires_at"));
            if (expStr != null)
            {
                try
                {
                    if (now.isAfter(Instant.parse(expStr)))
                    {
                        count.incrementAndGet();
                        return true;
                    }
                }
                catch (Exception ignored)
                {
                }
            }
            return false;
        });
        return Future.succeededFuture(count.get());
    }
}
