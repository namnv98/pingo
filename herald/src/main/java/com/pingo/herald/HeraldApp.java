package com.pingo.herald;

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
 * Service REST + 1 EventBus consumer (nhận broadcast "message_notify_candidates" từ colony, xem
 * {@code NotificationConsumer}) — dispatch REST qua {@code LegoHttpServer} (core/http), quét
 * {@code @RegisterHandler} trong {@code HeraldApiHandlers}, cùng pattern {@code HallApp}.
 * {@code NotificationConsumer} tự đăng ký consumer trong constructor (qua Guice, xem
 * {@code HeraldAppModule}), không cần deploy verticle riêng cho nó.
 */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HeraldApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  private final @NonNull LegoHttpServer httpServer;
  private final @NonNull AtomicBoolean ready;
  @SuppressWarnings("unused") // giu tham chieu song (Guice tao 1 lan, dang ky consumer trong constructor) -- khong goi method nao khac
  private final @NonNull NotificationConsumer notificationConsumer;

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
