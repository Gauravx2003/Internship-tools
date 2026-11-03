package com.motadata.websession.modules.auth.service;

import com.motadata.websession.common.error.ApiException;

import com.motadata.websession.common.error.ErrorCodes;

import com.motadata.websession.config.AppConfig;

import com.motadata.websession.modules.auth.repository.RefreshTokenRepository;

import com.motadata.websession.modules.auth.repository.UserRepository;

import com.motadata.websession.modules.crypto.PasswordHasher;

import com.motadata.websession.modules.terminal.registry.TerminalSessionRegistry;

import io.vertx.core.Future;

import io.vertx.core.Vertx;

import io.vertx.core.json.JsonObject;

import io.vertx.ext.auth.JWTOptions;

import io.vertx.ext.auth.PubSecKeyOptions;

import io.vertx.ext.auth.authentication.TokenCredentials;

import io.vertx.ext.auth.jwt.JWTAuth;

import io.vertx.ext.auth.jwt.JWTAuthOptions;

import java.security.SecureRandom;

import java.time.Instant;

import java.time.temporal.ChronoUnit;

import java.util.HexFormat;

import java.util.Map;

import java.util.Objects;

import java.util.Optional;

import java.util.UUID;

import java.util.concurrent.ConcurrentHashMap;

public class AuthServiceImpl implements AuthService
{

  private static final String CLOSE_ALL_REASON = "explicit_logout_close_all";

  private final Vertx vertx;

  private final UserRepository userRepository;

  private final RefreshTokenRepository refreshTokenRepository;

  private final TerminalSessionRegistry terminalSessionRegistry;

  private final AppConfig config;

  private final SecureRandom secureRandom;

  private final JWTAuth jwtAuth;

  /**

   * userId -> time of the last "logout and close all".
   * Any access token issued before this time (iat) is rejected.
   * Entries are dropped once every older access token has expired anyway, so the map stays small.
   * In memory on purpose: terminals live in memory too, so a restart ends them as well.

   */

  private final Map<String, Instant> tokensValidAfter = new ConcurrentHashMap<>();

  /**

   * Single constructor. Everything is required, so a wrong wiring fails at startup
   * instead of silently creating a second registry or an in-memory refresh store.

   */

  public AuthServiceImpl(

    Vertx vertx,

    UserRepository userRepository,

    RefreshTokenRepository refreshTokenRepository,

    TerminalSessionRegistry terminalSessionRegistry,

    AppConfig config)

  {

    this.vertx = Objects.requireNonNull(vertx, "vertx");

    this.userRepository = Objects.requireNonNull(userRepository, "userRepository");

    this.refreshTokenRepository = Objects.requireNonNull(refreshTokenRepository, "refreshTokenRepository");

    this.terminalSessionRegistry = Objects.requireNonNull(terminalSessionRegistry, "terminalSessionRegistry");

    this.config = Objects.requireNonNull(config, "config");

    this.secureRandom = new SecureRandom();

    JWTAuthOptions jwtAuthOptions = new JWTAuthOptions()

      .addPubSecKey(new PubSecKeyOptions()

        .setAlgorithm("HS256")

        .setBuffer(config.getEncryptionKey()));

    this.jwtAuth = JWTAuth.create(vertx, jwtAuthOptions);

    // Purge expired refresh tokens every hour

    this.vertx.setPeriodic(60 * 60 * 1000L, id ->

      this.refreshTokenRepository.deleteExpired()

        .onSuccess(count ->

        {

          if (count > 0)

          {

            System.out.println("Purged " + count + " expired refresh tokens");

          }

        })

        .onFailure(err -> System.out.println("Refresh token purge failed: " + err.getMessage())));

    // Drop tokensValidAfter entries that no longer matter (every older access token has expired)

    this.vertx.setPeriodic(5 * 60 * 1000L, id -> cleanTokensValidAfter());

  }

  // ------------------------------------------------------------------

  // Login

  // ------------------------------------------------------------------

  @Override

  public Future<JsonObject> login(String username, String password)

