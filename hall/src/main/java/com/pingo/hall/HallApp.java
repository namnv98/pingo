package com.pingo.hall;

import com.google.inject.Inject;
import com.pingo.core.common.comp.AutoStopLifeCycle;
import com.pingo.core.common.support.Fulfilled;
import com.pingo.core.http.LegoHttpServer;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Service REST — CÓ cluster Hazelcast (cần {@code vertx.eventBus().publish()} lúc tạo conversation
 * mới, xem {@code HallApiHandlers#createConversation}/{@code HallBoot}), nhưng vẫn đơn giản hơn hẳn
 * {@code HarborApp}/{@code ColonyApp}: không tự quản lý vòng đời cluster (giao hết cho
 * {@code LegoBootStart}), chỉ deploy 1 verticle rồi đóng {@code Vertx} khi dừng. Route dispatch qua
 * {@code LegoHttpServer} (core/http) — quét {@code @RegisterHandler} trong {@code HallApiHandlers},
 * không tự viết {@code Router} tay như trước.
 */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HallApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  private final @NonNull LegoHttpServer httpServer;
  private final @NonNull AtomicBoolean ready;

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) {
    registerStopTask(() -> ready.set(false));
    registerStopTask(() -> vertx.close().toCompletionStage().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS));
    initUpstream(vertx) //
        .thenAccept(Fulfilled.forwardEmpty(startFuture)) //
        .exceptionally(Fulfilled.forwardException(startFuture));
  }

  private CompletionStage<Void> initUpstream(Vertx vertx) {
    return vertx.deployVerticle(httpServer).toCompletionStage().thenAccept(Fulfilled::empty);
  }
}
