package com.motadata.websession.middleware;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;

public class RequestIdMiddleware implements Handler<RoutingContext>
{

    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    public void handle(RoutingContext ctx)
    {
        String requestId = ctx.request().getHeader(HEADER_REQUEST_ID);

        if (requestId == null || requestId.trim().isEmpty())
        {
            requestId = UUID.randomUUID().toString();
        }

        ctx.put("requestId", requestId);

        ctx.response().putHeader(HEADER_REQUEST_ID, requestId);

        ctx.next();
    }
}
