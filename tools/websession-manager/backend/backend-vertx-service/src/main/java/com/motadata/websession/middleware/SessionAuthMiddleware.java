package com.motadata.websession.middleware;

import com.motadata.websession.modules.auth.handler.AuthHandler;
import com.motadata.websession.modules.auth.service.AuthService;
import com.motadata.websession.common.error.ApiException;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public class SessionAuthMiddleware implements Handler<RoutingContext>
{

    private final AuthService authService;

    public SessionAuthMiddleware(AuthService authService)
    {
        this.authService = authService;
    }

    @Override
    public void handle(RoutingContext ctx)
    {
        String sessionId = AuthHandler.extractSessionToken(ctx);

        if (sessionId == null)
        {
            ctx.fail(new ApiException(401, "UNAUTHORIZED", "Authentication required"));

            return;
        }

        authService.authenticateSession(sessionId)

            .onSuccess(userJson ->
            {
                ctx.put("currentUser", userJson);

                ctx.put("userId", userJson.getString("id"));

                ctx.next();
            })

            .onFailure(ctx::fail);
    }
}
