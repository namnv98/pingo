package com.pingo.harbor;

import com.google.inject.Inject;
import com.pingo.harbor.api.HealthCheckVerticle;
import com.pingo.core.common.comp.AutoStopLifeCycle;
import com.pingo.core.common.support.Fulfilled;
import com.pingo.harbor.ws.HarborSessionManager;
import com.pingo.core.socket.LegoSocketServer;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HarborApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  private final @NonNull LegoSocketServer upstreamVertical;
  private final @NonNull HealthCheckVerticle healthCheckVerticle;
  private final @NonNull HarborSessionManager sessionManager;

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) throws Exception {
    // Thứ tự đăng ký = thứ tự chạy lúc stop: báo GOAWAY, đợi client reconnect sang pod khác, rồi mới đóng Vertx.
    registerStopTask(sessionManager::drain);
    // vertx.close() qua Hazelcast cluster có thể treo lâu -- timeout 10s để không chặn shutdown.
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
