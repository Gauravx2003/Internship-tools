package com.motadata.websession.modules.keys.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface KeyService
{

    Future<JsonObject> generateKey(String userId, JsonObject payload);
}
