package com.lego.namnv.connector;

import com.lego.namnv.core.message.request.LegoRequest;
import com.lego.namnv.discovery.keeper.DestinationChangeEvent;
import com.lego.namnv.discovery.router.Destination;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface PingoConnector {

  static PingoConnector newDefault(EventBus eventBus) {
    return new DefaultPingoConnector(eventBus);
  }

  CompletionStage<Void> send(int currentVersion, LegoRequest<Buffer> request);

  CompletionStage<RouteResp> routing(int currentVersion, LegoRequest<Buffer> request);

  CompletionStage<Void> addDestinationChangeEvent(int version, List<DestinationChangeEvent> destinationChangeEvents);

  CompletionStage<Void> add(int version, List<Destination> destinations);
}
