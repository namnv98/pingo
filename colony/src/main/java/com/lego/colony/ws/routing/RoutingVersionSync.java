package com.lego.colony.ws.routing;

import com.lego.namnv.connector.Payload;
import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.connector.SignalingResponse;
import io.vertx.core.AsyncResult;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Giữ node này đồng bộ với routing table hiện hành do beacon phát ra: lấy version ban đầu, rồi
 * lắng nghe mọi thay đổi tiếp theo. Khác với harbor's phiên bản của lớp này (xem
 * {@code com.lego.harbor.ws.routing.RoutingVersionSync} bên module harbor) — bên
 * colony không cần "di chuyển" gì khi version đổi (không có backend link theo session), chỉ
 * cần track đúng version mới nhất để {@code MessageDelivery.forwardToOwningNode} route đúng. Dùng
 * bởi {@code com.lego.colony.ws.ChatSessionManager}.
 */
@Slf4j
public class RoutingVersionSync {

  private final Vertx vertx;
  private final PingoConnector connector;
  private volatile int currentVersion;

  public RoutingVersionSync(Vertx vertx, PingoConnector connector) {
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
            (AsyncResult<Message<Object>> event) -> {
              if (event.failed()) {
                log.warn(
                    "beacon_init failed ({}), retrying in 2s",
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

  private void onSignalingChanged(Message<JsonObject> jsonObjectMessage) {
    Payload payload = jsonObjectMessage.body().mapTo(Payload.class);
    if (payload.getVersion() > 0) {
      connector.addDestinationChangeEvent(payload.getVersion(), payload.getAllElements());
      currentVersion = payload.getVersion();
      log.info(
          "nhan gossip tu beacon: routing table doi sang version {}, {} thay doi colony destination trong lan nay",
          currentVersion,
          payload.getAllElements().size());
    }
  }
}
