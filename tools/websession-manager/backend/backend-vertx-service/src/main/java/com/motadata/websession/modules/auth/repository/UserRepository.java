package com.motadata.websession.modules.auth.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Optional;

public interface UserRepository
{

    Future<Optional<JsonObject>> findByUsername(String username);

    Future<Optional<JsonObject>> findById(String id);

    Future<JsonObject> create(JsonObject user);

    Future<Long> countUsers();

    @Deprecated
    default Future<Void> updateTokensValidAfter(String userId, Instant tokensValidAfter)
    {
        return Future.succeededFuture();
    }
}
