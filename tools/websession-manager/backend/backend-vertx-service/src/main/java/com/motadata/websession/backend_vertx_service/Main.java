package com.motadata.websession.backend_vertx_service;

import io.vertx.core.Vertx;

public class Main {
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();

    vertx.deployVerticle(new MainVerticle())
      .onFailure(err -> {
        System.err.println("❌ Failed to deploy MainVerticle: " + err.getMessage());
        System.exit(1);
      });
  }
}
