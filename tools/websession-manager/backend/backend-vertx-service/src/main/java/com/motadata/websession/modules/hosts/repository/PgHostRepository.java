package com.motadata.websession.modules.hosts.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PgHostRepository implements HostRepository
{

    private final Pool pool;

    public PgHostRepository(Pool pool)
    {
        this.pool = pool;
    }

    @Override
    public Future<JsonObject> create(JsonObject host)
    {
        String sql = "INSERT INTO hosts ("

            + "id, user_id, name, address, port, credential_id, "

            + "color, tags, favorite, post_connect_script, logging_enabled, agent_id, "

            + "created_at, updated_at"

            + ") VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14) "

            + "RETURNING id, user_id, name, address, port, credential_id, "

            + "color, tags, favorite, post_connect_script, logging_enabled, agent_id, "

            + "created_at, updated_at";

        String createdAtStr = host.getString("created.at");

        OffsetDateTime createdAt = createdAtStr != null

            ? Instant.parse(createdAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        String updatedAtStr = host.getString("updated.at");

        OffsetDateTime updatedAt = updatedAtStr != null

            ? Instant.parse(updatedAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        String userId = host.getString("user.id");

        if (userId == null)
        {
            userId = host.getString("userId");
        }

        String credId = host.getString("credential.id");

        if (credId == null)
        {
            credId = host.getString("credentialId");
        }

        Tuple params = Tuple.of(
            host.getString("id"),
            userId,
            host.getString("name"),
            host.getString("address"),
            host.getInteger("port", 22),
            credId,
            host.getString("color"),
            host.getString("tags"),
            host.getBoolean("favorite", false),
            host.getString("postConnectScript"),
            host.getBoolean("loggingEnabled", false),
            host.getString("agentId"),
            createdAt,
            updatedAt
        );

        return pool.preparedQuery(sql)

            .execute(params)

            .map(rows ->
            {
                Row row = rows.iterator().next();

                return mapRow(row);
            });
    }

    @Override
    public Future<Optional<JsonObject>> findById(String id, String userId)
    {
        String sql = "SELECT h.id, h.user_id, h.name, h.address, h.port, h.credential_id, "

            + "h.color, h.tags, h.favorite, h.post_connect_script, h.logging_enabled, h.agent_id, "

            + "h.created_at, h.updated_at, "

            + "c.name AS credential_name, c.username AS credential_username, c.auth_type AS credential_auth_type "

            + "FROM hosts h "

            + "LEFT JOIN credentials c ON h.credential_id = c.id "

            + "WHERE h.id = $1 AND h.user_id = $2";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id, userId))

            .map(rows ->
            {
                if (rows == null || !rows.iterator().hasNext())
                {
                    return Optional.empty();
                }

                return Optional.of(mapRowWithCredential(rows.iterator().next()));
            });
    }

    @Override
    public Future<List<JsonObject>> listByUserId(String userId)
    {
        String sql = "SELECT h.id, h.user_id, h.name, h.address, h.port, h.credential_id, "

            + "h.color, h.tags, h.favorite, h.post_connect_script, h.logging_enabled, h.agent_id, "

            + "h.created_at, h.updated_at, "

            + "c.name AS credential_name, c.username AS credential_username, c.auth_type AS credential_auth_type "

            + "FROM hosts h "

            + "LEFT JOIN credentials c ON h.credential_id = c.id "

            + "WHERE h.user_id = $1 "

            + "ORDER BY h.created_at DESC";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(userId))

            .map(rows ->
            {
                List<JsonObject> list = new ArrayList<>();

                for (Row row : rows)
                {
                    list.add(mapRowWithCredential(row));
                }

                return list;
            });
    }

    @Override
    public Future<Optional<JsonObject>> update(String id, String userId, JsonObject host)
    {
        String sql = "UPDATE hosts SET "

            + "name = COALESCE($1, name), "

            + "address = COALESCE($2, address), "

            + "port = COALESCE($3, port), "

            + "credential_id = $4, "

            + "color = COALESCE($5, color), "

            + "tags = COALESCE($6, tags), "

            + "favorite = COALESCE($7, favorite), "

            + "post_connect_script = COALESCE($8, post_connect_script), "

            + "logging_enabled = COALESCE($9, logging_enabled), "

            + "updated_at = CURRENT_TIMESTAMP "

            + "WHERE id = $10 AND user_id = $11 "

            + "RETURNING id, user_id, name, address, port, credential_id, "

            + "color, tags, favorite, post_connect_script, logging_enabled, agent_id, "

            + "created_at, updated_at";

        String credId = host.getString("credential.id");

        if (credId == null)
        {
            credId = host.getString("credentialId");
        }

        Tuple params = Tuple.of(
            host.getString("name"),
            host.getString("address"),
            host.getInteger("port"),
            credId,
            host.getString("color"),
            host.getString("tags"),
            host.getBoolean("favorite"),
            host.getString("postConnectScript"),
            host.getBoolean("loggingEnabled"),
            id,
            userId
        );

        return pool.preparedQuery(sql)

            .execute(params)

            .map(rows ->
            {
                if (rows == null || !rows.iterator().hasNext())
                {
                    return Optional.empty();
                }

                return Optional.of(mapRow(rows.iterator().next()));
            });
    }

    @Override
    public Future<Boolean> deleteById(String id, String userId)
    {
        String sql = "DELETE FROM hosts WHERE id = $1 AND user_id = $2";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id, userId))

            .map(rows -> rows.rowCount() > 0);
    }

    private JsonObject mapRow(Row row)
    {
        OffsetDateTime createdAt = row.getOffsetDateTime("created_at");

        OffsetDateTime updatedAt = row.getOffsetDateTime("updated_at");

        String userId = row.getString("user_id");

        String credId = row.getString("credential_id");

        return new JsonObject()

            .put("id", row.getString("id"))

            .put("user.id", userId)

            .put("name", row.getString("name"))

            .put("address", row.getString("address"))

            .put("port", row.getInteger("port"))

            .put("credential.id", credId)

            .put("color", row.getString("color"))

            .put("tags", row.getString("tags"))

            .put("favorite", row.getBoolean("favorite"))

            .put("postConnectScript", row.getString("post_connect_script"))

            .put("loggingEnabled", row.getBoolean("logging_enabled"))

            .put("agentId", row.getString("agent_id"))

            .put("created.at", createdAt != null ? createdAt.toInstant().toString() : null)

            .put("updated.at", updatedAt != null ? updatedAt.toInstant().toString() : null);
    }

    private JsonObject mapRowWithCredential(Row row)
    {
        JsonObject hostJson = mapRow(row);

        String credId = row.getString("credential_id");

        if (credId != null)
        {
            JsonObject credMeta = new JsonObject()

                .put("id", credId)

                .put("name", row.getString("credential_name"))

                .put("username", row.getString("credential_username"))

                .put("authType", row.getString("credential_auth_type"));

            hostJson.put("credential", credMeta);
        }
        else
        {
            hostJson.putNull("credential");
        }

        return hostJson;
    }
}
