package com.pingo.beacon;

import com.pingo.connector.Payload;
import com.pingo.connector.SignalingResponse;
import com.pingo.discovery.keeper.ChangeType;
import com.pingo.discovery.keeper.DestinationChangeEvent;
import com.pingo.beacon.connector.LegoConnector;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

/**
 * Publish (phát) routing/destination table hiện tại qua EventBus, để harbor và colony
 * dùng đó mà build consistent-hash router của chúng.
 *
 * <p>Đây chỉ là một gossip publisher (kiểu pub/sub thông báo thay đổi trạng thái) thuần tuý, hoàn toàn
 * không phải là socket — dù tên trước đây của class này là {@code SockjsSocketManager}, thực chất
 * không hề có WebSocket/SockJS nào ở đây cả (đã đổi tên lại cho đúng bản chất).
 */
@Slf4j
@NoArgsConstructor
public class RoutingGossipPublisher {
  private int version = 0;
  private Vertx vertx;
  private final BlockingQueue<DestinationChangeEvent> queue = new LinkedBlockingQueue<>();
  private final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);

  public void addToQueue(DestinationChangeEvent data) {
    queue.offer(data);
    if (data.getChangeType() == ChangeType.REMOVE) {
      // Pod bị xoá thì cần gossip NGAY — mọi node đang route tới pod đó cần biết sớm nhất có thể để
      // reconnect sang chỗ khác, mỗi giây chờ là mỗi giây tin nhắn gửi nhầm pod đã chết. Khác với ADD
      // (chờ batch 5s không sao — pod mới thêm chưa ai cần route tới ngay).
      executor.execute(this::processQueue);
    } else {
      executor.schedule(this::processQueue, 5, TimeUnit.SECONDS);
    }
  }

  public void processQueue() {
    List<DestinationChangeEvent> allElements = new ArrayList<>();
    queue.drainTo(allElements);
    if (CollectionUtils.isNotEmpty(allElements)) {
      version++;
      Payload payload = Payload.builder().version(version).allElements(allElements).build();
      log.info("gossip ra version {}: {} thay doi destination (publish toi colony/harbor)", version, allElements.size());
      publish("beacon", payload);
    }
  }

  private CompletionStage<Void> publish(String address, Payload downstreamData) {
    var deliveryOptions = new DeliveryOptions();
    return vertx
        .eventBus()
        .publisher(address, deliveryOptions) //
        .write(JsonObject.mapFrom(downstreamData)) //
        .toCompletionStage();
  }

  public RoutingGossipPublisher(Vertx vertx, LegoConnector connector) {
    this.vertx = vertx;
    vertx
        .eventBus()
        .consumer(
            "beacon_init",
            msg -> {
              log.debug("beacon_init requested, replying with version {}", version);
              msg.reply(
                  Json.encodeToBuffer(
                      SignalingResponse.builder()
                          .version(version)
                          .destinations(connector.getAll())
                          .build()));
            });
    connector.subscribe(event -> addToQueue((DestinationChangeEvent) event));
  }
}
