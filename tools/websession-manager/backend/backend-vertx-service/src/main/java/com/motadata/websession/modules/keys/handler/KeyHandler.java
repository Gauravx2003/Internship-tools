package com.motadata.websession.modules.keys.handler;

import com.motadata.websession.modules.keys.service.KeyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class KeyHandler
{

    private final KeyService keyService;

    public KeyHandler(KeyService keyService)
    {
        this.keyService = keyService;
    }

    public void handleGenerate(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        JsonObject body = ctx.body().asJsonObject();

        if (body == null)
        {
            body = new JsonObject();
        }

        keyService.generateKey(userId, body)

            .onSuccess(generatedKey ->
            {
                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(201)

                    .end(generatedKey.encode());
            })

            .onFailure(ctx::fail);
    }
}
