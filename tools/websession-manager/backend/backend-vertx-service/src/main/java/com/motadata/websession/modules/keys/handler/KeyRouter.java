package com.motadata.websession.modules.keys.handler;

import com.motadata.websession.middleware.SessionAuthMiddleware;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

public class KeyRouter
{

    private final KeyHandler keyHandler;

    private final SessionAuthMiddleware sessionAuthMiddleware;

    public KeyRouter(
        KeyHandler keyHandler,
        SessionAuthMiddleware sessionAuthMiddleware)
    {
        this.keyHandler = keyHandler;

        this.sessionAuthMiddleware = sessionAuthMiddleware;
    }

    public Router create(Vertx vertx)
    {
        Router router = Router.router(vertx);

        router.route().handler(sessionAuthMiddleware);

        router.post("/generate").handler(keyHandler::handleGenerate);

        return router;
    }
}
