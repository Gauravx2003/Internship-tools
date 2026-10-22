package com.motadata.websession.modules.keys.service;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.modules.credentials.service.CredentialService;
import com.motadata.websession.modules.keys.util.SshKeyGenerator;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

public class KeyServiceImpl implements KeyService
{

    private final Vertx vertx;

    private final CredentialService credentialService;

    public KeyServiceImpl(Vertx vertx, CredentialService credentialService)
    {
        this.vertx = vertx;

        this.credentialService = credentialService;
    }

    @Override
    public Future<JsonObject> generateKey(String userId, JsonObject payload)
    {
        JsonObject safePayload = payload != null ? payload : new JsonObject();

        String type = safePayload.getString("type", "ed25519").toLowerCase().trim();

        String username = safePayload.getString("username", "root").trim();

        String name = safePayload.getString("name");

        if (name == null || name.trim().isEmpty())
        {
            name = "SSH Key (" + type + ")";
        }
        else
        {
            name = name.trim();
        }

        String comment = username + "@websession";

        int keySize = safePayload.getInteger("keySize", 3072);

        String finalName = name;

        return vertx.executeBlocking(() ->
        {
            if ("ed25519".equalsIgnoreCase(type))
            {
                return SshKeyGenerator.generateEd25519(comment);
            }
            else if ("rsa".equalsIgnoreCase(type))
            {
                return SshKeyGenerator.generateRsa(keySize, comment);
            }
            else
            {
                throw new ApiException(400, "INVALID_KEY_TYPE", "Unsupported key type: " + type + ". Supported: ed25519, rsa");
            }
        })

            .compose(generatedKey ->
            {
                String privateKeyPem = generatedKey.getString("privateKey");

                JsonObject credPayload = new JsonObject()

                    .put("name", finalName)

                    .put("username", username)

                    .put("authType", "key")

                    .put("password", privateKeyPem);

                return credentialService.create(userId, credPayload)

                    .map(savedCred ->
                    {
                        return new JsonObject()

                            .put("credential.id", savedCred.getString("id"))

                            .put("name", finalName)

                            .put("username", username)

                            .put("type", type)

                            .put("publicKey", generatedKey.getString("publicKey"))

                            .put("fingerprint", generatedKey.getString("fingerprint"))

                            .put("privateKey", privateKeyPem);
                    });
            });
    }
}
