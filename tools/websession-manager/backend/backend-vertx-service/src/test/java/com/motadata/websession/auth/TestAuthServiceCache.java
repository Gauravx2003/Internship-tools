package com.motadata.websession.auth;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.auth.repository.InMemoryRefreshTokenRepository;
import com.motadata.websession.modules.auth.repository.RefreshTokenRepository;
import com.motadata.websession.modules.auth.repository.UserRepository;
import com.motadata.websession.modules.auth.service.AuthServiceImpl;
import com.motadata.websession.modules.crypto.PasswordHasher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

@ExtendWith(VertxExtension.class)
public class TestAuthServiceCache
{

    @Test
    void testSessionCachedAfterLoginAndAvoidsDbQuery(Vertx vertx, VertxTestContext testContext)
    {
        String password = "TestPassword@123";
        String hash = PasswordHasher.hash(password);

        JsonObject fakeUser = new JsonObject()
            .put("id", "user-123")
            .put("username", "cacheduser")
            .put("passwordHash", hash)
            .put("created.at", Instant.now().toString());

        AtomicInteger userRepoFindCallCount = new AtomicInteger(0);

        UserRepository fakeUserRepo = new UserRepository()
        {
            @Override
            public Future<Optional<JsonObject>> findByUsername(String username)
            {
                userRepoFindCallCount.incrementAndGet();
                return Future.succeededFuture(Optional.of(fakeUser));
            }

            @Override
            public Future<Optional<JsonObject>> findById(String id)
            {
                userRepoFindCallCount.incrementAndGet();
                return Future.succeededFuture(Optional.of(fakeUser));
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

        RefreshTokenRepository refreshTokenRepo = new InMemoryRefreshTokenRepository();
        AppConfig config = AppConfig.load();
        AuthServiceImpl authService = new AuthServiceImpl(vertx, fakeUserRepo, refreshTokenRepo, config);

        authService.login("cacheduser", password)
            .compose(loginResult ->
            {
                String accessToken = loginResult.getString("accessToken");
                String refreshToken = loginResult.getString("refreshToken");

                Assertions.assertNotNull(accessToken);
                Assertions.assertNotNull(refreshToken);

                int callsAfterLogin = userRepoFindCallCount.get();

                // 1st authenticateSession call -> Access token is verified statelessly, user repo find count does not increment
                return authService.authenticateSession(accessToken)
                    .compose(user1 ->
                    {
                        Assertions.assertEquals("cacheduser", user1.getString("username"));
                        Assertions.assertEquals(callsAfterLogin, userRepoFindCallCount.get(), "Database must not be queried on stateless access token verification");

                        // 2nd authenticateSession call -> Still verified statelessly without DB query
                        return authService.authenticateSession(accessToken);
                    })
                    .compose(user2 ->
                    {
                        Assertions.assertEquals("cacheduser", user2.getString("username"));
                        Assertions.assertEquals(callsAfterLogin, userRepoFindCallCount.get(), "Database must still not be queried");

                        // Refresh token rotation: exchange refreshToken for new pair
                        return authService.refreshToken(refreshToken);
                    })
                    .compose(refreshResult ->
                    {
                        String newAccessToken = refreshResult.getString("accessToken");
                        String newRefreshToken = refreshResult.getString("refreshToken");

                        Assertions.assertNotNull(newAccessToken);
                        Assertions.assertNotNull(newRefreshToken);
                        Assertions.assertNotEquals(refreshToken, newRefreshToken);

                        // Old refresh token must now be revoked
                        return authService.refreshToken(refreshToken)
                            .map(u -> false)
                            .recover(err -> Future.succeededFuture(true))
                            .compose(oldRejected ->
                            {
                                Assertions.assertTrue(oldRejected, "Reusing old rotated refresh token must fail");

                                // Logout using new refresh token
                                return authService.logout(newAccessToken, newRefreshToken)
                                    .compose(v -> authService.refreshToken(newRefreshToken))
                                    .map(u -> false)
                                    .recover(err -> Future.succeededFuture(true));
                            });
                    });
            })
            .onComplete(testContext.succeeding(res ->
            {
                testContext.verify(() -> Assertions.assertTrue(res, "Revoked refresh token must fail after logout"));
                testContext.completeNow();
            }));
    }
}
