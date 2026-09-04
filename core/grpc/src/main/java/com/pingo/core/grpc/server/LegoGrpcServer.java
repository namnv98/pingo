package com.pingo.core.grpc.server;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.grpc.server.GrpcServer;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.NonNull;

/**
 * Mount 1 {@link GrpcServer} lên 1 {@code HttpServer} HTTP/2 thuần (host/port) — boilerplate dùng
 * chung cho mọi service nhận gRPC call nội bộ (hiện tại: colony, chặng harbor↔colony, xem
 * ARCHITECTURE.md mục 12). Đăng ký {@code callHandler} nào vào {@link GrpcServer} là việc của
 * {@link #registrar} truyền vào (biết {@code MethodDescriptor} cụ thể sinh từ {@code .proto} riêng
 * của từng nghiệp vụ) — lớp này chỉ lo mount + listen, không biết method nào được gọi.
 *
 * <p>KHÔNG tự set HTTP/2 keepalive/idleTimeout trên {@code HttpServer} sinh ra — cùng lý do với
 * {@link com.pingo.core.grpc.client.GrpcClientPool} (xem javadoc lớp đó): bug thật đã gặp với
 * vertx-grpc 4.5.5 + bidi-streaming.
 */
@Builder
public class LegoGrpcServer extends AbstractVerticle {

  private final int port;
  private final @NonNull String host;
  private final @NonNull Consumer<GrpcServer> registrar;

  @Override
  public void start(Promise<Void> promise) {
    var grpcServer = GrpcServer.server(vertx);
    registrar.accept(grpcServer);

    vertx.createHttpServer()
        .requestHandler(grpcServer)
        .listen(port, host)
        .<Void>mapEmpty()
        .onSuccess(promise::complete)
        .onFailure(promise::fail);
  }

  @Override
  public void stop(Promise<Void> stopPromise) {}
}
