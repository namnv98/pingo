package com.lego.beacon.connector;

import com.lego.namnv.discovery.k8s.K8sClientConfig;
import com.lego.namnv.discovery.router.Destination;
import com.lego.namnv98.event.EventObservable;
import com.lego.beacon.discovery.keeper.Keeper;
import io.vertx.core.eventbus.EventBus;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Cầu nối giữa {@link Keeper} (nguồn phát hiện destination) và phần còn lại của beacon: bọc
 * quanh 1 Keeper, expose {@link #getAll()} + khả năng {@code subscribe} thay đổi (qua
 * {@link EventObservable}), để {@code RoutingGossipPublisher} dùng mà phát (publish) đi cho
 * colony/harbor. Không nên nhầm với {@code com.lego.namnv.connector.PingoConnector}
 * bên module {@code discovery} — 2 interface đó tên gần giống nhưng phục vụ vai trò khác: PingoConnector
 * là phía CLIENT tra cứu routing (dùng bởi colony/harbor), LegoConnector là phía
 * SERVER tổng hợp/phát destination (chỉ dùng trong beacon).
 */
public interface LegoConnector extends EventObservable {
  static LegoConnector newDefault(K8sClientConfig k8sClientConfig, EventBus eventBus, Function<String, CompletionStage<Boolean>> healthcheck) {
    return new DefaultLegoConnector(k8sClientConfig, eventBus, healthcheck);
  }

  static LegoConnector newLocal(EventBus eventBus, List<Destination> destinations) {
    return new DefaultLegoConnector(Keeper.localKeeper(destinations), eventBus);
  }

  List<Destination> getAll();
}
