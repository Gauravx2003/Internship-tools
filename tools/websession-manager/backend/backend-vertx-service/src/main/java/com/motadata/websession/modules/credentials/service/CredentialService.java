package com.motadata.websession.credentials.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Optional;

public interface CredentialService
{

    Future<JsonObject> create(String userId, JsonObject payload);

    Future<Optional<JsonObject>> findById(String id, String userId);

    Future<List<JsonObject>> list(String userId);

    Future<Boolean> delete(String id, String userId);

    Future<Optional<String>> decryptSecret(String id, String userId);
}
