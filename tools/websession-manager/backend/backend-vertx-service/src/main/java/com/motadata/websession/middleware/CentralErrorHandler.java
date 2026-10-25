package com.motadata.websession.middleware;

import com.motadata.websession.common.error.ApiException;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class CentralErrorHandler implements Handler<RoutingContext>
{

    @Override
    public void handle(RoutingContext ctx)
    {
        Throwable failure = ctx.failure();

        String requestId = ctx.get("requestId");

        if (requestId == null)
        {
            requestId = "";
        }

        if (failure instanceof ApiException apiException)
        {
            ctx.response()

                .setStatusCode(apiException.getStatusCode())

                .putHeader("Content-Type", "application/json")

                .end(apiException.toErrorJson(requestId).encode());

            return;
        }

        int statusCode = ctx.statusCode() > 0 ? ctx.statusCode() : 500;

        String errorMessage = failure != null && failure.getMessage() != null

            ? failure.getMessage()

            : "An unexpected internal error occurred";

        JsonObject errorObj = new JsonObject()

            .put("code", "INTERNAL_ERROR")

            .put("message", errorMessage)

            .put("details", new JsonArray())

            .put("requestId", requestId);

        JsonObject responseJson = new JsonObject().put("error", errorObj);

        ctx.response()

            .setStatusCode(statusCode)

            .putHeader("Content-Type", "application/json")

            .end(responseJson.encode());
    }
}