  {

    if (username == null || username.trim().isEmpty() || password == null || password.isEmpty())

    {

      return Future.failedFuture(

        new ApiException(400, ErrorCodes.INVALID_REQUEST.getCode(), "Username and password are required")

      );

    }

    String cleanUsername = username.trim();

    return userRepository.findByUsername(cleanUsername)

      .compose(userOpt ->

      {

        if (userOpt.isEmpty())

        {

          return Future.failedFuture(

            new ApiException(401, ErrorCodes.INVALID_CREDENTIALS.getCode(), "Invalid username or password")

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

                new ApiException(401, ErrorCodes.INVALID_CREDENTIALS.getCode(), "Invalid username or password")

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

              .put("user.id", userId)

              .put("token", refreshToken)

              .put("expires.at", refreshExpiresAt.toString())

              .put("created.at", Instant.now().toString());

            return refreshTokenRepository.create(rtRecord)

              .map(saved -> new JsonObject()

                .put("user", safeUser)

                .put("accessToken", accessToken)

                .put("refreshToken", refreshToken)

              );

          });

      });

  }

  // ------------------------------------------------------------------

  // Refresh (rotation)

  // ------------------------------------------------------------------

  @Override
  public Future<JsonObject> refreshToken(String refreshToken)

  {

    if (refreshToken == null || refreshToken.trim().isEmpty())

    {

      return Future.failedFuture(

        new ApiException(400, ErrorCodes.INVALID_REQUEST.getCode(), "Refresh token is required")

      );

    }

    String cleanToken = refreshToken.trim();

    return refreshTokenRepository.findByToken(cleanToken)

      .compose(tokenOpt ->

      {

        if (tokenOpt.isEmpty())

        {

          return Future.failedFuture(

            new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Invalid or expired refresh token")

          );

        }

        JsonObject record = tokenOpt.get();

        String expiresAtStr = record.getString("expires.at");

        if (expiresAtStr == null || Instant.now().isAfter(Instant.parse(expiresAtStr)))

        {

          return refreshTokenRepository.deleteByToken(cleanToken)

            .compose(v -> Future.failedFuture(

              new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Refresh token has expired")

            ));

        }

        String userId = record.getString("user.id");

        // Look the user up BEFORE consuming the old token, so a failure here

        // does not leave the user without any refresh token.

        return userRepository.findById(userId)

          .compose(userOpt ->

          {

            if (userOpt.isEmpty())

            {

              return refreshTokenRepository.deleteByToken(cleanToken)

                .compose(v -> Future.failedFuture(

                  new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "User not found")

                ));

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

              .put("user.id", user.getString("id"))

              .put("token", newRefreshToken)

              .put("expires.at", newExpiresAt.toString())

              .put("created.at", Instant.now().toString());

            // Rotate: the used refresh token is consumed, a new one is issued

            return refreshTokenRepository.deleteByToken(cleanToken)

              .compose(deleted -> refreshTokenRepository.create(newRecord))

              .map(saved -> new JsonObject()

                .put("user", safeUser)

                .put("token", newAccessToken)

                .put("accessToken", newAccessToken)

                .put("refreshToken", newRefreshToken)

              );

          });

      });

  }

  // ------------------------------------------------------------------

  // Logout

  // ------------------------------------------------------------------

  @Override
  public Future<Void> logout(String sessionId)

  {

    return logout(sessionId, null);

  }

  /**

   * Plain logout: ends ONLY this browser's login.
   * Deletes the refresh token that was presented. Other browsers/devices keep their own
   * refresh tokens and stay logged in. Live terminals keep running.
   * The access token is short-lived and simply expires; the handler clears the cookies.

   */

  @Override
  public Future<Void> logout(String accessToken, String refreshToken)

  {

    if (refreshToken == null || refreshToken.trim().isEmpty())

    {

      return Future.succeededFuture();

    }

    return refreshTokenRepository.deleteByToken(refreshToken.trim()).mapEmpty();

  }

  @Override
  public Future<Void> logoutAndCloseSessions(String sessionId)

  {

    return logoutAndCloseSessions(sessionId, null);

  }

  /**

   * Logout and close all: ends every login of this user and closes every live terminal.
   * The userId comes only from a trusted source (see resolveUserId), never from an
   * unverified token.

   */

  @Override
  public Future<Void> logoutAndCloseSessions(String accessToken, String refreshToken)

  {

    return resolveUserId(accessToken, refreshToken)

      .compose(uidOpt ->

      {

        if (uidOpt.isEmpty())

        {

          // Nothing trustworthy to act on (no valid refresh token, access token invalid or expired)

          return Future.<Void>succeededFuture();

        }

        String uid = uidOpt.get();

        // 1. Every access token issued before now is rejected from this moment (no DB, immediate)

        tokensValidAfter.put(uid, Instant.now());

        // 2. Delete all refresh tokens, and close all terminals. Both run even if one fails.

        Future<Void> deleteTokens = refreshTokenRepository.deleteByUserId(uid).mapEmpty();

        Future<Integer> closeTerminals = terminalSessionRegistry.closeAllSessionsForUser(uid, CLOSE_ALL_REASON);

        return Future.all(deleteTokens, closeTerminals)

          .onSuccess(v -> System.out.println("[AUDIT] User " + uid

            + " executed 'logout and close all': " + closeTerminals.result()

            + " live terminal session(s) closed."))

          .<Void>mapEmpty();

      });

  }

