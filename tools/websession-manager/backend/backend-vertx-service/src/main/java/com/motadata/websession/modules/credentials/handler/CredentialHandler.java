package com.motadata.websession.modules.credentials.handler;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.modules.credentials.service.CredentialService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class CredentialHandler
{

    private final CredentialService credentialService;

    public CredentialHandler(CredentialService credentialService)
    {
        this.credentialService = credentialService;
    }

    public void handleList(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        credentialService.list(userId)

            .onSuccess(creds ->
            {
                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(new JsonArray(creds).encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleCreate(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        JsonObject body = ctx.body().asJsonObject();

        if (body == null)
        {
            ctx.fail(new ApiException(400, "INVALID_REQUEST", "Request body must be a JSON object"));

            return;
        }

        credentialService.create(userId, body)

            .onSuccess(createdCred ->
            {
                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(201)

                    .end(createdCred.encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleGet(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        String credId = ctx.pathParam("id");

        credentialService.findById(credId, userId)

            .onSuccess(credOpt ->
            {
                if (credOpt.isEmpty())
                {
                    ctx.fail(new ApiException(404, "CREDENTIAL_NOT_FOUND", "Credential not found"));

                    return;
                }

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(credOpt.get().encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleDelete(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        String credId = ctx.pathParam("id");

        credentialService.delete(credId, userId)

            .onSuccess(deleted ->
            {
                if (!deleted)
                {
                    ctx.fail(new ApiException(404, "CREDENTIAL_NOT_FOUND", "Credential not found"));

                    return;
                }

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(new JsonObject().put("message", "Credential deleted successfully").encode());
            })

            .onFailure(ctx::fail);
    }
}
