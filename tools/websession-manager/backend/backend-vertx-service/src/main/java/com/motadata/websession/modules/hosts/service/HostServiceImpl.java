package com.motadata.websession.modules.hosts.service;

import com.motadata.websession.common.error.ApiException;
import com.motadata.websession.modules.credentials.service.CredentialService;
import com.motadata.websession.modules.hosts.repository.HostRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class HostServiceImpl implements HostService
{

    private final HostRepository hostRepository;

    private final CredentialService credentialService;

    public HostServiceImpl(
        HostRepository hostRepository,
        CredentialService credentialService)
    {
        this.hostRepository = hostRepository;

        this.credentialService = credentialService;
    }

    @Override
    public Future<JsonObject> create(String userId, JsonObject payload)
    {
        if (payload == null)
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Payload cannot be null")
            );
        }

        String name = payload.getString("name");

        String address = payload.getString("address");

        String username = payload.getString("username");

        int port = payload.getInteger("port", 22);

        if (name == null || name.trim().isEmpty())
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Host name is required")
            );
        }

        if (address == null || address.trim().isEmpty())
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Host address is required")
            );
        }

        if (port < 1 || port > 65535)
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Port must be between 1 and 65535")
            );
        }

        if(username == null || username.trim().isEmpty())
        {
          return Future.failedFuture(
              new ApiException(400, "INVALID_REQUEST", "Username is required"));
        }



        return resolveCredentialId(userId, name, payload)

            .compose(credentialId ->
            {
                String hostId = UUID.randomUUID().toString();

                Instant now = Instant.now();

                JsonObject hostRecord = new JsonObject()

                    .put("id", hostId)

                    .put("user.id", userId)

                    .put("name", name.trim())

                    .put("address", address.trim())

                    .put("port", port)

                    .put("credential.id", credentialId)

                    .put("color", payload.getString("color"))

                    .put("tags", payload.getString("tags"))

                    .put("favorite", payload.getBoolean("favorite", false))

                    .put("postConnectScript", payload.getString("postConnectScript"))

                    .put("loggingEnabled", payload.getBoolean("loggingEnabled", false))

                    .put("agentId", payload.getString("agentId"))

                    .put("created.at", now.toString())

                    .put("updated.at", now.toString());

                return hostRepository.create(hostRecord);
            });
    }

    @Override
    public Future<Optional<JsonObject>> findById(String id, String userId)
    {
        return hostRepository.findById(id, userId);
    }

    @Override
    public Future<List<JsonObject>> list(String userId)
    {
        return hostRepository.listByUserId(userId);
    }

    @Override
    public Future<Optional<JsonObject>> update(String id, String userId, JsonObject payload)
    {
        if (payload == null)
        {
            return Future.failedFuture(
                new ApiException(400, "INVALID_REQUEST", "Payload cannot be null")
            );
        }

        return hostRepository.findById(id, userId)

            .compose(existingOpt ->
            {
                if (existingOpt.isEmpty())
                {
                    return Future.succeededFuture(Optional.empty());
                }

                String credentialId = payload.getString("credential.id");

                if (credentialId == null)
                {
                    credentialId = payload.getString("credentialId");
                }

                if (credentialId == null)
                {
                    credentialId = existingOpt.get().getString("credential.id");
                }

                JsonObject updateRecord = new JsonObject()

                    .put("name", payload.getString("name"))

                    .put("address", payload.getString("address"))

                    .put("port", payload.getInteger("port"))

                    .put("credential.id", credentialId)

                    .put("color", payload.getString("color"))

                    .put("tags", payload.getString("tags"))

                    .put("favorite", payload.getBoolean("favorite"))

                    .put("postConnectScript", payload.getString("postConnectScript"))

                    .put("loggingEnabled", payload.getBoolean("loggingEnabled"));

                return hostRepository.update(id, userId, updateRecord);
            });
    }

    @Override
    public Future<Boolean> delete(String id, String userId)
    {
        return hostRepository.deleteById(id, userId);
    }

    private Future<String> resolveCredentialId(String userId, String hostName, JsonObject payload)
    {
        String rawCredId = payload.getString("credential.id");

        if (rawCredId == null)
        {
            rawCredId = payload.getString("credentialId");
        }

        String existingCredId = (rawCredId != null) ? rawCredId.trim() : null;

        if (existingCredId != null && !existingCredId.isEmpty())
        {
            return credentialService.findById(existingCredId, userId)

                .compose(credOpt ->
                {
                    if (credOpt.isEmpty())
                    {
                        return Future.failedFuture(
                            new ApiException(404, "CREDENTIAL_NOT_FOUND", "Specified credential does not exist")
                        );
                    }

                    return Future.succeededFuture(existingCredId);
                });
        }

        String username = payload.getString("username");

        String password = payload.getString("password");

        if (username != null && !username.trim().isEmpty() && password != null && !password.isEmpty())
        {
            JsonObject credPayload = new JsonObject()

                .put("name", hostName + " Credential")

                .put("username", username.trim())

                .put("password", password)

                .put("authType", payload.getString("authType", "password"));

            return credentialService.create(userId, credPayload)

                .map(createdCred -> createdCred.getString("id"));
        }

        return Future.succeededFuture(null);
    }
}
