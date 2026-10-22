package com.motadata.websession.persistence;

import com.motadata.websession.config.AppConfig;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

public class DatabasePool
{

    public static Pool create(Vertx vertx, AppConfig config)
    {
        PgConnectOptions connectOptions = new PgConnectOptions()

            .setHost(config.getDbHost())

            .setPort(config.getDbPort())

            .setDatabase(config.getDbName())

            .setUser(config.getDbUser())

            .setPassword(config.getDbPassword());

        PoolOptions poolOptions = new PoolOptions()

            .setMaxSize(10);

        return PgBuilder.pool()

            .connectingTo(connectOptions)

            .using(vertx)

            .with(poolOptions)

            .build();
    }
}
