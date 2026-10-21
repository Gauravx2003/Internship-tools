package com.motadata.websession.auth.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Optional;

public interface UserRepository
{

    Future<Optional<JsonObject>> findByUsername(String username);

    Future<Optional<JsonObject>> findById(String id);

    Future<JsonObject> create(JsonObject user);

    Future<Long> countUsers();
}
