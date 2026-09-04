package com.pingo.colony.ws;

import com.pingo.namnv.discovery.grpc.LinkGrpc;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.grpc.server.GrpcServer;
import lombok.Builder;
import lombok.NonNull;

/**
 * gRPC server nơi harbor kết nối vào (chặng harbor↔colony, xem ARCHITECTURE.md mục 12) — thay thế
 * hoàn toàn raw WebSocket server cũ. Mount {@link GrpcServer} (handler RPC {@code Link.Stream}) lên 1
 * {@code HttpServer} HTTP/2 thuần, cùng khuôn "verticle chỉ mở cổng, logic giao cho *Manager" như
 * {@code HarborSocketServer} bên harbor mount {@code SockJSHandler}.
 *
 * <p>Không cấu hình HTTP/2 keepalive ở đây lẫn phía client (xem {@code BackendStreamGateway#newClient})
 * — bật {@code setHttp2KeepAliveTimeout} phía client từng làm {@code response()} trễ đúng bằng thời
 * gian cấu hình (quirk vertx-grpc-client 4.5.5 với bidi-streaming). Liveness dựa vào timeout tầng
 * ứng dụng có sẵn ({@code HANDSHAKE_TIMEOUT_MS}, {@code MESSAGE_ACK_TIMEOUT_MS}).
 */
@Builder
public class ChatSocketServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String serverId;
  private final @NonNull String host;
  // Giữ field để khớp shape config chung với harbor (host/port/path) -- gRPC route theo method path
  // riêng sinh từ .proto, path ở đây không dùng tới.
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
