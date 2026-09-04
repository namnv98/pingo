package com.pingo.colony;

import com.google.inject.Inject;
import com.pingo.colony.api.HealthCheckVerticle;
import com.pingo.core.common.comp.AutoStopLifeCycle;
import com.pingo.core.common.support.Fulfilled;
import com.pingo.colony.ws.ChatSessionManager;
import com.pingo.core.grpc.server.LegoGrpcServer;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class ColonyApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  private final @NonNull LegoGrpcServer upstreamVertical;
  private final @NonNull HealthCheckVerticle healthCheckVerticle;
  private final @NonNull ChatSessionManager sessionManager;

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) {
    // Thứ tự đăng ký = thứ tự lúc stop: draining trước, chờ gossip REMOVE lan ra, rồi mới đóng
    // Vertx — cùng pattern đã dùng cho harbor.
    registerStopTask(sessionManager::drain);
    // Timeout 10s bảo vệ: vertx.close() qua Hazelcast cluster có thể treo vô thời hạn (xem HarborApp).
    registerStopTask(() -> vertx.close().toCompletionStage().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS));
    initUpstream(vertx) //
        .thenAccept(Fulfilled.forwardEmpty(startFuture)) //
        .exceptionally(Fulfilled.forwardException(startFuture));
  }

  private CompletionStage<Void> initUpstream(Vertx vertx) {
    vertx.deployVerticle(healthCheckVerticle);
    return vertx.deployVerticle(upstreamVertical).toCompletionStage().thenAccept(Fulfilled::empty);
  }
}
