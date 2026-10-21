package com.motadata.websession.credentials.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

public interface CredentialRepository
{

    Future<JsonObject> create(JsonObject credential);

    Future<Optional<JsonObject>> findById(String id, String userId);

    Future<List<JsonObject>> listByUserId(String userId);

    Future<Boolean> deleteById(String id, String userId);
}
