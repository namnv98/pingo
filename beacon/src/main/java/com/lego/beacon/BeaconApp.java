package com.lego.beacon;

import com.google.inject.Inject;
import com.lego.namnv.core.common.comp.AutoStopLifeCycle;
import io.vertx.core.Vertx;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Điểm khởi động (entry point) chính của service beacon — vòng đời (lifecycle) của app, không
 * chứa business logic gì (toàn bộ nằm trong {@link RoutingGossipPublisher}).
 */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class BeaconApp extends AutoStopLifeCycle {

  private final @NonNull Vertx vertx;
  // Field này không được đọc ở đâu cả trong class — nhưng KHÔNG được xoá: khai báo nó ở đây là
  // để Guice tiêm (inject) và khởi tạo RoutingGossipPublisher ngay khi BeaconApp được tạo, qua
  // đó chạy side-effect trong constructor của nó (đăng ký consumer "beacon_init" trên EventBus).
  // Nếu xoá field này, RoutingGossipPublisher sẽ chỉ được khởi tạo khi có ai đó thật sự inject nó
  // ở nơi khác — mà hiện không có nơi nào khác cả, nên app sẽ khởi động mà thiếu mất chức năng gossip.
  private final RoutingGossipPublisher routingGossipPublisher;

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) throws Exception {
    // Timeout bao ve: vertx.close() khi clustered qua Hazelcast co the treo vo thoi han (xem
    // HarborApp — bug da biet cua vertx-hazelcast khi tu truyen vao 1 HazelcastInstance co san).
    registerStopTask(() -> vertx.close().toCompletionStage().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS));
  }
}
