package com.motadata.websession.backend_vertx_service;

import com.motadata.websession.modules.auth.handler.AuthHandler;
import com.motadata.websession.modules.auth.handler.AuthRouter;
import com.motadata.websession.middleware.SessionAuthMiddleware;
import com.motadata.websession.modules.auth.repository.PgSessionRepository;
import com.motadata.websession.modules.auth.repository.PgUserRepository;
import com.motadata.websession.modules.auth.repository.SessionRepository;
import com.motadata.websession.modules.auth.repository.UserRepository;
import com.motadata.websession.modules.auth.service.AuthService;
import com.motadata.websession.modules.auth.service.AuthServiceImpl;
import com.motadata.websession.config.AppConfig;
import com.motadata.websession.credentials.handler.CredentialHandler;
import com.motadata.websession.credentials.handler.CredentialRouter;
import com.motadata.websession.credentials.repository.CredentialRepository;
import com.motadata.websession.credentials.repository.PgCredentialRepository;
import com.motadata.websession.credentials.service.CredentialService;
import com.motadata.websession.credentials.service.CredentialServiceImpl;
import com.motadata.websession.modules.hosts.handler.HostHandler;
import com.motadata.websession.modules.hosts.handler.HostRouter;
import com.motadata.websession.modules.hosts.repository.HostRepository;
import com.motadata.websession.modules.hosts.repository.PgHostRepository;
import com.motadata.websession.modules.hosts.service.HostService;
import com.motadata.websession.modules.hosts.service.HostServiceImpl;
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

        // Repositories
        UserRepository userRepository = new PgUserRepository(dbPool);

        SessionRepository sessionRepository = new PgSessionRepository(dbPool);

        CredentialRepository credentialRepository = new PgCredentialRepository(dbPool);

        HostRepository hostRepository = new PgHostRepository(dbPool);

        // Services
        AuthService authService = new AuthServiceImpl(vertx, userRepository, sessionRepository, config);

        CredentialService credentialService = new CredentialServiceImpl(vertx, credentialRepository, config);

        HostService hostService = new HostServiceImpl(hostRepository, credentialService);

        // Middleware
        SessionAuthMiddleware sessionAuthMiddleware = new SessionAuthMiddleware(authService);

        // Handlers
        AuthHandler authHandler = new AuthHandler(authService, config);

        CredentialHandler credentialHandler = new CredentialHandler(credentialService);

        HostHandler hostHandler = new HostHandler(hostService);

        // Routers
        AuthRouter authRouter = new AuthRouter(authHandler);

        CredentialRouter credentialRouter = new CredentialRouter(credentialHandler, sessionAuthMiddleware);

        HostRouter hostRouter = new HostRouter(hostHandler, sessionAuthMiddleware);

        Router appRouter = AppRouter.create(vertx, authRouter, hostRouter, credentialRouter);

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
