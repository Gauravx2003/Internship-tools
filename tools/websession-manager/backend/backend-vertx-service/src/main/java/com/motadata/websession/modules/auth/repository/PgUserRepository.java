package com.motadata.websession.modules.auth.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

public class PgUserRepository implements UserRepository
{

    private final Pool pool;

    public PgUserRepository(Pool pool)
    {
        this.pool = pool;
    }

    @Override
    public Future<Optional<JsonObject>> findByUsername(String username)
    {
        String sql = "SELECT id, username, password_hash, created_at, updated_at FROM users WHERE username = $1";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(username))

            .map(this::mapSingleUser);
    }

    @Override
    public Future<Optional<JsonObject>> findById(String id)
    {
        String sql = "SELECT id, username, password_hash, created_at, updated_at FROM users WHERE id = $1";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id))

            .map(this::mapSingleUser);
    }

    @Override
    public Future<JsonObject> create(JsonObject user)
    {
        String sql = "INSERT INTO users (id, username, password_hash, created_at, updated_at) "

            + "VALUES ($1, $2, $3, $4, $5) "

            + "RETURNING id, username, password_hash, created_at, updated_at";

        String createdAtStr = user.getString("created.at");

        OffsetDateTime createdAt = createdAtStr != null

            ? Instant.parse(createdAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        String updatedAtStr = user.getString("updated.at");

        OffsetDateTime updatedAt = updatedAtStr != null

            ? Instant.parse(updatedAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        Tuple params = Tuple.of(
            user.getString("id"),
            user.getString("username"),
            user.getString("passwordHash"),
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
    public Future<Long> countUsers()
    {
        String sql = "SELECT COUNT(*) FROM users";

        return pool.query(sql)

            .execute()

            .map(rows ->
            {
                Row row = rows.iterator().next();

                return row.getLong(0);
            });
    }

    private Optional<JsonObject> mapSingleUser(RowSet<Row> rows)
    {
        if (rows == null || !rows.iterator().hasNext())
        {
            return Optional.empty();
        }

        Row row = rows.iterator().next();

        return Optional.of(mapRow(row));
    }

    private JsonObject mapRow(Row row)
    {
        OffsetDateTime createdAt = row.getOffsetDateTime("created_at");

        OffsetDateTime updatedAt = row.getOffsetDateTime("updated_at");

        return new JsonObject()

            .put("id", row.getString("id"))

            .put("username", row.getString("username"))

            .put("passwordHash", row.getString("password_hash"))

            .put("created.at", createdAt != null ? createdAt.toInstant().toString() : null)

            .put("updated.at", updatedAt != null ? updatedAt.toInstant().toString() : null);
    }
}
