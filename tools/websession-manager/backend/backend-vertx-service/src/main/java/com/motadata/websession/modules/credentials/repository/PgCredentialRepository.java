package com.motadata.websession.credentials.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PgCredentialRepository implements CredentialRepository
{

    private final Pool pool;

    public PgCredentialRepository(Pool pool)
    {
        this.pool = pool;
    }

    @Override
    public Future<JsonObject> create(JsonObject credential)
    {
        String sql = "INSERT INTO credentials (id, user_id, name, username, auth_type, encrypted_secret, created_at, updated_at) "

            + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) "

            + "RETURNING id, user_id, name, username, auth_type, encrypted_secret, created_at, updated_at";

        String createdAtStr = credential.getString("createdAt");

        OffsetDateTime createdAt = createdAtStr != null

            ? Instant.parse(createdAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        String updatedAtStr = credential.getString("updatedAt");

        OffsetDateTime updatedAt = updatedAtStr != null

            ? Instant.parse(updatedAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        Tuple params = Tuple.of(
            credential.getString("id"),
            credential.getString("userId"),
            credential.getString("name"),
            credential.getString("username"),
            credential.getString("authType", "password"),
            credential.getString("encryptedSecret"),
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
        String sql = "SELECT id, user_id, name, username, auth_type, encrypted_secret, created_at, updated_at "

            + "FROM credentials WHERE id = $1 AND user_id = $2";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id, userId))

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
    public Future<List<JsonObject>> listByUserId(String userId)
    {
        String sql = "SELECT id, user_id, name, username, auth_type, encrypted_secret, created_at, updated_at "

            + "FROM credentials WHERE user_id = $1 ORDER BY created_at DESC";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(userId))

            .map(rows ->
            {
                List<JsonObject> list = new ArrayList<>();

                for (Row row : rows)
                {
                    list.add(mapRow(row));
                }

                return list;
            });
    }

    @Override
    public Future<Boolean> deleteById(String id, String userId)
    {
        String sql = "DELETE FROM credentials WHERE id = $1 AND user_id = $2";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id, userId))

            .map(rows -> rows.rowCount() > 0);
    }

    private JsonObject mapRow(Row row)
    {
        OffsetDateTime createdAt = row.getOffsetDateTime("created_at");

        OffsetDateTime updatedAt = row.getOffsetDateTime("updated_at");

        return new JsonObject()

            .put("id", row.getString("id"))

            .put("userId", row.getString("user_id"))

            .put("name", row.getString("name"))

            .put("username", row.getString("username"))

            .put("authType", row.getString("auth_type"))

            .put("encryptedSecret", row.getString("encrypted_secret"))

            .put("createdAt", createdAt != null ? createdAt.toInstant().toString() : null)

            .put("updatedAt", updatedAt != null ? updatedAt.toInstant().toString() : null);
    }
}
