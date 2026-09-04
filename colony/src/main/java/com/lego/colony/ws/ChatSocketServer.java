package com.lego.colony.ws;

import com.lego.namnv.discovery.grpc.LinkGrpc;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.grpc.server.GrpcServer;
import lombok.Builder;
import lombok.NonNull;

/**
 * gRPC server — nơi harbor kết nối vào (chặng harbor↔colony, xem ARCHITECTURE.md mục 12). Thay thế
 * hoàn toàn raw WebSocket server trước đó; mount {@link GrpcServer} (handler cho RPC {@code Link.Stream})
 * lên 1 {@code HttpServer} HTTP/2 thuần, giống hệt cách {@code SockjsSocketServer} bên harbor mount
 * {@code SockJSHandler} lên router — cùng 1 khuôn "verticle chỉ lo mở cổng, logic giao cho *Manager".
 * Không cấu hình keepalive HTTP/2 tầng transport ở đây lẫn phía client (xem
 * {@code BackendLinkGateway#newClient}) — tự bắt được bug thật khi test: bật
 * {@code setHttp2KeepAliveTimeout} phía client làm {@code response()} bị trễ ĐÚNG BẰNG khoảng thời
 * gian cấu hình trước khi resolve (nghi là quirk của vertx-grpc-client 4.5.5 với bidi-streaming),
 * nên cố tình để mặc định. Liveness của stream dựa vào timeout ở tầng ứng dụng đã có sẵn
 * ({@code HANDSHAKE_TIMEOUT_MS} cho SUBSCRIBE, {@code MESSAGE_ACK_TIMEOUT_MS} cho MESSAGE).
 */
@Builder
public class ChatSocketServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String serverId;
  private final @NonNull String host;
  // Giữ field này để khớp shape config dùng chung với harbor (cùng có host/port/path); gRPC route
  // theo method path riêng của chính nó (sinh từ .proto), path ở đây không được dùng tới.
  private final @NonNull String path;
  private final ChatSessionManager sessionManager;

  @Override
  public void start(Promise<Void> promise) {
    var grpcServer = GrpcServer.server(vertx);
    grpcServer.callHandler(LinkGrpc.getStreamMethod(), sessionManager::onConnection);

    vertx
        .createHttpServer()
        .requestHandler(grpcServer)
        .listen(port, host)
        .<Void>mapEmpty()
        .onSuccess(promise::complete)
        .onFailure(promise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {}
}
