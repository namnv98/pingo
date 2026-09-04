package com.lego.harbor;

import com.google.inject.Inject;
import com.lego.harbor.api.HealthCheckVerticle;
import com.lego.namnv.core.common.comp.AutoStopLifeCycle;
import com.lego.namnv.core.common.support.Fulfilled;
import com.lego.harbor.ws.HarborSessionManager;
import com.lego.harbor.ws.HarborSocketServer;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HarborApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  private final @NonNull HarborSocketServer upstreamVertical;
  private final @NonNull HealthCheckVerticle healthCheckVerticle;
  private final @NonNull HarborSessionManager sessionManager;

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) throws Exception {
    // Thứ tự đăng ký = thứ tự chạy lúc stop (xem AutoStopLifeCycle.executeStopTasks): báo GOAWAY +
    // đợi 1 nhịp ngắn cho client kịp reconnect sang pod khác, RỒI mới đóng hẳn Vertx.
    registerStopTask(sessionManager::drain);
    // Timeout bao ve: vertx.close() khi clustered qua Hazelcast co the mat nhieu thoi gian hoac
    // treo (da dieu tra bang thread dump + debug log that -- xem ARCHITECTURE.md/ghi chu lien quan
    // neu can chi tiet). 10s la du cho truong hop binh thuong -- qua thoi gian do thi bo qua,
    // khong chan shutdown tiep tuc (xem AutoStopLifeCycle.executeStopTasks).
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
