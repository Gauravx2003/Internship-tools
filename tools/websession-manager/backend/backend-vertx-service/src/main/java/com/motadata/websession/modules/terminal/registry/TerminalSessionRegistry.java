package com.motadata.websession.modules.terminal.registry;

import com.motadata.websession.config.AppConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

public class TerminalSessionRegistry {
  public TerminalSessionRegistry(Vertx vertx, AppConfig config) {
  }

  public Future<Integer> closeAllSessionsForUser(String uid, String closeAllReason) {
    return null;
  }
}
