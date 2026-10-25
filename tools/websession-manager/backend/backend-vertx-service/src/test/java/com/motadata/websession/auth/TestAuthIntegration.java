package com.motadata.websession.auth;

import com.motadata.websession.modules.auth.handler.AuthHandler;
import com.motadata.websession.backend_vertx_service.MainVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
public class TestAuthIntegration
{

    private static final int PORT = 8888;

    private static final String HOST = "localhost";

    private WebClient client;

    @BeforeEach
    void setUp(Vertx vertx, VertxTestContext testContext)
    {
        client = WebClient.create(vertx, new WebClientOptions().setDefaultPort(PORT).setDefaultHost(HOST));

        vertx.deployVerticle(new MainVerticle())
            .onComplete(testContext.succeeding(id -> testContext.completeNow()));
    }

    @Test
    void testHealthCheck(Vertx vertx, VertxTestContext testContext)
    {
        client.get("/health")
            .send()
            .onComplete(testContext.succeeding(response ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(200, response.statusCode());

                    JsonObject body = response.bodyAsJsonObject();

                    Assertions.assertNotNull(body);

                    Assertions.assertEquals("UP", body.getString("status"));

                    testContext.completeNow();
                });
            }));
    }

    @Test
    void testCompleteAuthLifecycle(Vertx vertx, VertxTestContext testContext)
    {
        String uniqueUser = "testuser_" + System.currentTimeMillis();

        String password = "SecurePassword@123";

        JsonObject regPayload = new JsonObject()
            .put("username", uniqueUser)
            .put("password", password);

        // Step 1: Register new user
        client.post("/api/v1/auth/register")
            .sendJsonObject(regPayload)
            .compose(regResp ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(201, regResp.statusCode());

                    JsonObject userObj = regResp.bodyAsJsonObject().getJsonObject("user");

                    Assertions.assertNotNull(userObj);

                    Assertions.assertEquals(uniqueUser, userObj.getString("username"));
                });

                // Step 2: Attempt login with invalid password (must fail 401)
                JsonObject wrongLogin = new JsonObject()
                    .put("username", uniqueUser)
                    .put("password", "WrongPassword!999");

                return client.post("/api/v1/auth/login").sendJsonObject(wrongLogin);
            })
            .compose(wrongResp ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(401, wrongResp.statusCode());

                    JsonObject err = wrongResp.bodyAsJsonObject().getJsonObject("error");

                    Assertions.assertEquals("INVALID_CREDENTIALS", err.getString("code"));
                });

                // Step 3: Login with valid credentials
                JsonObject validLogin = new JsonObject()
                    .put("username", uniqueUser)
                    .put("password", password);

                return client.post("/api/v1/auth/login").sendJsonObject(validLogin);
            })
            .compose(loginResp ->
            {
                String token = loginResp.bodyAsJsonObject().getString("token");

                testContext.verify(() ->
                {
                    Assertions.assertEquals(200, loginResp.statusCode());

                    JsonObject userObj = loginResp.bodyAsJsonObject().getJsonObject("user");

                    Assertions.assertEquals(uniqueUser, userObj.getString("username"));

                    // Verify token is in response body
                    Assertions.assertNotNull(token);

                    Assertions.assertFalse(token.isEmpty());

                    // Verify session cookie was also set
                    String cookieHeader = loginResp.getHeader("Set-Cookie");

                    Assertions.assertNotNull(cookieHeader);

                    Assertions.assertTrue(cookieHeader.contains(AuthHandler.SESSION_COOKIE_NAME));
                });

                // Step 4: Call /auth/me with Bearer token header
                return client.get("/api/v1/auth/me")
                    .putHeader("Authorization", "Bearer " + token)
                    .send()
                    .compose(meResp ->
                    {
                        testContext.verify(() ->
                        {
                            Assertions.assertEquals(200, meResp.statusCode());

                            JsonObject meUser = meResp.bodyAsJsonObject().getJsonObject("user");

                            Assertions.assertEquals(uniqueUser, meUser.getString("username"));
                        });

                        // Step 5: Logout using Bearer token
                        return client.post("/api/v1/auth/logout")
                            .putHeader("Authorization", "Bearer " + token)
                            .send()
                            .compose(logoutResp ->
                            {
                                testContext.verify(() ->
                                {
                                    Assertions.assertEquals(200, logoutResp.statusCode());
                                });

                                // Step 6: Verify /auth/me now returns 401 after logout
                                return client.get("/api/v1/auth/me")
                                    .putHeader("Authorization", "Bearer " + token)
                                    .send();
                            });
                    });
            })
            .onComplete(testContext.succeeding(finalResp ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(401, finalResp.statusCode());

                    testContext.completeNow();
                });
            }));
    }

    @Test
    void testExplicitLogoutAndCloseAllWithCloseAllQueryParam(Vertx vertx, VertxTestContext testContext)
    {
        String uniqueUser = "closeall_" + System.currentTimeMillis();
        String password = "SecurePassword@123";

        JsonObject regPayload = new JsonObject()
            .put("username", uniqueUser)
            .put("password", password);

        // 1. Register & Login
        client.post("/api/v1/auth/register")
            .sendJsonObject(regPayload)
            .compose(regResp ->
            {
                Assertions.assertEquals(201, regResp.statusCode());
                return client.post("/api/v1/auth/login").sendJsonObject(regPayload);
            })
            .compose(loginResp ->
            {
                Assertions.assertEquals(200, loginResp.statusCode());
                String token = loginResp.bodyAsJsonObject().getString("token");

                // 2. Call POST /api/auth/logout?closeAll=true
                return client.post("/api/auth/logout?closeAll=true")
                    .putHeader("Authorization", "Bearer " + token)
                    .send()
                    .compose(logoutResp ->
                    {
                        testContext.verify(() ->
                        {
                            Assertions.assertEquals(200, logoutResp.statusCode());
                            String msg = logoutResp.bodyAsJsonObject().getString("message");
                            Assertions.assertTrue(msg.contains("closed all ongoing sessions"));
                        });

                        // 3. Verify /api/auth/me now returns 401
                        return client.get("/api/auth/me")
                            .putHeader("Authorization", "Bearer " + token)
                            .send();
                    });
            })
            .onComplete(testContext.succeeding(meResp ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertEquals(401, meResp.statusCode());
                    testContext.completeNow();
                });
            }));
    }
}
