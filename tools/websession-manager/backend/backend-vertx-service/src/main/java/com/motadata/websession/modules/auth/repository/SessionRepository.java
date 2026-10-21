package com.motadata.websession.modules.auth.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Optional;

public interface SessionRepository
{

    Future<JsonObject> createSession(JsonObject session);

    Future<Optional<JsonObject>> findById(String id);

    Future<Void> updateLastAccessed(String id);

    Future<Boolean> deleteById(String id);

    Future<Integer> deleteExpired();
}
