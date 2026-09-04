package com.pingo.colony.ws.routing;

import com.pingo.connector.Payload;
import com.pingo.connector.PingoConnector;
import com.pingo.discovery.router.RoutingVersionTracker;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Phần đồng bộ chung với beacon nằm ở {@link RoutingVersionTracker} — colony không cần "di
 * chuyển" gì khi version đổi (không có backend link theo session), chỉ cần track đúng version mới
 * nhất để {@code MessageDelivery.forwardToOwningNode} route đúng. Dùng bởi
 * {@code com.lego.colony.ws.ChatSessionManager}.
 */
@Slf4j
public class RoutingVersionSync extends RoutingVersionTracker {

  public RoutingVersionSync(Vertx vertx, PingoConnector connector) {
    super(vertx, connector);
  }

  @Override
  protected void onSignalingChanged(Message<JsonObject> jsonObjectMessage) {
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
