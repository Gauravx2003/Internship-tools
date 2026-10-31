package com.motadata.websession.modules.auth.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Optional;

public interface RefreshTokenRepository
{

    Future<JsonObject> create(JsonObject tokenRecord);

    Future<Optional<JsonObject>> findByToken(String token);

    Future<Boolean> deleteByToken(String token);

    Future<Integer> deleteByUserId(String userId);

    Future<Integer> deleteExpired();
}
