package com.motadata.websession.modules.auth.service;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.auth.repository.InMemoryRefreshTokenRepository;
import com.motadata.websession.modules.auth.repository.RefreshTokenRepository;
import com.motadata.websession.modules.auth.repository.SessionRepository;
import com.motadata.websession.modules.auth.repository.UserRepository;
import com.motadata.websession.modules.crypto.PasswordHasher;
import com.motadata.websession.modules.terminal.registry.InMemoryTerminalSessionRegistry;
import com.motadata.websession.modules.terminal.registry.TerminalSessionRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.JWTOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

public class AuthServiceImpl implements AuthService
{

    private final Vertx vertx;

    private final UserRepository userRepository;

    private final RefreshTokenRepository refreshTokenRepository;

    private final TerminalSessionRegistry terminalSessionRegistry;

    private final AppConfig config;

    private final SecureRandom secureRandom;

    private final JWTAuth jwtAuth;

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        TerminalSessionRegistry terminalSessionRegistry,
        AppConfig config)
    {
        this.vertx = vertx;

        this.userRepository = userRepository;

        this.refreshTokenRepository = refreshTokenRepository != null
            ? refreshTokenRepository
            : new InMemoryRefreshTokenRepository();

        this.terminalSessionRegistry = terminalSessionRegistry != null
            ? terminalSessionRegistry
            : new InMemoryTerminalSessionRegistry(vertx, config);

        this.config = config;

        this.secureRandom = new SecureRandom();

        JWTAuthOptions jwtAuthOptions = new JWTAuthOptions()
            .addPubSecKey(new PubSecKeyOptions()
                .setAlgorithm("HS256")
                .setBuffer(config.getEncryptionKey()));

        this.jwtAuth = JWTAuth.create(vertx, jwtAuthOptions);

        // Periodically purge expired refresh tokens from repository
        this.vertx.setPeriodic(60 * 60 * 1000L, id -> this.refreshTokenRepository.deleteExpired());
    }

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        AppConfig config)
    {
        this(vertx, userRepository, refreshTokenRepository, new InMemoryTerminalSessionRegistry(vertx, config), config);
    }

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        TerminalSessionRegistry terminalSessionRegistry,
        AppConfig config)
    {
        this(vertx, userRepository, new InMemoryRefreshTokenRepository(), terminalSessionRegistry, config);
    }

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        AppConfig config)
    {
        this(vertx, userRepository, new InMemoryRefreshTokenRepository(), new InMemoryTerminalSessionRegistry(vertx, config), config);
    }

    @Deprecated
    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        SessionRepository sessionRepository,
        TerminalSessionRegistry terminalSessionRegistry,
        AppConfig config)
    {
        this(vertx, userRepository, new InMemoryRefreshTokenRepository(), terminalSessionRegistry, config);
    }

    @Deprecated
    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        SessionRepository sessionRepository,
        AppConfig config)
    {
        this(vertx, userRepository, new InMemoryRefreshTokenRepository(), new InMemoryTerminalSessionRegistry(vertx, config), config);
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

                return vertx.executeBlocking(() -> PasswordHasher.verify(password, passwordHash), false)
                    .compose(matches ->
                    {
                        if (!matches)
                        {
                            return Future.failedFuture(
                                new ApiException(401, "INVALID_CREDENTIALS", "Invalid username or password")
                            );
                        }

                        String userId = user.getString("id");

                        JsonObject safeUser = new JsonObject()
                            .put("id", userId)
                            .put("username", user.getString("username"))
                            .put("created.at", user.getString("created.at"));

                        String accessToken = generateAccessToken(user);

                        String refreshToken = generateSecureToken();

                        Instant refreshExpiresAt = Instant.now().plus(config.getRefreshTokenTtlDays(), ChronoUnit.DAYS);

                        JsonObject rtRecord = new JsonObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("userId", userId)
                            .put("token", refreshToken)
                            .put("expiresAt", refreshExpiresAt.toString())
                            .put("createdAt", Instant.now().toString());

                        return refreshTokenRepository.create(rtRecord)
                            .map(saved -> new JsonObject()
                                .put("user", safeUser)
                                .put("accessToken", accessToken)
                                .put("refreshToken", refreshToken)
                            );
                    });
            });
    }

    @Override
    public Future<JsonObject> refreshToken(String refreshToken)
    {
        if (refreshToken == null || refreshToken.trim().isEmpty())
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Refresh token is required")
            );
        }

        String cleanToken = refreshToken.trim();

        return refreshTokenRepository.findByToken(cleanToken)
            .compose(tokenOpt ->
            {
                if (tokenOpt.isEmpty())
                {
                    return Future.failedFuture(
                        new ApiException(401, "UNAUTHORIZED", "Invalid or expired refresh token")
                    );
                }

                JsonObject record = tokenOpt.get();

                String expiresAtStr = record.getString("expiresAt");

                if (expiresAtStr == null)
                {
                    expiresAtStr = record.getString("expires_at");
                }

                Instant expiresAt = Instant.parse(expiresAtStr);

                if (Instant.now().isAfter(expiresAt))
                {
                    return refreshTokenRepository.deleteByToken(cleanToken)
                        .compose(v -> Future.failedFuture(
                            new ApiException(401, "UNAUTHORIZED", "Refresh token has expired")
                        ));
                }

                String userId = record.getString("userId");

                if (userId == null)
                {
                    userId = record.getString("user_id");
                }

                final String finalUserId = userId;

                // Rotate refresh token: Invalidate the used refresh token immediately upon use
                return refreshTokenRepository.deleteByToken(cleanToken)
                    .compose(deleted -> userRepository.findById(finalUserId))
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
                            .put("created.at", user.getString("created.at"));

                        String newAccessToken = generateAccessToken(user);

                        String newRefreshToken = generateSecureToken();

                        Instant newExpiresAt = Instant.now().plus(config.getRefreshTokenTtlDays(), ChronoUnit.DAYS);

                        JsonObject newRecord = new JsonObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("userId", user.getString("id"))
                            .put("token", newRefreshToken)
                            .put("expiresAt", newExpiresAt.toString())
                            .put("createdAt", Instant.now().toString());

                        return refreshTokenRepository.create(newRecord)
                            .map(saved -> new JsonObject()
                                .put("user", safeUser)
                                .put("token", newAccessToken)
                                .put("accessToken", newAccessToken)
                                .put("refreshToken", newRefreshToken)
                            );
                    });
            });
    }

    @Override
    public Future<Void> logout(String sessionId)
    {
        return logout(sessionId, null);
    }

    @Override
    public Future<Void> logout(String accessToken, String refreshToken)
    {
        Future<Void> deleteRt = Future.succeededFuture();

        if (refreshToken != null && !refreshToken.trim().isEmpty())
        {
            deleteRt = refreshTokenRepository.deleteByToken(refreshToken.trim()).mapEmpty();
        }

        if (accessToken != null && !accessToken.trim().isEmpty())
        {
            String cleanAccess = accessToken.trim();

            String userId = extractUserId(cleanAccess);

            if (userId != null)
            {
                return deleteRt.compose(v -> refreshTokenRepository.deleteByUserId(userId)).mapEmpty();
            }
            else if (refreshToken == null || refreshToken.trim().isEmpty())
            {
                // In case sessionId passed is a refresh token
                return refreshTokenRepository.deleteByToken(cleanAccess).mapEmpty();
            }
        }

        return deleteRt;
    }

    @Override
    public Future<Void> logoutAndCloseSessions(String sessionId)
    {
        return logoutAndCloseSessions(sessionId, null);
    }

    @Override
    public Future<Void> logoutAndCloseSessions(String accessToken, String refreshToken)
    {
        String userId = null;

        if (accessToken != null && !accessToken.trim().isEmpty())
        {
            userId = extractUserId(accessToken.trim());
        }

        if (userId != null)
        {
            final String finalUserId = userId;

            return refreshTokenRepository.deleteByUserId(finalUserId)
                .compose(v -> terminalSessionRegistry.closeAllSessionsForUser(finalUserId, "explicit_logout_close_all"))
                .mapEmpty();
        }

        // If userId was not found in accessToken, check if refreshToken provides userId
        String tokenToCheck = (refreshToken != null && !refreshToken.trim().isEmpty())
            ? refreshToken.trim()
            : (accessToken != null ? accessToken.trim() : null);

        if (tokenToCheck != null)
        {
            return refreshTokenRepository.findByToken(tokenToCheck)
                .compose(opt ->
                {
                    if (opt.isPresent())
                    {
                        JsonObject rec = opt.get();

                        String uid = rec.getString("userId", rec.getString("user_id"));

                        return refreshTokenRepository.deleteByUserId(uid)
                            .compose(v -> terminalSessionRegistry.closeAllSessionsForUser(uid, "explicit_logout_close_all"))
                            .mapEmpty();
                    }

                    return refreshTokenRepository.deleteByToken(tokenToCheck).mapEmpty();
                });
        }

        return Future.succeededFuture();
    }

    /**
     * Authenticates the access token statelessly without hitting the database.
     * Rejects expired or invalid tokens with 401 UNAUTHORIZED.
     */
    @Override
    public Future<JsonObject> authenticateSession(String sessionId)
    {
        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.failedFuture(
                new ApiException(401, "UNAUTHORIZED", "Authentication required")
            );
        }

        String cleanToken = sessionId.trim();

        return jwtAuth.authenticate(new TokenCredentials(cleanToken))
            .recover(err -> Future.failedFuture(
                new ApiException(401, "UNAUTHORIZED", "Session expired or invalid")
            ))
            .compose(user ->
            {
                JsonObject principal = user.principal();

                String type = principal.getString("type");

                if ("refresh".equalsIgnoreCase(type))
                {
                    return Future.failedFuture(
                        new ApiException(401, "UNAUTHORIZED", "Invalid token type")
                    );
                }

                String userId = principal.getString("sub");

                if (userId == null)
                {
                    userId = principal.getString("user.id");
                }

                JsonObject safeUser = new JsonObject()
                    .put("id", userId)
                    .put("username", principal.getString("username"))
                    .put("created.at", principal.getString("created.at"));

                return Future.succeededFuture(safeUser);
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
                            .put("password.hash", hashedPassword)
                            .put("created.at", now.toString())
                            .put("updated.at", now.toString());

                        return userRepository.create(newUser)
                            .map(createdUser -> new JsonObject()
                                .put("id", createdUser.getString("id"))
                                .put("username", createdUser.getString("username"))
                                .put("created.at", createdUser.getString("created.at"))
                            );
                    });
            });
    }

    @Override
    public TerminalSessionRegistry getTerminalSessionRegistry()
    {
        return this.terminalSessionRegistry;
    }

    private String generateAccessToken(JsonObject user)
    {
        String userId = user.getString("id");

        String jti = generateSecureToken();

        JsonObject claims = new JsonObject()
            .put("sub", userId)
            .put("user.id", userId)
            .put("username", user.getString("username"))
            .put("created.at", user.getString("created.at"))
            .put("jti", jti)
            .put("jit", jti)
            .put("type", "access");

        JWTOptions jwtOptions = new JWTOptions()
            .setExpiresInMinutes(config.getAccessTokenTtlMinutes());

        return jwtAuth.generateToken(claims, jwtOptions);
    }

    private String generateSecureToken()
    {
        byte[] bytes = new byte[32];

        secureRandom.nextBytes(bytes);

        return HexFormat.of().formatHex(bytes);
    }

    private JsonObject decodeJwtPayload(String token)
    {
        try
        {
            String[] parts = token.split("\\.");

            if (parts.length >= 2)
            {
                byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);

                return new JsonObject(new String(decoded, StandardCharsets.UTF_8));
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    private String extractJti(String token)
    {
        JsonObject payload = decodeJwtPayload(token);

        if (payload != null)
        {
            String jti = payload.getString("jti");

            if (jti == null)
            {
                jti = payload.getString("jit");
            }

            if (jti != null)
            {
                return jti;
            }
        }

        return token;
    }

    private String extractUserId(String token)
    {
        JsonObject payload = decodeJwtPayload(token);

        if (payload != null)
        {
            String sub = payload.getString("sub");

            if (sub != null)
            {
                return sub;
            }

            return payload.getString("userId");
        }

        return null;
    }

    private Instant extractExpiry(String token)
    {
        JsonObject payload = decodeJwtPayload(token);

        if (payload != null)
        {
            Long exp = payload.getLong("exp");

            if (exp != null)
            {
                return Instant.ofEpochSecond(exp);
            }
        }

        return Instant.now().plus(config.getAccessTokenTtlMinutes(), ChronoUnit.MINUTES);
    }
}
