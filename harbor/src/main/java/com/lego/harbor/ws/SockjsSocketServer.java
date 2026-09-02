package com.lego.harbor.ws;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import lombok.Builder;
import lombok.NonNull;

@Builder
public class SockjsSocketServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String serverId;
  private final @NonNull String host;
  private final @NonNull String path;
  private final SockjsSocketManager socketManager;

  @Override
  public void start(Promise<Void> promise) {
    var router = Router.router(vertx);
    var path = this.path.trim();
    if (!path.endsWith("/")) path += "/";
    if (!path.endsWith("*")) path += "*";

    router
        .route("/connect/*") //
        .subRouter(
            SockJSHandler.create(vertx) //
                .socketHandler(socketManager::onConnection));
    vertx
        .createHttpServer() //
        .requestHandler(router) //
        .listen(port, host) //
        .<Void>mapEmpty() //
        .onSuccess(promise::complete) //
        .onFailure(promise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {}
}
