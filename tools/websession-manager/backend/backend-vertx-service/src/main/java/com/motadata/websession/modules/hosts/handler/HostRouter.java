package com.motadata.websession.modules.hosts.handler;

import com.motadata.websession.middleware.SessionAuthMiddleware;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;

public class HostRouter
{

    private final HostHandler hostHandler;

    private final SessionAuthMiddleware sessionAuthMiddleware;

    public HostRouter(
        HostHandler hostHandler,
        SessionAuthMiddleware sessionAuthMiddleware)
    {
        this.hostHandler = hostHandler;

        this.sessionAuthMiddleware = sessionAuthMiddleware;
    }

    public Router create(Vertx vertx)
    {
        Router router = Router.router(vertx);

        router.route().handler(sessionAuthMiddleware);

        router.get("/").handler(hostHandler::handleList);

        router.post("/").handler(hostHandler::handleCreate);

        router.get("/:id").handler(hostHandler::handleGet);

        router.put("/:id").handler(hostHandler::handleUpdate);

        router.delete("/:id").handler(hostHandler::handleDelete);

        return router;
    }
}
