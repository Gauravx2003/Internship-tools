package com.motadata.websession.auth;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.auth.repository.InMemoryRefreshTokenRepository;
import com.motadata.websession.modules.auth.repository.RefreshTokenRepository;
import com.motadata.websession.modules.auth.repository.UserRepository;
import com.motadata.websession.modules.auth.service.AuthServiceImpl;
import com.motadata.websession.modules.crypto.PasswordHasher;
import com.motadata.websession.modules.terminal.registry.InMemoryTerminalSessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(VertxExtension.class)
public class TestRefreshTokenRotation
{

    private AuthServiceImpl authService;
    private RefreshTokenRepository refreshTokenRepo;
    private JsonObject mockUser;
    private final String password = "Password@123";

    @BeforeEach
    void setUp(Vertx vertx)
    {
        mockUser = new JsonObject()
            .put("id", UUID.randomUUID().toString())
            .put("username", "rotation_tester")
            .put("passwordHash", PasswordHasher.hash(password))
            .put("created.at", Instant.now().toString());

        UserRepository fakeUserRepo = new UserRepository()
        {
            @Override
            public Future<Optional<JsonObject>> findByUsername(String username)
            {
                if (mockUser.getString("username").equals(username))
                {
                    return Future.succeededFuture(Optional.of(mockUser));
                }
                return Future.succeededFuture(Optional.empty());
            }

            @Override
            public Future<Optional<JsonObject>> findById(String id)
            {
                if (mockUser.getString("id").equals(id))
                {
                    return Future.succeededFuture(Optional.of(mockUser));
                }
                return Future.succeededFuture(Optional.empty());
            }

            @Override
            public Future<JsonObject> create(JsonObject user)
            {
                return Future.succeededFuture(user);
            }

            @Override
            public Future<Long> countUsers()
            {
                return Future.succeededFuture(1L);
            }
        };

        refreshTokenRepo = new InMemoryRefreshTokenRepository();
        AppConfig config = AppConfig.load();
        authService = new AuthServiceImpl(
            vertx,
            fakeUserRepo,
            refreshTokenRepo,
            new InMemoryTerminalSessionRegistry(vertx, config),
            config
        );
    }

    @Test
    void testLoginGeneratesAccessAndRefreshToken(Vertx vertx, VertxTestContext testContext)
    {
        authService.login("rotation_tester", password)
            .onComplete(testContext.succeeding(result ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertNotNull(result.getString("accessToken"));
                    Assertions.assertNotNull(result.getString("token"));
                    Assertions.assertNotNull(result.getString("refreshToken"));
                    Assertions.assertEquals("rotation_tester", result.getJsonObject("user").getString("username"));

                    // Verify refresh token is saved in DB repository
                    refreshTokenRepo.findByToken(result.getString("refreshToken"))
                        .onComplete(testContext.succeeding(tokenOpt ->
                        {
                            testContext.verify(() ->
                            {
                                Assertions.assertTrue(tokenOpt.isPresent());
                                Assertions.assertEquals(mockUser.getString("id"), tokenOpt.get().getString("userId"));
                                testContext.completeNow();
                            });
                        }));
                });
            }));
    }

    @Test
    void testRefreshTokenRotationInvalidatesOldToken(Vertx vertx, VertxTestContext testContext)
    {
        authService.login("rotation_tester", password)
            .compose(loginResult ->
            {
                String initialRefreshToken = loginResult.getString("refreshToken");

                // 1st Refresh: Must succeed and yield rotated token
                return authService.refreshToken(initialRefreshToken)
                    .compose(firstRefreshResult ->
                    {
                        String rotatedRefreshToken = firstRefreshResult.getString("refreshToken");
                        String rotatedAccessToken = firstRefreshResult.getString("accessToken");

                        Assertions.assertNotNull(rotatedAccessToken);
                        Assertions.assertNotNull(rotatedRefreshToken);
                        Assertions.assertNotEquals(initialRefreshToken, rotatedRefreshToken, "Token must be rotated");

                        // Verify old token was removed from DB
                        return refreshTokenRepo.findByToken(initialRefreshToken)
                            .compose(oldOpt ->
                            {
                                Assertions.assertTrue(oldOpt.isEmpty(), "Old token must be deleted from repository");

                                // Attempt to reuse OLD token -> MUST FAIL (401)
                                return authService.refreshToken(initialRefreshToken)
                                    .map(r -> false)
                                    .recover(err -> Future.succeededFuture(true))
                                    .compose(oldFailed ->
                                    {
                                        Assertions.assertTrue(oldFailed, "Reused token must be rejected");

                                        // New rotated token must succeed
                                        return authService.refreshToken(rotatedRefreshToken);
                                    });
                            });
                    });
            })
            .onComplete(testContext.succeeding(secondRefreshResult ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertNotNull(secondRefreshResult.getString("accessToken"));
                    Assertions.assertNotNull(secondRefreshResult.getString("refreshToken"));
                    testContext.completeNow();
                });
            }));
    }

    @Test
    void testExpiredRefreshTokenIsRejectedAndCleanedUp(Vertx vertx, VertxTestContext testContext)
    {
        String expiredTokenStr = "expired_token_12345";
        JsonObject expiredRecord = new JsonObject()
            .put("id", UUID.randomUUID().toString())
            .put("userId", mockUser.getString("id"))
            .put("token", expiredTokenStr)
            .put("expiresAt", Instant.now().minus(1, ChronoUnit.HOURS).toString())
            .put("createdAt", Instant.now().minus(8, ChronoUnit.DAYS).toString());

        refreshTokenRepo.create(expiredRecord)
            .compose(v -> authService.refreshToken(expiredTokenStr))
            .onComplete(testContext.failing(err ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertTrue(err.getMessage().contains("Refresh token has expired"));

                    // Verify expired token was cleaned up from DB
                    refreshTokenRepo.findByToken(expiredTokenStr)
                        .onComplete(testContext.succeeding(opt ->
                        {
                            testContext.verify(() ->
                            {
                                Assertions.assertTrue(opt.isEmpty(), "Expired token must be cleaned up");
                                testContext.completeNow();
                            });
                        }));
                });
            }));
    }

    @Test
    void testLogoutRemovesRefreshToken(Vertx vertx, VertxTestContext testContext)
    {
        authService.login("rotation_tester", password)
            .compose(loginResult ->
            {
                String accessToken = loginResult.getString("accessToken");
                String refreshToken = loginResult.getString("refreshToken");

                // Logout by passing refresh token
                return authService.logout(accessToken, refreshToken)
                    .compose(v -> authService.refreshToken(refreshToken));
            })
            .onComplete(testContext.failing(err ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertTrue(err.getMessage().contains("Invalid or expired refresh token"));
                    testContext.completeNow();
                });
            }));
    }
}
