package com.motadata.websession.backend_vertx_service;

import com.motadata.websession.auth.handler.AuthHandler;
import com.motadata.websession.auth.handler.AuthRouter;
import com.motadata.websession.auth.repository.PgSessionRepository;
import com.motadata.websession.auth.repository.PgUserRepository;
import com.motadata.websession.auth.repository.SessionRepository;
import com.motadata.websession.auth.repository.UserRepository;
import com.motadata.websession.auth.service.AuthService;
import com.motadata.websession.auth.service.AuthServiceImpl;
import com.motadata.websession.config.AppConfig;
import com.motadata.websession.http.AppRouter;
import com.motadata.websession.persistence.DatabasePool;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.ext.web.Router;
import io.vertx.sqlclient.Pool;

public class MainVerticle extends VerticleBase
{

    private Pool dbPool;

    @Override
    public Future<?> start()
    {
        AppConfig config = AppConfig.load();

        this.dbPool = DatabasePool.create(vertx, config);

        UserRepository userRepository = new PgUserRepository(dbPool);

        SessionRepository sessionRepository = new PgSessionRepository(dbPool);

        AuthService authService = new AuthServiceImpl(vertx, userRepository, sessionRepository, config);

        AuthHandler authHandler = new AuthHandler(authService, config);

        AuthRouter authRouter = new AuthRouter(authHandler);

        Router appRouter = AppRouter.create(vertx, authRouter);

        return vertx.createHttpServer()

            .requestHandler(appRouter)

            .listen(config.getHttpPort())

            .onSuccess(server ->
            {
                System.out.println("HTTP server started on port " + server.actualPort());
            })

            .onFailure(err ->
            {
                System.err.println("Failed to start HTTP server: " + err.getMessage());
            });
    }

    @Override
    public Future<?> stop()
    {
        if (dbPool != null)
        {
            return dbPool.close();
        }

        return Future.succeededFuture();
    }
}
