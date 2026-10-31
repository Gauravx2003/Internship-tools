package com.motadata.websession.modules.auth.handler;

import com.motadata.websession.modules.auth.service.AuthService;
import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.config.AppConfig;
import io.vertx.core.Future;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class AuthHandler
{

    public static final String SESSION_COOKIE_NAME = "WEBSESSION_ID";

    public static final String REFRESH_COOKIE_NAME = "WEBSESSION_REFRESH_TOKEN";

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
                String accessToken = loginResult.getString("accessToken");

                if (accessToken == null)
                {
                    accessToken = loginResult.getString("token");
                }

                String refreshToken = loginResult.getString("refreshToken");

                Cookie sessionCookie = Cookie.cookie(SESSION_COOKIE_NAME, accessToken)
                    .setPath("/")
                    .setHttpOnly(true)
                    .setSameSite(CookieSameSite.STRICT)
                    .setSecure(config.isCookieSecure())
                    .setMaxAge(config.getAccessTokenTtlMinutes() * 60L);

                ctx.response().addCookie(sessionCookie);

                if (refreshToken != null)
                {
                    Cookie refreshCookie = Cookie.cookie(REFRESH_COOKIE_NAME, refreshToken)
                        .setPath("/")
                        .setHttpOnly(true)
                        .setSameSite(CookieSameSite.STRICT)
                        .setSecure(config.isCookieSecure())
                        .setMaxAge(config.getRefreshTokenTtlDays() * 24L * 3600L);

                    ctx.response().addCookie(refreshCookie);
                }

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(loginResult.encode());
            })
            .onFailure(ctx::fail);
    }

    public void handleRefreshToken(RoutingContext ctx)
    {
        String refreshToken = extractRefreshToken(ctx);

        if (refreshToken == null || refreshToken.trim().isEmpty())
        {
            ctx.fail(new ApiException(400, "INVALID_REQUEST", "Refresh token is required"));

            return;
        }

        authService.refreshToken(refreshToken)
            .onSuccess(refreshResult ->
            {
                String newAccessToken = refreshResult.getString("accessToken");

                if (newAccessToken == null)
                {
                    newAccessToken = refreshResult.getString("token");
                }

                String newRefreshToken = refreshResult.getString("refreshToken");

                Cookie sessionCookie = Cookie.cookie(SESSION_COOKIE_NAME, newAccessToken)
                    .setPath("/")
                    .setHttpOnly(true)
                    .setSameSite(CookieSameSite.STRICT)
                    .setSecure(config.isCookieSecure())
                    .setMaxAge(config.getAccessTokenTtlMinutes() * 60L);

                ctx.response().addCookie(sessionCookie);

                if (newRefreshToken != null)
                {
                    Cookie refreshCookie = Cookie.cookie(REFRESH_COOKIE_NAME, newRefreshToken)
                        .setPath("/")
                        .setHttpOnly(true)
                        .setSameSite(CookieSameSite.STRICT)
                        .setSecure(config.isCookieSecure())
                        .setMaxAge(config.getRefreshTokenTtlDays() * 24L * 3600L);

                    ctx.response().addCookie(refreshCookie);
                }

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(refreshResult.encode());
            })
            .onFailure(ctx::fail);
    }

    public void handleLogout(RoutingContext ctx)
    {
        String accessToken = extractSessionToken(ctx);

        String refreshToken = extractRefreshToken(ctx);

        boolean closeAll = "true".equalsIgnoreCase(ctx.request().getParam("closeAll"))
            || "true".equalsIgnoreCase(ctx.request().getParam("close_sessions"))
            || "true".equalsIgnoreCase(ctx.request().getParam("closeSessions"));

        if (!closeAll && ctx.body() != null)
        {
            try
            {
                JsonObject body = ctx.body().asJsonObject();

                if (body != null)
                {
                    closeAll = body.getBoolean("closeAll", false)
                        || body.getBoolean("close_sessions", false)
                        || body.getBoolean("closeSessions", false);
                }
            }
            catch (Exception ignored)
            {
            }
        }

        final boolean closeSessions = closeAll;

        Future<Void> logoutFuture = closeSessions
            ? authService.logoutAndCloseSessions(accessToken, refreshToken)
            : authService.logout(accessToken, refreshToken);

        logoutFuture.onComplete(ar ->
        {
            clearSessionCookies(ctx);

            String message = closeSessions
                ? "Logged out and closed all ongoing sessions successfully"
                : "Logged out successfully";

            ctx.response()
                .putHeader("Content-Type", "application/json")
                .setStatusCode(200)
                .end(new JsonObject().put("message", message).encode());
        });
    }

    public void handleLogoutAndCloseSessions(RoutingContext ctx)
    {
        String accessToken = extractSessionToken(ctx);

        String refreshToken = extractRefreshToken(ctx);

        authService.logoutAndCloseSessions(accessToken, refreshToken)
            .onComplete(ar ->
            {
                clearSessionCookies(ctx);

                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .setStatusCode(200)
                    .end(new JsonObject()
                        .put("message", "Logged out and closed all ongoing sessions successfully")
                        .encode());
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
            .onFailure(err ->
            {
                ctx.fail(err);
            });
    }

    private void clearSessionCookies(RoutingContext ctx)
    {
        Cookie clearSessionCookie = Cookie.cookie(SESSION_COOKIE_NAME, "")
            .setPath("/")
            .setHttpOnly(true)
            .setSameSite(CookieSameSite.STRICT)
            .setSecure(config.isCookieSecure())
            .setMaxAge(0);

        Cookie clearRefreshCookie = Cookie.cookie(REFRESH_COOKIE_NAME, "")
            .setPath("/")
            .setHttpOnly(true)
            .setSameSite(CookieSameSite.STRICT)
            .setSecure(config.isCookieSecure())
            .setMaxAge(0);

        ctx.response().addCookie(clearSessionCookie);

        ctx.response().addCookie(clearRefreshCookie);
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

    public static String extractRefreshToken(RoutingContext ctx)
    {
        if (ctx.body() != null)
        {
            try
            {
                JsonObject body = ctx.body().asJsonObject();

                if (body != null)
                {
                    String token = body.getString("refreshToken");

                    if (token == null)
                    {
                        token = body.getString("refresh_token");
                    }

                    if (token != null && !token.trim().isEmpty())
                    {
                        return token.trim();
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }

        String headerToken = ctx.request().getHeader("X-Refresh-Token");

        if (headerToken != null && !headerToken.trim().isEmpty())
        {
            return headerToken.trim();
        }

        Cookie cookie = ctx.request().getCookie(REFRESH_COOKIE_NAME);

        if (cookie != null && cookie.getValue() != null && !cookie.getValue().trim().isEmpty())
        {
            return cookie.getValue().trim();
        }

        return null;
    }
}
