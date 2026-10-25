package com.motadata.websession.modules.auth.service;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.config.AppConfig;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthServiceImpl implements AuthService
{

    private final Vertx vertx;

    private final UserRepository userRepository;

    private final TerminalSessionRegistry terminalSessionRegistry;

    private final AppConfig config;

    private final SecureRandom secureRandom;

    private final JWTAuth jwtAuth;

    // In-memory token revocation denylist: JTI / token -> expiry time
    private final Map<String, Instant> tokenDenylist = new ConcurrentHashMap<>();

    private static final long EXPIRED_CLEANUP_INTERVAL_MS = 60 * 1000; // 1 minute cleanup

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        TerminalSessionRegistry terminalSessionRegistry,
        AppConfig config)
    {
        this.vertx = vertx;

        this.userRepository = userRepository;

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

        // Background periodic cleaner for expired denylist entries.
        // Token expiration does not affect ongoing live terminal sessions.
        this.vertx.setPeriodic(EXPIRED_CLEANUP_INTERVAL_MS, id -> cleanExpired());
    }

    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        AppConfig config)
    {
        this(vertx, userRepository, new InMemoryTerminalSessionRegistry(vertx, config), config);
    }

    @Deprecated
    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        SessionRepository sessionRepository,
        TerminalSessionRegistry terminalSessionRegistry,
        AppConfig config)
    {
        this(vertx, userRepository, terminalSessionRegistry, config);
    }

    @Deprecated
    public AuthServiceImpl(
        Vertx vertx,
        UserRepository userRepository,
        SessionRepository sessionRepository,
        AppConfig config)
    {
        this(vertx, userRepository, new InMemoryTerminalSessionRegistry(vertx, config), config);
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

                        String jti = generateSecureSessionId();

                        JsonObject safeUser = new JsonObject()
                            .put("id", userId)
                            .put("username", user.getString("username"))
                            .put("created.at", user.getString("created.at"));

                        JsonObject claims = new JsonObject()
                            .put("sub", userId)
                            .put("userId", userId)
                            .put("username", user.getString("username"))
                            .put("created.at", user.getString("created.at"))
                            .put("jti", jti)
                            .put("jit", jti);

                        JWTOptions jwtOptions = new JWTOptions()
                            .setExpiresInMinutes(config.getTokenTtlMinutes());

                        String token = jwtAuth.generateToken(claims, jwtOptions);

                        return Future.succeededFuture(new JsonObject()
                            .put("user", safeUser)
                            .put("token", token));
                    });
            });
    }

    /**
     * Plain logout (closeAll = false):
     * Revokes the token only via in-memory denylist tracking JTI.
     * Ongoing live terminal sessions keep running.
     */
    @Override
    public Future<Void> logout(String sessionId)
    {
        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.succeededFuture();
        }

        String cleanToken = sessionId.trim();

        String jti = extractJti(cleanToken);

        Instant expiry = extractExpiry(cleanToken);

        String userId = extractUserId(cleanToken);

        tokenDenylist.put(cleanToken, expiry);

        if (jti != null)
        {
            tokenDenylist.put(jti, expiry);
        }

        System.out.println("[AUDIT] Plain logout: revoked token (jti=" + jti
            + ") for user " + (userId != null ? userId : "unknown") + ". Live terminals keep running.");

        return Future.succeededFuture();
    }

    /**
     * Explicit "logout and close all" (closeAll = true):
     * Revokes the token via in-memory denylist tracking JTI,
     * closes ALL live terminal sessions of that user, and logs audit events.
     */
    @Override
    public Future<Void> logoutAndCloseSessions(String sessionId)
    {
        if (sessionId == null || sessionId.trim().isEmpty())
        {
            return Future.succeededFuture();
        }

        String cleanToken = sessionId.trim();

        String jti = extractJti(cleanToken);

        Instant expiry = extractExpiry(cleanToken);

        String userId = extractUserId(cleanToken);

        tokenDenylist.put(cleanToken, expiry);

        if (jti != null)
        {
            tokenDenylist.put(jti, expiry);
        }

        if (userId != null)
        {
            final String uid = userId;

            final String finalJti = jti;

            // Close ALL live terminal sessions of that user
            return terminalSessionRegistry.closeAllSessionsForUser(uid, "explicit_logout_close_all")
                .onSuccess(count ->
                {
                    System.out.println("[AUDIT] User " + uid
                        + " executed 'logout and close all': token revoked (jti=" + finalJti + "), and "
                        + count + " live terminal session(s) closed.");
                })
                .mapEmpty();
        }

        return Future.succeededFuture();
    }

    /**
     * Authenticates the login JWT token without hitting the database on requests.
     * Rejects revoked or expired tokens with 401 UNAUTHORIZED.
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

        // Check if raw token string is in denylist
        if (tokenDenylist.containsKey(cleanToken))
        {
            return Future.failedFuture(
                new ApiException(401, "UNAUTHORIZED", "Session has been revoked")
            );
        }

        return jwtAuth.authenticate(new TokenCredentials(cleanToken))
            .recover(err -> Future.failedFuture(
                new ApiException(401, "UNAUTHORIZED", "Session expired or invalid")
            ))
            .compose(user ->
            {
                JsonObject principal = user.principal();

                String jti = principal.getString("jti");

                if (jti == null)
                {
                    jti = principal.getString("jit");
                }

                // Check denylist for JTI
                if (jti != null && tokenDenylist.containsKey(jti))
                {
                    return Future.failedFuture(
                        new ApiException(401, "UNAUTHORIZED", "Session has been revoked")
                    );
                }

                String userId = principal.getString("sub");

                if (userId == null)
                {
                    userId = principal.getString("userId");
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
                            .put("passwordHash", hashedPassword)
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

    private void cleanExpired()
    {
        Instant now = Instant.now();

        // Clean expired denylist entries; ongoing live terminal sessions are NOT affected
        tokenDenylist.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
    }

    private String generateSecureSessionId()
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

        return Instant.now().plus(config.getTokenTtlMinutes(), ChronoUnit.MINUTES);
    }
}
