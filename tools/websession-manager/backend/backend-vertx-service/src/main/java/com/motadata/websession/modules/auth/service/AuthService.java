package com.motadata.websession.modules.auth.service;

import com.motadata.websession.modules.terminal.registry.TerminalSessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

public interface AuthService
{

    Future<JsonObject> login(String username, String password);

    Future<JsonObject> refreshToken(String refreshToken);

    Future<Void> logout(String sessionId);

    Future<Void> logout(String accessToken, String refreshToken);

    Future<Void> logoutAndCloseSessions(String sessionId);

    Future<Void> logoutAndCloseSessions(String accessToken, String refreshToken);

    Future<JsonObject> authenticateSession(String sessionId);

    Future<JsonObject> register(String username, String password);

    TerminalSessionRegistry getTerminalSessionRegistry();
}
