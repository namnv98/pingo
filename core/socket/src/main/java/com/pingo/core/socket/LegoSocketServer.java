package com.pingo.core.socket;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import lombok.Builder;
import lombok.NonNull;

/**
 * Mount {@link SockJSHandler} lên 1 {@code HttpServer} riêng (host/port/path) — boilerplate dùng
 * chung cho mọi service có 1 client-facing socket endpoint (hiện tại: harbor, xem
 * ARCHITECTURE.md mục 3). Chỉ lo mount + normalize path + listen; toàn bộ logic nghiệp vụ (auth,
 * dispatch frame, quản lý session) nằm ở {@link #socketHandler} truyền vào, lớp này không biết gì
 * về nó — cùng triết lý "chỉ mở cổng, logic giao cho *Manager" như {@code LegoHttpServer}.
 */
@Builder
public class LegoSocketServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String host;
  private final @NonNull String path;
  private final @NonNull Handler<SockJSSocket> socketHandler;

  @Override
  public void start(Promise<Void> promise) {
    var router = Router.router(vertx);
    router.route(normalizePath(path)).subRouter(SockJSHandler.create(vertx).socketHandler(socketHandler));

    vertx.createHttpServer()
        .requestHandler(router)
        .listen(port, host)
        .<Void>mapEmpty()
        .onSuccess(promise::complete)
        .onFailure(promise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {}

  private static String normalizePath(String path) {
    var normalized = path.trim();
    if (!normalized.endsWith("/")) {
      normalized += "/";
    }
    if (!normalized.endsWith("*")) {
      normalized += "*";
    }
    return normalized;
  }
}
