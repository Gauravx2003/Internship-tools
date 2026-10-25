package com.motadata.websession.auth;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.auth.repository.SessionRepository;
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
import java.util.concurrent.atomic.AtomicBoolean;
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

        AtomicInteger findWithUserCallCount = new AtomicInteger(0);
        AtomicInteger updateLastAccessedCallCount = new AtomicInteger(0);
        AtomicBoolean sessionExists = new AtomicBoolean(true);

        UserRepository fakeUserRepo = new UserRepository()
        {
            @Override
            public Future<Optional<JsonObject>> findByUsername(String username)
            {
                return Future.succeededFuture(Optional.of(fakeUser));
            }

            @Override
            public Future<Optional<JsonObject>> findById(String id)
            {
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

            @Override
            public Future<Void> updateTokensValidAfter(String userId, Instant tokensValidAfter)
            {
                return Future.succeededFuture();
            }
        };

        SessionRepository fakeSessionRepo = new SessionRepository()
        {
            @Override
            public Future<JsonObject> createSession(JsonObject session)
            {
                sessionExists.set(true);
                return Future.succeededFuture(session);
            }

            @Override
            public Future<Optional<JsonObject>> findById(String id)
            {
                return Future.succeededFuture(Optional.empty());
            }

            @Override
            public Future<Optional<JsonObject>> findSessionWithUser(String id)
            {
                findWithUserCallCount.incrementAndGet();
                if (!sessionExists.get())
                {
                    return Future.succeededFuture(Optional.empty());
                }
                return Future.succeededFuture(Optional.of(new JsonObject()
                    .put("sessionId", id)
                    .put("expiresAt", Instant.now().plusSeconds(3600).toString())
                    .put("user", fakeUser)));
            }

            @Override
            public Future<Void> updateLastAccessed(String id)
            {
                updateLastAccessedCallCount.incrementAndGet();
                return Future.succeededFuture();
            }

            @Override
            public Future<Boolean> deleteById(String id)
            {
                sessionExists.set(false);
                return Future.succeededFuture(true);
            }

            @Override
            public Future<Integer> deleteExpired()
            {
                return Future.succeededFuture(0);
            }
        };

        AppConfig config = AppConfig.load();
        AuthServiceImpl authService = new AuthServiceImpl(vertx, fakeUserRepo, fakeSessionRepo, config);

        authService.login("cacheduser", password)
            .compose(loginResult ->
            {
                String token = loginResult.getString("token");
                Assertions.assertNotNull(token);

                // 1st authenticateSession call -> MUST hit memory cache, findWithUserCallCount remains 0
                return authService.authenticateSession(token)
                    .compose(user1 ->
                    {
                        Assertions.assertEquals("cacheduser", user1.getString("username"));
                        Assertions.assertEquals(0, findWithUserCallCount.get(), "Database must not be queried on cache hit");
                        Assertions.assertEquals(0, updateLastAccessedCallCount.get(), "Throttled: DB write must not happen on immediate hit");

                        // 2nd authenticateSession call -> MUST still hit memory cache without DB query
                        return authService.authenticateSession(token);
                    })
                    .compose(user2 ->
                    {
                        Assertions.assertEquals("cacheduser", user2.getString("username"));
                        Assertions.assertEquals(0, findWithUserCallCount.get(), "Database must still not be queried");
                        Assertions.assertEquals(0, updateLastAccessedCallCount.get());

                        // Logout -> revokes token via denylist
                        return authService.logout(token)
                            .compose(v -> authService.authenticateSession(token))
                            .map(u -> false)
                            .recover(err ->
                            {
                                // Session is invalid/revoked now
                                return Future.succeededFuture(true);
                            });
                    });
            })
            .onComplete(testContext.succeeding(res ->
            {
                testContext.verify(() -> Assertions.assertTrue(res));
                testContext.completeNow();
            }));
    }
}
