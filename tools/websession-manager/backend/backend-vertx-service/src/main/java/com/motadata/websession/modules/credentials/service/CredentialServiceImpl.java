package com.motadata.websession.modules.credentials.service;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.common.validator.PayloadValidator;
import com.motadata.websession.config.AppConfig;
import com.motadata.websession.modules.crypto.AesGcmEncryption;
import com.motadata.websession.modules.credentials.repository.CredentialRepository;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class CredentialServiceImpl implements CredentialService
{

    private final Vertx vertx;

    private final CredentialRepository credentialRepository;

    private final AppConfig config;

    public CredentialServiceImpl(
        Vertx vertx,
        CredentialRepository credentialRepository,
        AppConfig config)
    {
        this.vertx = vertx;

        this.credentialRepository = credentialRepository;

        this.config = config;
    }

  @Override
  public Future<JsonObject> create(String userId, JsonObject payload)
  {
    return PayloadValidator.of(payload)

      .requireNonEmpty("name", "Credential name is required")

      .requireNonEmpty("username", "Username is required")

      .requireAnyNonEmpty(List.of("password", "secret"), "Password or secret is required")

      .validate()

      .compose(v -> {

        String secret = Optional.ofNullable(payload.getString("password"))

          .filter(s -> !s.trim().isEmpty())

          .orElseGet(() -> payload.getString("secret"));

        return vertx.executeBlocking(() -> AesGcmEncryption.encrypt(secret, config.getEncryptionKey()));

      })
      .compose(encryptedSecret -> {

        String id = UUID.randomUUID().toString();

        Instant now = Instant.now();

        JsonObject credRecord = new JsonObject()
          .put("id", id)
          .put("user.id", userId)
          .put("name", payload.getString("name").trim())
          .put("username", payload.getString("username").trim())
          .put("authType", payload.getString("authType", "password"))
          .put("encryptedSecret", encryptedSecret)
          .put("created.at", now.toString())
          .put("updated.at", now.toString());

        return credentialRepository.create(credRecord);

      })

      .map(this::sanitizeForClient);
  }

    @Override
    public Future<Optional<JsonObject>> findById(String id, String userId)
    {
        return credentialRepository.findById(id, userId)

            .map(opt -> opt.map(this::sanitizeForClient));
    }

    @Override
    public Future<List<JsonObject>> list(String userId)
    {
        return credentialRepository.listByUserId(userId)

            .map(list ->
            {
                List<JsonObject> sanitized = new ArrayList<>();

                for (JsonObject cred : list)
                {
                    sanitized.add(sanitizeForClient(cred));
                }

                return sanitized;
            });
    }

    @Override
    public Future<Boolean> delete(String id, String userId)
    {
        return credentialRepository.deleteById(id, userId);
    }

    @Override
    public Future<Optional<String>> decryptSecret(String id, String userId)
    {
        return credentialRepository.findById(id, userId)

            .compose(opt ->
            {
                if (opt.isEmpty())
                {
                    return Future.succeededFuture(Optional.empty());
                }

                String encryptedSecret = opt.get().getString("encryptedSecret");

                return vertx.executeBlocking(() ->
                {
                    String decrypted = AesGcmEncryption.decrypt(encryptedSecret, config.getEncryptionKey());

                    return Optional.of(decrypted);
                });
            });
    }

    private JsonObject sanitizeForClient(JsonObject cred)
    {
        return new JsonObject()

            .put("id", cred.getString("id"))

            .put("name", cred.getString("name"))

            .put("username", cred.getString("username"))

            .put("authType", cred.getString("authType"))

            .put("created.at", cred.getString("created.at"))

            .put("updated.at", cred.getString("updated.at"));
    }
}