  /**

   * Finds the user for a logout request using only trusted data:
   * 1. the refresh token's DB record (works even if the access token has expired), else
   * 2. an access token whose signature and expiry are verified.
   * A token that is merely base64-decoded is never trusted.

   */

  private Future<Optional<String>> resolveUserId(String accessToken, String refreshToken)

  {

    if (refreshToken != null && !refreshToken.trim().isEmpty())

    {

      return refreshTokenRepository.findByToken(refreshToken.trim())

        .map(opt -> opt.map(record -> record.getString("user.id")));

    }

    if (accessToken != null && !accessToken.trim().isEmpty())

    {

      return jwtAuth.authenticate(new TokenCredentials(accessToken.trim()))

        .map(user -> Optional.ofNullable(user.principal().getString("sub")))

        .recover(err -> Future.succeededFuture(Optional.<String>empty()));

    }

    System.out.println("Refresh: " + refreshToken + " access token: " + accessToken);

    return Future.succeededFuture(Optional.<String>empty());

  }

  // ------------------------------------------------------------------

  // Authenticate (every request / WebSocket connect)

  // ------------------------------------------------------------------

  /**

   * Verifies the access token (signature + expiry) without any DB call, then checks it was not
   * issued before the user's last "logout and close all".

   */

  @Override
  public Future<JsonObject> authenticateSession(String sessionId)
  {

    if (sessionId == null || sessionId.trim().isEmpty())
    {

      return Future.failedFuture(

        new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Authentication required")

      );

    }

    String cleanToken = sessionId.trim();

    return jwtAuth.authenticate(new TokenCredentials(cleanToken))

      .recover(err -> Future.failedFuture(

        new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Session expired or invalid")

      ))

      .compose(user ->

      {

        JsonObject principal = user.principal();

        if (!"access".equalsIgnoreCase(principal.getString("type")))
        {

          return Future.failedFuture(

            new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Invalid token type")

          );

        }

        String userId = principal.getString("sub");

        if (userId == null)
        {

          return Future.failedFuture(

            new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Session expired or invalid")

          );

        }

        Instant validAfter = tokensValidAfter.get(userId);

        if (validAfter != null)

        {

          Long iat = principal.getLong("iat");

          // Fail closed: a token without iat cannot prove it is newer than the logout

          if (iat == null || iat < validAfter.getEpochSecond())
          {

            return Future.failedFuture(

              new ApiException(401, ErrorCodes.UNAUTHORIZED.getCode(), "Session has been revoked")

            );

          }

        }

        JsonObject safeUser = new JsonObject()

          .put("id", userId)

          .put("username", principal.getString("username"))

          .put("created.at", principal.getString("created.at"));

        return Future.succeededFuture(safeUser);

      });

  }

  // ------------------------------------------------------------------

  // Register

  // ------------------------------------------------------------------

  @Override

  public Future<JsonObject> register(String username, String password)
  {

    if (username == null || username.trim().isEmpty() || password == null || password.length() < 8)
    {

      return Future.failedFuture(

        new ApiException(400, ErrorCodes.INVALID_REQUEST.getCode(), "Username is required and password must be at least 8 characters")

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

  // ------------------------------------------------------------------

  // Helpers

  // ------------------------------------------------------------------

  private String generateAccessToken(JsonObject user)
  {

    String userId = user.getString("id");

    JsonObject claims = new JsonObject()

      .put("sub", userId)

      .put("username", user.getString("username"))

      .put("created.at", user.getString("created.at"))

      .put("jti", generateSecureToken())

      .put("type", "access");

    // Vert.x adds "iat" and "exp" automatically

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

  /**

   * A tokensValidAfter entry is only needed while some older access token could still be valid.
   * After accessTokenTtl (+1 minute margin) all of them have expired, so the entry can go.

   */

  private void cleanTokensValidAfter()
  {

    Instant cutoff = Instant.now().minus(config.getAccessTokenTtlMinutes() + 1L, ChronoUnit.MINUTES);

    tokensValidAfter.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));

  }

}
