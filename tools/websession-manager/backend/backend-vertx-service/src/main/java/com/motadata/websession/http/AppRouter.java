package com.motadata.websession.http;

import com.motadata.websession.middleware.CentralErrorHandler;
import com.motadata.websession.middleware.RequestIdMiddleware;
import com.motadata.websession.modules.auth.handler.AuthRouter;
import com.motadata.websession.modules.credentials.handler.CredentialRouter;
import com.motadata.websession.modules.hosts.handler.HostRouter;
import com.motadata.websession.modules.keys.handler.KeyRouter;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

import java.time.Instant;

public class AppRouter
{

    public static Router create(
        Vertx vertx,
        AuthRouter authRouter,
        HostRouter hostRouter,
        CredentialRouter credentialRouter,
        KeyRouter keyRouter)
    {
        Router router = Router.router(vertx);

        router.route().handler(new RequestIdMiddleware());

        router.route().failureHandler(new CentralErrorHandler());

        router.route().handler(BodyHandler.create());

        router.get("/health").handler(ctx ->
        {
            ctx.response()

                .putHeader("Content-Type", "application/json")

                .end(new JsonObject()

                    .put("status", "UP")

                    .put("timestamp", Instant.now().toString())

                    .encode()
                );
        });

        router.route("/api/v1/auth/*").subRouter(authRouter.create(vertx));

        router.route("/api/v1/hosts/*").subRouter(hostRouter.create(vertx));

        router.route("/api/v1/credentials/*").subRouter(credentialRouter.create(vertx));

        router.route("/api/v1/keys/*").subRouter(keyRouter.create(vertx));

        return router;
    }
}
