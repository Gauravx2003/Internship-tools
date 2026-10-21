package com.motadata.websession.auth.service;

import com.motadata.websession.auth.repository.SessionRepository;
import com.motadata.websession.auth.repository.UserRepository;
import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.config.AppConfig;
import com.motadata.websession.crypto.PasswordHasher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

public class AuthServiceImpl implements AuthService
{

    private final Vertx vertx;

    private final UserRepository userRepository;

    private final SessionRepository sessionRepository;

    private final AppConfig config;

    private final SecureRandom secureRandom;

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        SessionRepository sessionRepository,
        AppConfig config)
    {
        this.vertx = vertx;

        this.userRepository = userRepository;

        this.sessionRepository = sessionRepository;

        this.config = config;

        this.secureRandom = new SecureRandom();
    }

    @Override
    public Future<JsonObject> login(String username, String password)
    {
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty())
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Username and password are required")
            );
        }

        String cleanUsername = username.trim();

        return userRepository.findByUsername(cleanUsername)

            .compose(userOpt ->
            {
                if (userOpt.isEmpty())
                {
                    return Future.failedFuture(
                        new ApiException(401, "INVALID_CREDENTIALS", "Invalid username or password")
                    );
                }

                JsonObject user = userOpt.get();

                String passwordHash = user.getString("passwordHash");

                return vertx.executeBlocking(() -> PasswordHasher.verify(password, passwordHash))

                    .compose(matches ->
                    {
                        if (!matches)
                        {
                            return Future.failedFuture(
                                new ApiException(401, "INVALID_CREDENTIALS", "Invalid username or password")
                            );
                        }

                        String sessionId = generateSecureSessionId();

                        Instant now = Instant.now();

                        Instant expiresAt = now.plus(config.getSessionTimeoutMinutes(), ChronoUnit.MINUTES);

                        JsonObject newSession = new JsonObject()

                            .put("id", sessionId)

                            .put("userId", user.getString("id"))

                            .put("createdAt", now.toString())

                            .put("expiresAt", expiresAt.toString())

                            .put("lastAccessedAt", now.toString());

                        return sessionRepository.createSession(newSession)

                            .map(savedSession ->
                            {
                                JsonObject safeUser = new JsonObject()

                                    .put("id", user.getString("id"))

                                    .put("username", user.getString("username"))

                                    .put("createdAt", user.getString("createdAt"));

                                return new JsonObject()

                                    .put("user", safeUser)

                                    .put("token", sessionId);
                            });
                    });
            });
    }

    @Override
    public Future<Void> logout(String sessionId)
    {
        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.succeededFuture();
        }

        return sessionRepository.deleteById(sessionId.trim())

            .mapEmpty();
    }

    @Override
    public Future<JsonObject> authenticateSession(String sessionId)
    {
        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.failedFuture(
                new ApiException(401, "UNAUTHORIZED", "Authentication required")
            );
        }

        return sessionRepository.findById(sessionId.trim())

            .compose(sessionOpt ->
            {
                if (sessionOpt.isEmpty())
                {
                    return Future.failedFuture(
                        new ApiException(401, "UNAUTHORIZED", "Session expired or invalid")
                    );
                }

                JsonObject session = sessionOpt.get();

                String expiresAtStr = session.getString("expiresAt");

                if (expiresAtStr != null && Instant.now().isAfter(Instant.parse(expiresAtStr)))
                {
                    return sessionRepository.deleteById(session.getString("id"))

                        .compose(v -> Future.failedFuture(
                            new ApiException(401, "UNAUTHORIZED", "Session has expired")
                        ));
                }

                sessionRepository.updateLastAccessed(session.getString("id"));

                return userRepository.findById(session.getString("userId"))

                    .compose(userOpt ->
                    {
                        if (userOpt.isEmpty())
                        {
                            return Future.failedFuture(
                                new ApiException(401, "UNAUTHORIZED", "User not found")
                            );
                        }

                        JsonObject user = userOpt.get();

                        JsonObject safeUser = new JsonObject()

                            .put("id", user.getString("id"))

                            .put("username", user.getString("username"))

                            .put("createdAt", user.getString("createdAt"));

                        return Future.succeededFuture(safeUser);
                    });
            });
    }

    @Override
    public Future<JsonObject> register(String username, String password)
    {
        if (username == null || username.trim().isEmpty() || password == null || password.length() < 8)
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Username is required and password must be at least 8 characters")
            );
        }

        String cleanUsername = username.trim();

        return userRepository.findByUsername(cleanUsername)

            .compose(existingOpt ->
            {
                if (existingOpt.isPresent())
                {
                    return Future.failedFuture(
                        new ApiException(409, "USER_ALREADY_EXISTS", "Username already exists")
                    );
                }

                return vertx.executeBlocking(() -> PasswordHasher.hash(password))

                    .compose(hashedPassword ->
                    {
                        String userId = UUID.randomUUID().toString();

                        Instant now = Instant.now();

                        JsonObject newUser = new JsonObject()

                            .put("id", userId)

                            .put("username", cleanUsername)

                            .put("passwordHash", hashedPassword)

                            .put("createdAt", now.toString())

                            .put("updatedAt", now.toString());

                        return userRepository.create(newUser)

                            .map(createdUser -> new JsonObject()

                                .put("id", createdUser.getString("id"))

                                .put("username", createdUser.getString("username"))

                                .put("createdAt", createdUser.getString("createdAt"))
                            );
                    });
            });
    }

    private String generateSecureSessionId()
    {
        byte[] bytes = new byte[32];

        secureRandom.nextBytes(bytes);

        return HexFormat.of().formatHex(bytes);
    }
}
