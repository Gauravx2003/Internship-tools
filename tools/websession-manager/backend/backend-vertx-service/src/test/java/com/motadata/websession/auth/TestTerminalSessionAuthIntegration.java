package com.motadata.websession.auth;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.auth.repository.UserRepository;
import com.motadata.websession.modules.auth.service.AuthServiceImpl;
import com.motadata.websession.modules.crypto.PasswordHasher;
import com.motadata.websession.modules.terminal.model.TerminalSession;
import com.motadata.websession.modules.terminal.registry.InMemoryTerminalSessionRegistry;
import com.motadata.websession.modules.terminal.registry.TerminalSessionRegistry;
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

@ExtendWith(VertxExtension.class)
public class TestTerminalSessionAuthIntegration
{

    @Test
    void testTerminalSessionRegistryUserMapping(Vertx vertx, VertxTestContext testContext)
    {
        AppConfig config = AppConfig.load();
        TerminalSessionRegistry registry = new InMemoryTerminalSessionRegistry(vertx, config);

        String userId1 = "user-abc-1";
        String userId2 = "user-xyz-2";

        TerminalSession s1 = new TerminalSession("term-1", userId1);
        TerminalSession s2 = new TerminalSession("term-2", userId1);
        TerminalSession s3 = new TerminalSession("term-3", userId2);

        registry.registerSession(s1);
        registry.registerSession(s2);
        registry.registerSession(s3);

        // Verify sessions associated to a single user can be known via in-map
        Assertions.assertEquals(2, registry.getActiveSessionCountForUser(userId1));
        Assertions.assertEquals(1, registry.getActiveSessionCountForUser(userId2));
        Assertions.assertEquals(3, registry.getTotalActiveSessionCount());

        Assertions.assertTrue(registry.getSessionsForUser(userId1).contains(s1));
        Assertions.assertTrue(registry.getSessionsForUser(userId1).contains(s2));
        Assertions.assertFalse(registry.getSessionsForUser(userId1).contains(s3));

        // Unregister one session
        registry.unregisterSession("term-1");
        Assertions.assertEquals(1, registry.getActiveSessionCountForUser(userId1));
        Assertions.assertEquals(2, registry.getTotalActiveSessionCount());

        testContext.completeNow();
    }

    @Test
    void testTokenExpirationDoesNotAffectOngoingTerminalSessions(Vertx vertx, VertxTestContext testContext)
    {
        String password = "StrongPassword@123";
        String hash = PasswordHasher.hash(password);
        String userId = "user-exp-test";

        JsonObject fakeUser = new JsonObject()
            .put("id", userId)
            .put("username", "expuser")
            .put("passwordHash", hash)
            .put("created.at", Instant.now().toString());

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
        };

        AppConfig config = AppConfig.load();
        TerminalSessionRegistry registry = new InMemoryTerminalSessionRegistry(vertx, config);
        AuthServiceImpl authService = new AuthServiceImpl(vertx, fakeUserRepo, registry, config);

        // Register active live terminal session for the user
        String termSessionId = "active-ssh-term-999";
        TerminalSession activeTerminal = new TerminalSession(termSessionId, userId);
        registry.registerSession(activeTerminal);

        Assertions.assertEquals(1, registry.getActiveSessionCountForUser(userId));

