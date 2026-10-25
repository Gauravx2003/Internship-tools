package com.motadata.websession.modules.hosts.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

public interface HostService
{

    Future<JsonObject> create(String userId, JsonObject payload);

    Future<Optional<JsonObject>> findById(String id, String userId);

    Future<List<JsonObject>> list(String userId);

    Future<Optional<JsonObject>> update(String id, String userId, JsonObject payload);

    Future<Boolean> delete(String id, String userId);
}
