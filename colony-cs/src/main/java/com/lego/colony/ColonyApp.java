package com.lego.colony;

import com.google.inject.Inject;
import com.lego.colony.api.RestApiVerticle;
import com.lego.namnv.core.common.comp.AutoStopLifeCycle;
import com.lego.namnv.core.common.support.Fulfilled;
import com.lego.colony.ws.ChatSessionManager;
import com.lego.colony.ws.ChatSocketServer;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class ColonyApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  private final @NonNull ChatSocketServer upstreamVertical;
  private final @NonNull RestApiVerticle restApiVerticle;
  private final @NonNull ChatSessionManager sessionManager;

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) {
    // Thứ tự đăng ký = thứ tự chạy lúc stop: đánh dấu draining + chờ 1 nhịp ngắn cho gossip REMOVE
    // kịp lan ra, RỒI mới đóng hẳn Vertx — cùng pattern đã dùng cho harbor.
    registerStopTask(sessionManager::drain);
    // Timeout bao ve: vertx.close() khi clustered qua Hazelcast co the treo vo thoi han (xem
    // HarborApp — bug da biet cua vertx-hazelcast khi tu truyen vao 1 HazelcastInstance co san).
    registerStopTask(() -> vertx.close().toCompletionStage().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS));
    initUpstream(vertx) //
        .thenAccept(Fulfilled.forwardEmpty(startFuture)) //
        .exceptionally(Fulfilled.forwardException(startFuture));
  }

  private CompletionStage<Void> initUpstream(Vertx vertx) {
    vertx.deployVerticle(restApiVerticle);
    return vertx.deployVerticle(upstreamVertical).toCompletionStage().thenAccept(Fulfilled::empty);
  }
}
