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

public class PgRefreshTokenRepository implements RefreshTokenRepository
{

    private final Pool pool;

    public PgRefreshTokenRepository(Pool pool)
    {
        this.pool = pool;
    }

    @Override
    public Future<JsonObject> create(JsonObject tokenRecord)
    {
        String sql = "INSERT INTO refresh_tokens (id, user_id, token, expires_at, created_at) "
            + "VALUES ($1, $2, $3, $4, $5) "
            + "RETURNING id, user_id, token, expires_at, created_at";

        String createdAtStr = tokenRecord.getString("createdAt");
        if (createdAtStr == null)
        {
            createdAtStr = tokenRecord.getString("created.at");
        }

        OffsetDateTime createdAt = createdAtStr != null
            ? Instant.parse(createdAtStr).atOffset(ZoneOffset.UTC)
            : OffsetDateTime.now(ZoneOffset.UTC);

        String expiresAtStr = tokenRecord.getString("expiresAt");
        if (expiresAtStr == null)
        {
            expiresAtStr = tokenRecord.getString("expires.at");
        }

        OffsetDateTime expiresAt = expiresAtStr != null
            ? Instant.parse(expiresAtStr).atOffset(ZoneOffset.UTC)
            : OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);

        String userId = tokenRecord.getString("userId");
        if (userId == null)
        {
            userId = tokenRecord.getString("user_id");
        }

        Tuple params = Tuple.of(
            tokenRecord.getString("id"),
            userId,
            tokenRecord.getString("token"),
            expiresAt,
            createdAt
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
    public Future<Optional<JsonObject>> findByToken(String token)
    {
        String sql = "SELECT id, user_id, token, expires_at, created_at FROM refresh_tokens WHERE token = $1";

        return pool.preparedQuery(sql)
            .execute(Tuple.of(token))
            .map(this::mapSingleToken);
    }

    @Override
    public Future<Boolean> deleteByToken(String token)
    {
        String sql = "DELETE FROM refresh_tokens WHERE token = $1";

        return pool.preparedQuery(sql)
            .execute(Tuple.of(token))
            .map(rows -> rows.rowCount() > 0);
    }

    @Override
    public Future<Integer> deleteByUserId(String userId)
    {
        String sql = "DELETE FROM refresh_tokens WHERE user_id = $1";

        return pool.preparedQuery(sql)
            .execute(Tuple.of(userId))
            .map(RowSet::rowCount);
    }

    @Override
    public Future<Integer> deleteExpired()
    {
        String sql = "DELETE FROM refresh_tokens WHERE expires_at < CURRENT_TIMESTAMP";

        return pool.query(sql)
            .execute()
            .map(RowSet::rowCount);
    }

    private Optional<JsonObject> mapSingleToken(RowSet<Row> rows)
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
        OffsetDateTime expiresAt = row.getOffsetDateTime("expires_at");

        return new JsonObject()
            .put("id", row.getString("id"))
            .put("userId", row.getString("user_id"))
            .put("user_id", row.getString("user_id"))
            .put("token", row.getString("token"))
            .put("expiresAt", expiresAt != null ? expiresAt.toInstant().toString() : null)
            .put("expires_at", expiresAt != null ? expiresAt.toInstant().toString() : null)
            .put("createdAt", createdAt != null ? createdAt.toInstant().toString() : null)
            .put("created.at", createdAt != null ? createdAt.toInstant().toString() : null);
    }
}
