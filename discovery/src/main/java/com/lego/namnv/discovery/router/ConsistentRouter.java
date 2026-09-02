package com.lego.namnv.discovery.router;

import com.lego.namnv.discovery.keeper.DestinationChangeEvent;
import com.lego.namnv.discovery.keeper.Keeper;
import com.lego.namnv98.event.Event;
import com.lego.namnv98.event.EventConsumer;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;

public class ConsistentRouter implements Router {
  private static final int DEFAULT_LOOKUP_TABLE_SIZE = 271;
  @NonNull private final Keeper keeper;

  @NonNull
  @Getter(AccessLevel.PACKAGE)
  private final Maglev routingTable;

  ConsistentRouter(@NonNull Keeper keeper) {
    this(keeper, DEFAULT_LOOKUP_TABLE_SIZE);
  }

  ConsistentRouter(@NonNull Keeper keeper, int lookupTableSize) {
    this.keeper = keeper;
    this.routingTable = new Maglev(lookupTableSize);
    this.keeper.subscribe(
        new EventConsumer() {
          @Override
          public void onEvent(Event event) {
            var change = event.cast(DestinationChangeEvent.class);
            switch (change.getChangeType()) {
              case ADD -> routingTable.addDestination(change.getDestination());
              case REMOVE -> routingTable.removeDestination(change.getDestination());
            }
          }

          @Override
          public String getName() {
            return "maglev router";
          }
        });
  }

  @Override
  public Destination route(RoutingKey routingEntity) {
    return this.routingTable.route(routingEntity);
  }
}
