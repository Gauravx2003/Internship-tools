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

public class PgSessionRepository implements SessionRepository
{

    private final Pool pool;

    public PgSessionRepository(Pool pool)
    {
        this.pool = pool;
    }

    @Override
    public Future<JsonObject> createSession(JsonObject session)
    {
        String sql = "INSERT INTO auth_sessions (id, user_id, created_at, expires_at, last_accessed_at) "

            + "VALUES ($1, $2, $3, $4, $5) "

            + "RETURNING id, user_id, created_at, expires_at, last_accessed_at";

        String createdAtStr = session.getString("created.at");

        OffsetDateTime createdAt = createdAtStr != null

            ? Instant.parse(createdAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC);

        String expiresAtStr = session.getString("expires.at");

        OffsetDateTime expiresAt = expiresAtStr != null

            ? Instant.parse(expiresAtStr).atOffset(ZoneOffset.UTC)

            : OffsetDateTime.now(ZoneOffset.UTC).plusHours(8);

        String lastAccessedStr = session.getString("lastAccessedAt");

        OffsetDateTime lastAccessedAt = lastAccessedStr != null

            ? Instant.parse(lastAccessedStr).atOffset(ZoneOffset.UTC)

            : createdAt;

        String userId = session.getString("user.id");

        if (userId == null)
        {
            userId = session.getString("userId");
        }

        Tuple params = Tuple.of(
            session.getString("id"),
            userId,
            createdAt,
            expiresAt,
            lastAccessedAt
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
    public Future<Optional<JsonObject>> findById(String id)
    {
        String sql = "SELECT id, user_id, created_at, expires_at, last_accessed_at FROM auth_sessions WHERE id = $1";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id))

            .map(this::mapSingleSession);
    }

    @Override
    public Future<Optional<JsonObject>> findSessionWithUser(String id)
    {
        String sql = "SELECT s.id AS session_id, s.expires_at, s.last_accessed_at, "

            + "u.id AS user_id, u.username, u.created_at AS user_created_at "

            + "FROM auth_sessions s "

            + "JOIN users u ON s.user_id = u.id "

            + "WHERE s.id = $1";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id))

            .map(rows ->
            {
                if (rows == null || !rows.iterator().hasNext())
                {
                    return Optional.empty();
                }

                Row row = rows.iterator().next();

                OffsetDateTime expiresAt = row.getOffsetDateTime("expires_at");

                OffsetDateTime lastAccessedAt = row.getOffsetDateTime("last_accessed_at");

                OffsetDateTime userCreatedAt = row.getOffsetDateTime("user_created_at");

                JsonObject safeUser = new JsonObject()

                    .put("id", row.getString("user_id"))

                    .put("username", row.getString("username"))

                    .put("created.at", userCreatedAt != null ? userCreatedAt.toInstant().toString() : null);

                JsonObject result = new JsonObject()

                    .put("sessionId", row.getString("session_id"))

                    .put("expiresAt", expiresAt != null ? expiresAt.toInstant().toString() : null)

                    .put("lastAccessedAt", lastAccessedAt != null ? lastAccessedAt.toInstant().toString() : null)

                    .put("user", safeUser);

                return Optional.of(result);
            });
    }

    @Override
    public Future<Void> updateLastAccessed(String id)
    {
        String sql = "UPDATE auth_sessions SET last_accessed_at = CURRENT_TIMESTAMP WHERE id = $1";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id))

            .mapEmpty();
    }

    @Override
    public Future<Boolean> deleteById(String id)
    {
        String sql = "DELETE FROM auth_sessions WHERE id = $1";

        return pool.preparedQuery(sql)

            .execute(Tuple.of(id))

            .map(rows -> rows.rowCount() > 0);
    }

    @Override
    public Future<Integer> deleteExpired()
    {
        String sql = "DELETE FROM auth_sessions WHERE expires_at < CURRENT_TIMESTAMP";

        return pool.query(sql)

            .execute()

            .map(RowSet::rowCount);
    }

    private Optional<JsonObject> mapSingleSession(RowSet<Row> rows)
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

        OffsetDateTime lastAccessedAt = row.getOffsetDateTime("last_accessed_at");

        String userId = row.getString("user_id");

        return new JsonObject()

            .put("id", row.getString("id"))

            .put("user.id", userId)

            .put("created.at", createdAt != null ? createdAt.toInstant().toString() : null)

            .put("expires.at", expiresAt != null ? expiresAt.toInstant().toString() : null)

            .put("lastAccessedAt", lastAccessedAt != null ? lastAccessedAt.toInstant().toString() : null);
    }
}