        // Unknown or expired token fails with 401
        String expiredToken = "expired-token-xyz";
        authService.authenticateSession(expiredToken)
            .onComplete(ar ->
            {
                testContext.verify(() ->
                {
                    // Authentication fails with 401
                    Assertions.assertTrue(ar.failed(), "Expired token must be rejected with 401");

                    // Crucial: live terminal sessions in registry must NOT be affected!
                    Assertions.assertEquals(1, registry.getActiveSessionCountForUser(userId),
                        "Ongoing terminal sessions must NOT be affected by token expiration");
                    Assertions.assertTrue(registry.getSession(termSessionId).isPresent());

                    testContext.completeNow();
                });
            });
    }

    @Test
    void testExplicitLogoutAndCloseSessionsClosesAllOngoingSessions(Vertx vertx, VertxTestContext testContext)
    {
        String password = "StrongPassword@123";
        String hash = PasswordHasher.hash(password);
        String userId = "user-logout-test";

        JsonObject fakeUser = new JsonObject()
            .put("id", userId)
            .put("username", "logoutuser")
            .put("passwordHash", hash)
            .put("created.at", Instant.now().toString());

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
        };

        AppConfig config = AppConfig.load();
        TerminalSessionRegistry registry = new InMemoryTerminalSessionRegistry(vertx, config);
        AuthServiceImpl authService = new AuthServiceImpl(vertx, fakeUserRepo, registry, config);

        AtomicBoolean callback1Closed = new AtomicBoolean(false);
        AtomicBoolean callback2Closed = new AtomicBoolean(false);

        TerminalSession term1 = new TerminalSession("term-101", userId, "host-1", "192.168.1.10", reason -> callback1Closed.set(true));
        TerminalSession term2 = new TerminalSession("term-102", userId, "host-2", "192.168.1.20", reason -> callback2Closed.set(true));

        registry.registerSession(term1);
        registry.registerSession(term2);

        Assertions.assertEquals(2, registry.getActiveSessionCountForUser(userId));

        authService.login("logoutuser", password)
            .compose(loginResult ->
            {
                String accessToken = loginResult.getString("accessToken");
                String refreshToken = loginResult.getString("refreshToken");

                // Explicit 'logout and close all'
                return authService.logoutAndCloseSessions(accessToken, refreshToken)
                    .compose(v -> authService.refreshToken(refreshToken).map(u -> false).recover(err -> Future.succeededFuture(true)));
            })
            .onComplete(testContext.succeeding(refreshInvalidated ->
            {
                testContext.verify(() ->
                {
                    // 1. Refresh token is revoked
                    Assertions.assertTrue(refreshInvalidated, "Refresh token must be revoked after logout");

                    // 2. In-map terminal sessions for this user are completely cleared
                    Assertions.assertEquals(0, registry.getActiveSessionCountForUser(userId),
                        "In-map sessions for user must be cleared");
                    Assertions.assertEquals(0, registry.getTotalActiveSessionCount());

                    // 3. Both terminal session callbacks were executed
                    Assertions.assertTrue(callback1Closed.get(), "Terminal 1 callback must be executed");
                    Assertions.assertTrue(callback2Closed.get(), "Terminal 2 callback must be executed");

                    testContext.completeNow();
                });
            }));
    }

    @Test
    void testPlainLogoutLeavesOngoingTerminalSessionsOpen(Vertx vertx, VertxTestContext testContext)
    {
        String password = "StrongPassword@123";
        String hash = PasswordHasher.hash(password);
        String userId = "user-std-logout";

        JsonObject fakeUser = new JsonObject()
            .put("id", userId)
            .put("username", "stduser")
            .put("passwordHash", hash)
            .put("created.at", Instant.now().toString());

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
        };

        AppConfig config = AppConfig.load();
        TerminalSessionRegistry registry = new InMemoryTerminalSessionRegistry(vertx, config);
        AuthServiceImpl authService = new AuthServiceImpl(vertx, fakeUserRepo, registry, config);

        AtomicBoolean callbackClosed = new AtomicBoolean(false);
        TerminalSession term = new TerminalSession("term-std-1", userId, "host-1", "10.0.0.1", reason -> callbackClosed.set(true));
        registry.registerSession(term);

        authService.login("stduser", password)
            .compose(loginResult ->
            {
                String accessToken = loginResult.getString("accessToken");
                String refreshToken = loginResult.getString("refreshToken");

                // Plain logout (closeAll = false)
                return authService.logout(accessToken, refreshToken)
                    .compose(v -> authService.refreshToken(refreshToken).map(u -> false).recover(err -> Future.succeededFuture(true)));
            })
            .onComplete(testContext.succeeding(refreshFailed ->
            {
                testContext.verify(() ->
                {
                    // Refresh token is revoked from database
                    Assertions.assertTrue(refreshFailed, "Refresh token must be invalidated after plain logout");

                    // Crucially, live terminal session is STILL ACTIVE
                    Assertions.assertEquals(1, registry.getActiveSessionCountForUser(userId),
                        "Plain logout must NOT close ongoing terminal sessions");
                    Assertions.assertFalse(callbackClosed.get(), "Terminal callback must not be invoked");

                    testContext.completeNow();
                });
            }));
    }

    @Test
    void testRefreshTokenRotationAndRejectionAfterLogout(Vertx vertx, VertxTestContext testContext)
    {
        String password = "StrongPassword@123";
        String hash = PasswordHasher.hash(password);
        String userId = "user-tva-test";

        JsonObject fakeUser = new JsonObject()
            .put("id", userId)
            .put("username", "tvauser")
            .put("passwordHash", hash)
            .put("created.at", Instant.now().toString());

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
        };

        AppConfig config = AppConfig.load();
        TerminalSessionRegistry registry = new InMemoryTerminalSessionRegistry(vertx, config);
        AuthServiceImpl authService = new AuthServiceImpl(vertx, fakeUserRepo, registry, config);

        authService.login("tvauser", password)
            .compose(loginResult ->
            {
                String accessToken = loginResult.getString("accessToken");
                String refreshToken = loginResult.getString("refreshToken");

                // 1. Authenticate session with access token works
                return authService.authenticateSession(accessToken)
                    .compose(u ->
                    {
                        Assertions.assertEquals("tvauser", u.getString("username"));

                        // 2. Refresh token rotation: exchange old refresh token for new access + refresh tokens
                        return authService.refreshToken(refreshToken);
                    })
                    .compose(rotated ->
                    {
                        String rotatedAccess = rotated.getString("accessToken");
                        String rotatedRefresh = rotated.getString("refreshToken");

                        Assertions.assertNotNull(rotatedAccess);
                        Assertions.assertNotNull(rotatedRefresh);
                        Assertions.assertNotEquals(refreshToken, rotatedRefresh, "Refresh token must be rotated");

                        // 3. Trying to reuse OLD refresh token must fail with 401
                        return authService.refreshToken(refreshToken)
                            .map(u -> false)
                            .recover(err -> Future.succeededFuture(true))
                            .compose(oldTokenRejected ->
                            {
                                Assertions.assertTrue(oldTokenRejected, "Old rotated refresh token must be rejected");

                                // 4. Logout and close sessions using the rotated refresh token
                                return authService.logoutAndCloseSessions(rotatedAccess, rotatedRefresh)
                                    .compose(v -> authService.refreshToken(rotatedRefresh))
                                    .map(u -> false)
                                    .recover(err -> Future.succeededFuture(true));
                            });
                    });
            })
            .onComplete(testContext.succeeding(rejected ->
            {
                testContext.verify(() ->
                {
                    Assertions.assertTrue(rejected, "Token revoked by logoutAndCloseSessions must be rejected");
                    testContext.completeNow();
                });
            }));
    }
}
