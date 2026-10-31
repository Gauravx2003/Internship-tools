package com.motadata.websession.modules.auth.handler;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

public class AuthRouter
{

    private final AuthHandler authHandler;

    public AuthRouter(AuthHandler authHandler)
    {
        this.authHandler = authHandler;
    }

    public Router create(Vertx vertx)
    {
        Router router = Router.router(vertx);

        router.post("/login").handler(authHandler::handleLogin);

        router.post("/refresh").handler(authHandler::handleRefreshToken);

        router.post("/logout").handler(authHandler::handleLogout);

        router.get("/me").handler(authHandler::handleMe);

        router.post("/register").handler(authHandler::handleRegister);

        return router;
    }
}
