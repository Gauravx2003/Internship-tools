package com.motadata.websession.backend_vertx_service;

import com.motadata.websession.config.AppConfig;
import com.motadata.websession.http.AppRouter;
import com.motadata.websession.middleware.SessionAuthMiddleware;
import com.motadata.websession.modules.auth.handler.AuthHandler;
import com.motadata.websession.modules.auth.handler.AuthRouter;
import com.motadata.websession.modules.auth.repository.*;
import com.motadata.websession.modules.auth.service.*;
import com.motadata.websession.modules.credentials.handler.*;
import com.motadata.websession.modules.credentials.repository.*;
import com.motadata.websession.modules.credentials.service.*;
import com.motadata.websession.modules.engine.channel.EngineChannel;
import com.motadata.websession.modules.engine.channel.WebSocketEngineChannel;
import com.motadata.websession.modules.hosts.handler.*;
import com.motadata.websession.modules.hosts.repository.*;
import com.motadata.websession.modules.hosts.service.*;
import com.motadata.websession.modules.keys.handler.*;
import com.motadata.websession.modules.keys.service.*;
import com.motadata.websession.modules.terminal.registry.InMemoryTerminalSessionRegistry;
import com.motadata.websession.modules.terminal.registry.TerminalSessionRegistry;
import com.motadata.websession.persistence.DatabasePool;
import io.vertx.core.Future;
import io.vertx.core.VerticleBase;
import io.vertx.ext.web.Router;
import io.vertx.sqlclient.Pool;

public class MainVerticle extends VerticleBase {

  private Pool dbPool;
  private EngineChannel engineChannel;

  @Override
  public Future<?> start() {
    AppConfig config = AppConfig.load();
    this.dbPool = DatabasePool.create(vertx, config);
    this.engineChannel = new WebSocketEngineChannel(vertx, config);

    Router appRouter = createAppRouter(config);

    return vertx.createHttpServer()
      .requestHandler(appRouter)
      .listen(config.getHttpPort())
      .onSuccess(server -> {
        int port = server.actualPort();
        System.out.println("===================================================");
        System.out.println(" 🔒 HTTPS SERVER RUNNING");
        System.out.println(" --------------------------------------------------");
        System.out.println(" 🌐 Port:               " + port);
        System.out.println(" 🏥 Health Check:       http://localhost:" + port + "/health");
        System.out.println(" 🔌 Engine Test WS:     ws://localhost:" + port + "/ws/test-engine");
        System.out.println("===================================================");
      })
      .onFailure(err -> System.err.println("Failed to start HTTP server: " + err.getMessage()));
  }

  @Override
  public Future<?> stop() {
    Future<Void> chFuture = engineChannel != null ? engineChannel.close() : Future.succeededFuture();
    Future<Void> dbFuture = dbPool != null ? dbPool.close() : Future.succeededFuture();
    return Future.all(chFuture, dbFuture);
  }

  /**
   * Wires all dependencies and returns the main application router.
   */
  private Router createAppRouter(AppConfig config) {
    // Repositories
    UserRepository userRepository = new PgUserRepository(dbPool);
    CredentialRepository credentialRepository = new PgCredentialRepository(dbPool);
    HostRepository hostRepository = new PgHostRepository(dbPool);

    // Registries
    TerminalSessionRegistry terminalSessionRegistry = new InMemoryTerminalSessionRegistry(vertx, config);

    // Services (No auth_session table used - fixed expiry token + in-memory revocation denylist)
    AuthService authService = new AuthServiceImpl(vertx, userRepository, terminalSessionRegistry, config);
    CredentialService credentialService = new CredentialServiceImpl(vertx, credentialRepository, config);
    HostService hostService = new HostServiceImpl(hostRepository, credentialService);
    KeyService keyService = new KeyServiceImpl(vertx, credentialService);

    // Middleware
    SessionAuthMiddleware sessionAuthMiddleware = new SessionAuthMiddleware(authService);

    // Handlers
    AuthHandler authHandler = new AuthHandler(authService, config);
    CredentialHandler credentialHandler = new CredentialHandler(credentialService);
    HostHandler hostHandler = new HostHandler(hostService);
    KeyHandler keyHandler = new KeyHandler(keyService);

    // Routers
    AuthRouter authRouter = new AuthRouter(authHandler);
    CredentialRouter credentialRouter = new CredentialRouter(credentialHandler, sessionAuthMiddleware);
    HostRouter hostRouter = new HostRouter(hostHandler, sessionAuthMiddleware);
    KeyRouter keyRouter = new KeyRouter(keyHandler, sessionAuthMiddleware);

    return AppRouter.create(vertx, authRouter, hostRouter, credentialRouter, keyRouter, engineChannel);
  }
}
