package com.pingo.beacon.connector;

import static com.pingo.beacon.discovery.Keeper.k8sKeeper;

import com.pingo.core.common.comp.AbstractLifeCycle;
import com.pingo.core.common.comp.LifeCycle;
import com.pingo.core.common.support.Disposable;
import com.pingo.core.common.support.Fulfilled;
import com.pingo.core.eventbus.client.EventBusRpcSender;
import com.pingo.discovery.k8s.K8sClientConfig;
import com.pingo.discovery.router.Destination;
import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.EventEmitter;
import com.pingo.beacon.discovery.Keeper;
import io.vertx.core.eventbus.EventBus;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

/**
 * Triển khai (implementation) mặc định của {@link LegoConnector}: bọc quanh 1 {@link Keeper}, tự
 * start/stop nó cùng lúc với {@code sender} (nếu chúng có lifecycle — {@code LocalKeeper} thì
 * không có gì để start/stop cả), và forward mọi event của Keeper ra ngoài qua {@link #subscribe}.
 */
class DefaultLegoConnector extends AbstractLifeCycle implements LegoConnector {

  private final @NonNull EventBusRpcSender sender;
  private final @NonNull Keeper keeper;
  private final EventEmitter eventEmitter = EventEmitter.newEmitter();

  DefaultLegoConnector(@NotNull K8sClientConfig k8sClientConfig, EventBus eventBus, Function<String, CompletionStage<Boolean>> healthcheck) {
    this.sender = EventBusRpcSender.of(eventBus);
    this.keeper = k8sKeeper(k8sClientConfig, healthcheck);
    subscribeKeeper(keeper);
  }

  DefaultLegoConnector(Keeper keeper, EventBus eventBus) {
    this.sender = EventBusRpcSender.of(eventBus);
    this.keeper = keeper;
    subscribeKeeper(keeper);
  }

  private void subscribeKeeper(Keeper keeper) {
    keeper.subscribe(eventEmitter::dispatch);
  }

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) {
    var fs =
        Stream.of(sender, keeper) 
            .filter(LifeCycle.class::isInstance) 
            .map(o -> ((LifeCycle) o).start()) 
            .toArray(CompletableFuture[]::new);

    (fs.length == 0 ? Fulfilled.<Void>emptyStage() : CompletableFuture.allOf(fs)) 
        .<Fulfilled<Void>>handle(Fulfilled::of) 
        .thenAccept(ff -> ff.forward(startFuture));
  }

  @Override
  protected void doStop(CompletableFuture<Void> stopFuture) {
    var fs =
        Stream.of(sender, keeper) 
            .filter(LifeCycle.class::isInstance) 
            .map(o -> ((LifeCycle) o).stop()) 
            .toArray(CompletableFuture[]::new);

    (fs.length == 0 ? Fulfilled.<Void>emptyStage() : CompletableFuture.allOf(fs))
            .<Fulfilled<Void>>handle(Fulfilled::of)
            .thenAccept(ff -> ff.forward(stopFuture));
  }

  @Override
  public Disposable subscribe(EventConsumer consumer) {
    return eventEmitter.subscribe(consumer);
  }

  @Override
  public List<Destination> getAll() {
    return keeper.getAll();
  }
}
