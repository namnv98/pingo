package com.pingo.core.socket;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.http.ServerWebSocket;
import lombok.Builder;
import lombok.NonNull;

/**
 * Mount 1 WebSocket handler THUẦN (Vert.x native, không qua SockJS) lên 1 {@code HttpServer} riêng
 * (host/port/path) — boilerplate dùng chung cho mọi service có 1 client-facing socket endpoint
 * (hiện tại: harbor, xem ARCHITECTURE.md mục 3). Chỉ lo mount + accept/reject theo path + listen;
 * toàn bộ logic nghiệp vụ (auth, dispatch frame, quản lý session) nằm ở {@link #socketHandler}
 * truyền vào, lớp này không biết gì về nó — cùng triết lý "chỉ mở cổng, logic giao cho *Manager"
 * như {@code LegoHttpServer}.
 *
 * <p>Trước đây dùng {@code SockJSHandler} (hỗ trợ fallback XHR-polling/JSONP cho trình duyệt cũ
 * không có WebSocket thật, ra đời thời WebSocket còn chưa phổ biến ~2011-2013) — không còn lý do
 * tồn tại: mọi trình duyệt hiện đại đều hỗ trợ WebSocket nguyên sinh, và client thực tế (demo.html)
 * từ trước giờ đã luôn đi qua transport "raw websocket" của SockJS (bỏ qua toàn bộ cơ chế fallback +
 * envelope + heartbeat riêng của nó) — bản chất đã tương đương WebSocket thuần, chỉ tốn thêm 1
 * dependency + 1 lớp indirection không dùng tới. Đổi thẳng sang {@code webSocketHandler()} khớp
 * đúng cách các hệ thống thật (Slack — xem app.slack.com.har) làm: WebSocket thuần, không qua lớp
 * tương thích nào.
 */
@Builder
public class LegoSocketServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String host;
  private final @NonNull String path;
  private final @NonNull Handler<ServerWebSocket> socketHandler;

  @Override
  public void start(Promise<Void> promise) {
    var normalizedPath = normalizePath(path);
    vertx.createHttpServer()
        .webSocketHandshakeHandler(
            handshake -> {
              if (normalizedPath.equals(normalizePath(handshake.path()))) {
                handshake.accept();
              } else {
                handshake.reject(404);
              }
            })
        .webSocketHandler(socketHandler)
        .listen(port, host)
        .<Void>mapEmpty()
        .onSuccess(promise::complete)
        .onFailure(promise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {}

  private static String normalizePath(String path) {
    var normalized = path.trim();
    if (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
