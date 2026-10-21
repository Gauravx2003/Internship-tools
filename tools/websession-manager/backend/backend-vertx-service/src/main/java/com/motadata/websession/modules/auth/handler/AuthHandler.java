package com.motadata.websession.auth.handler;

import com.motadata.websession.auth.service.AuthService;
import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.config.AppConfig;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class AuthHandler
{

    public static final String SESSION_COOKIE_NAME = "WEBSESSION_ID";

    private final AuthService authService;

    private final AppConfig config;

    public AuthHandler(AuthService authService, AppConfig config)
    {
        this.authService = authService;

        this.config = config;
    }

    public void handleLogin(RoutingContext ctx)
    {
        JsonObject body = ctx.body().asJsonObject();

        if (body == null)
        {
            ctx.fail(new ApiException(400, "INVALID_REQUEST", "Request body must be a JSON object"));

            return;
        }

        String username = body.getString("username");

        String password = body.getString("password");

        authService.login(username, password)

            .onSuccess(loginResult ->
            {
                String sessionId = loginResult.getString("token");

                Cookie sessionCookie = Cookie.cookie(SESSION_COOKIE_NAME, sessionId)

                    .setPath("/")

                    .setHttpOnly(true)

                    .setSameSite(CookieSameSite.STRICT)

                    .setSecure(config.isCookieSecure())

                    .setMaxAge(config.getSessionTimeoutMinutes() * 60L);

                ctx.response().addCookie(sessionCookie);

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(loginResult.encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleLogout(RoutingContext ctx)
    {
        String sessionId = extractSessionToken(ctx);

        authService.logout(sessionId)

            .onComplete(ar ->
            {
                Cookie clearCookie = Cookie.cookie(SESSION_COOKIE_NAME, "")

                    .setPath("/")

                    .setHttpOnly(true)

                    .setSameSite(CookieSameSite.STRICT)

                    .setSecure(config.isCookieSecure())

                    .setMaxAge(0);

                ctx.response().addCookie(clearCookie);

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(new JsonObject().put("message", "Logged out successfully").encode());
            });
    }

    public void handleMe(RoutingContext ctx)
    {
        String sessionId = extractSessionToken(ctx);

        if (sessionId == null)
        {
            ctx.fail(new ApiException(401, "UNAUTHORIZED", "Authentication required"));

            return;
        }

        authService.authenticateSession(sessionId)

            .onSuccess(userJson ->
            {
                JsonObject responseBody = new JsonObject()

                    .put("user", userJson);

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(responseBody.encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleRegister(RoutingContext ctx)
    {
        JsonObject body = ctx.body().asJsonObject();

        if (body == null)
        {
            ctx.fail(new ApiException(400, "INVALID_REQUEST", "Request body must be a JSON object"));

            return;
        }

        String username = body.getString("username");

        String password = body.getString("password");

        authService.register(username, password)

            .onSuccess(userJson ->
            {
                JsonObject responseBody = new JsonObject()

                    .put("user", userJson);

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(201)

                    .end(responseBody.encode());
            })

            .onFailure(ctx::fail);
    }

    public static String extractSessionToken(RoutingContext ctx)
    {
        String authHeader = ctx.request().getHeader("Authorization");

        if (authHeader != null && authHeader.toLowerCase().startsWith("bearer "))
        {
            String token = authHeader.substring(7).trim();

            if (!token.isEmpty())
            {
                return token;
            }
        }

        String headerToken = ctx.request().getHeader("X-Session-Id");

        if (headerToken != null && !headerToken.trim().isEmpty())
        {
            return headerToken.trim();
        }

        Cookie cookie = ctx.request().getCookie(SESSION_COOKIE_NAME);

        if (cookie != null && cookie.getValue() != null && !cookie.getValue().trim().isEmpty())
        {
            return cookie.getValue().trim();
        }

        return null;
    }
}
