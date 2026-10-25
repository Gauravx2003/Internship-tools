package com.motadata.websession.modules.hosts.handler;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.modules.hosts.service.HostService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class HostHandler
{

    private final HostService hostService;

    public HostHandler(HostService hostService)
    {
        this.hostService = hostService;
    }

    public void handleList(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        hostService.list(userId)

            .onSuccess(hosts ->
            {
                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(new JsonArray(hosts).encode());
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



        hostService.create(userId, body)

            .onSuccess(createdHost ->
            {
                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(201)

                    .end(createdHost.encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleGet(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        String hostId = ctx.pathParam("id");

        hostService.findById(hostId, userId)

            .onSuccess(hostOpt ->
            {
                if (hostOpt.isEmpty())
                {
                    ctx.fail(new ApiException(404, "HOST_NOT_FOUND", "Host not found"));

                    return;
                }

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(hostOpt.get().encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleUpdate(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        String hostId = ctx.pathParam("id");

        JsonObject body = ctx.body().asJsonObject();

        if (body == null)
        {
            ctx.fail(new ApiException(400, "INVALID_REQUEST", "Request body must be a JSON object"));

            return;
        }

        hostService.update(hostId, userId, body)

            .onSuccess(hostOpt ->
            {
                if (hostOpt.isEmpty())
                {
                    ctx.fail(new ApiException(404, "HOST_NOT_FOUND", "Host not found"));

                    return;
                }

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(hostOpt.get().encode());
            })

            .onFailure(ctx::fail);
    }

    public void handleDelete(RoutingContext ctx)
    {
        String userId = ctx.get("user.id") != null ? ctx.get("user.id") : ctx.get("userId");

        String hostId = ctx.pathParam("id");

        hostService.delete(hostId, userId)

            .onSuccess(deleted ->
            {
                if (!deleted)
                {
                    ctx.fail(new ApiException(404, "HOST_NOT_FOUND", "Host not found"));

                    return;
                }

                ctx.response()

                    .putHeader("Content-Type", "application/json")

                    .setStatusCode(200)

                    .end(new JsonObject().put("message", "Host deleted successfully").encode());
            })

            .onFailure(ctx::fail);
    }
}
