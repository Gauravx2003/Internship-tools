package com.motadata.websession.modules.auth.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface AuthService
{

    Future<JsonObject> login(String username, String password);

    Future<Void> logout(String sessionId);

    Future<JsonObject> authenticateSession(String sessionId);

    Future<JsonObject> register(String username, String password);
}
