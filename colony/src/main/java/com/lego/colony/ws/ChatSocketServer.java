package com.lego.colony.ws;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import lombok.Builder;
import lombok.NonNull;

/** Plain WebSocket server, nơi các node colony/harbor khác kết nối vào. Không phải SockJS. */
@Builder
public class ChatSocketServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String serverId;
  private final @NonNull String host;
  // Giữ field này để khớp shape config dùng chung với harbor (cùng có host/port/path);
  // nhưng handler WebSocket thuần (raw) chấp nhận mọi upgrade path, nên path không được dùng tới ở đây.
  private final @NonNull String path;
  private final ChatSessionManager sessionManager;

  @Override
  public void start(Promise<Void> promise) {
    HttpServer server = vertx.createHttpServer();
    server
        .webSocketHandler(sessionManager::onConnection)
        .listen(port, host)
        .<Void>mapEmpty()
        .onSuccess(promise::complete)
        .onFailure(promise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {}
}
