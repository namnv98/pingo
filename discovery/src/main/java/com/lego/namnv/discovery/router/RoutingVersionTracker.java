package com.lego.namnv.discovery.router;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.connector.SignalingResponse;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Đồng bộ 1 node (harbor hoặc colony) với routing table hiện hành do beacon phát ra: lấy version
 * ban đầu qua EventBus request {@code "beacon_init"}, rồi lắng nghe mọi thay đổi tiếp theo qua
 * broadcast {@code "beacon"} — phần này giống hệt nhau ở cả 2 phía, tách ra đây để tránh 2 bản copy
 * tự trôi khác nhau theo thời gian (đã từng xảy ra: harbor sửa 1 log message nhỏ mà colony không
 * theo kịp). Phần KHÁC nhau thật sự giữa 2 phía là CÁCH áp dụng version mới sau khi nhận được —
 * harbor còn phải "di chuyển" các session đã authenticate sang đúng node colony mới
 * ({@code BackendLinkGateway#reconnectConversationToVersion}), colony chỉ cần track đúng version
 * mới nhất để {@code MessageDelivery.forwardToOwningNode} route đúng — nên phần đó để subclass tự
 * implement qua {@link #onSignalingChanged}. Xem {@code com.lego.harbor.ws.routing.RoutingVersionSync}
 * / {@code com.lego.colony.ws.routing.RoutingVersionSync}.
 */
@Slf4j
public abstract class RoutingVersionTracker {

  protected final Vertx vertx;
  protected final PingoConnector connector;
  protected volatile int currentVersion;

  protected RoutingVersionTracker(Vertx vertx, PingoConnector connector) {
    this.vertx = vertx;
    this.connector = connector;
    vertx.eventBus().consumer("beacon", this::onSignalingChanged);
    signalingInit();
  }

  public int currentVersion() {
    return currentVersion;
  }

  private void signalingInit() {
    vertx
        .eventBus()
        .request(
            "beacon_init",
            null,
            new DeliveryOptions(),
            event -> {
              if (event.failed()) {
                log.warn(
                    "beacon_init failed, retrying in 2s: {}",
                    event.cause() != null ? event.cause().getMessage() : "unknown");
                vertx.setTimer(2000, tid -> signalingInit());
                return;
              }
              var jsonObject = new JsonObject(event.result().body().toString());
              var signalingResponse = jsonObject.mapTo(SignalingResponse.class);
              connector.add(signalingResponse.getVersion(), signalingResponse.getDestinations());
              currentVersion = signalingResponse.getVersion();
              log.info(
                  "beacon_init: dong bo xong routing table version {}, {} colony destination",
                  currentVersion,
                  signalingResponse.getDestinations().size());
            });
  }

  /** Broadcast version mới từ beacon — subclass tự quyết định cách áp dụng (xem javadoc lớp). */
  protected abstract void onSignalingChanged(Message<JsonObject> jsonObjectMessage);
}
