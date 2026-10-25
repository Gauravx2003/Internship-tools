package com.motadata.websession.modules.credentials.handler;

import com.motadata.websession.middleware.SessionAuthMiddleware;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

public class CredentialRouter
{

    private final CredentialHandler credentialHandler;

    private final SessionAuthMiddleware sessionAuthMiddleware;

    public CredentialRouter(
        CredentialHandler credentialHandler,
        SessionAuthMiddleware sessionAuthMiddleware)
    {
        this.credentialHandler = credentialHandler;

        this.sessionAuthMiddleware = sessionAuthMiddleware;
    }

    public Router create(Vertx vertx)
    {
        Router router = Router.router(vertx);

        router.route().handler(sessionAuthMiddleware);

        router.get("/").handler(credentialHandler::handleList);

        router.post("/").handler(credentialHandler::handleCreate);

        router.get("/:id").handler(credentialHandler::handleGet);

        router.delete("/:id").handler(credentialHandler::handleDelete);

        return router;
    }
}
