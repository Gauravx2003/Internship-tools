package com.motadata.websession.common.error;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ApiException extends RuntimeException
{

    private final int statusCode;

    private final String errorCode;

    public ApiException(int statusCode, String errorCode, String message)
    {
        super(message);

        this.statusCode = statusCode;

        this.errorCode = errorCode;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public String getErrorCode()
    {
        return errorCode;
    }

    public JsonObject toErrorJson(String requestId)
    {
        JsonObject errorObj = new JsonObject()

            .put("code", errorCode)

            .put("message", getMessage())

            //.put("details", new JsonArray())

            .put("requestId", requestId != null ? requestId : "");

        return new JsonObject().put("error", errorObj);
    }
}
