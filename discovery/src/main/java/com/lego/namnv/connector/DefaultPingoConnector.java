package com.lego.namnv.connector;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.lego.namnv.core.common.comp.AbstractLifeCycle;
import com.lego.namnv.core.common.comp.LifeCycle;
import com.lego.namnv.core.common.support.Fulfilled;
import com.lego.namnv.core.common.support.ThreadUtils;
import com.lego.namnv.core.eventbus.client.EventBusRpcSender;
import com.lego.namnv.core.message.request.LegoRequest;
import com.lego.namnv.discovery.keeper.DestinationChangeEvent;
import com.lego.namnv.discovery.router.Destination;
import com.lego.namnv.discovery.router.RoutingKey;
import com.lego.namnv.discovery.versionvector.VersionVector;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DefaultPingoConnector extends AbstractLifeCycle implements PingoConnector {
  private final @NonNull EventBusRpcSender sender;

  private final VersionVector versionVector;
  private final Thread pollingThread;

  DefaultPingoConnector(EventBus eventBus) {
    this.sender = EventBusRpcSender.of(eventBus);
    this.versionVector = new VersionVector();
    this.pollingThread = new Thread(this::startWatchPre);
    this.pollingThread.start();
  }

  private void startWatchPre() {
    while (!Thread.currentThread().isInterrupted()) {
      if (!ThreadUtils.sleepSilence(5000)) {
        return;
      }
      if (log.isDebugEnabled() && nonNull(versionVector.get())) {
        log.debug(
            "current routing version={}, destinations={}",
            versionVector.getCurrentVersion(),
            versionVector.get().getKeeper().listingDestinations());
      }
    }
  }

  @Override
  public CompletionStage<Void> addDestinationChangeEvent(
      int version, List<DestinationChangeEvent> destinationChangeEvents) {
    versionVector.increment(version, destinationChangeEvents);
    var future = new CompletableFuture<Void>();
    doStart(future);
    return future;
  }

  @Override
  public CompletionStage<Void> add(int version, List<Destination> destinations) {
    versionVector.incrementAll(version, destinations);
    var future = new CompletableFuture<Void>();
    doStart(future);
    return future;
  }

  @Override
  public CompletionStage<Void> send(int currentVersion, LegoRequest<Buffer> request) {
    return sender.send(publicEndpoint(currentVersion, request), request);
  }

  @Override
  public CompletionStage<RouteResp> routing(int version, LegoRequest<Buffer> request) {
    try {
      var route = versionVector.get(version).getConsistentRouter().route((RoutingKey) request);
      return CompletableFuture.completedStage(
          new RouteResp(version, route.ip().getHostAddress(), route.name()));
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(exception);
    }
  }

  @Override
  protected void doStart(CompletableFuture<Void> startFuture) {
    if (isNull(versionVector.get())) {
      Fulfilled.<Void>emptyStage()
          .handle(Fulfilled::of) 
          .thenAccept(ff -> ff.forward(startFuture));
      return;
    }
    var fs =
        Stream.of(sender, versionVector.get().getKeeper()) 
            .filter(LifeCycle.class::isInstance) 
            .map(o -> ((LifeCycle) o).start()) 
            .toArray(CompletableFuture[]::new);

    (fs.length == 0 ? Fulfilled.<Void>emptyStage() : CompletableFuture.allOf(fs)) 
        .handle(Fulfilled::of) 
        .thenAccept(ff -> ff.forward(startFuture));
  }

  @Override
  protected void doStop(CompletableFuture<Void> stopFuture) {
    pollingThread.interrupt();
    var fs =
        Stream.of(sender, versionVector.get().getKeeper()) 
            .filter(LifeCycle.class::isInstance) 
            .map(o -> ((LifeCycle) o).stop()) 
            .toArray(CompletableFuture[]::new);

    (fs.length == 0 ? Fulfilled.<Void>emptyStage() : CompletableFuture.allOf(fs)) 
        .handle(Fulfilled::of) 
        .thenAccept(ff -> ff.forward(stopFuture));
  }

  private String publicEndpoint(int version, LegoRequest<Buffer> request) {
    return versionVector.get(version).getConsistentRouter().route((RoutingKey) request).name();
  }
}
