package com.motadata.websession.modules.hosts.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

public interface HostRepository
{

    Future<JsonObject> create(JsonObject host);

    Future<Optional<JsonObject>> findById(String id, String userId);

    Future<List<JsonObject>> listByUserId(String userId);

    Future<Optional<JsonObject>> update(String id, String userId, JsonObject host);

    Future<Boolean> deleteById(String id, String userId);
}
