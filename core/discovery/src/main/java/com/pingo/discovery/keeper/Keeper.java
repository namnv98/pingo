package com.pingo.discovery.keeper;

import com.pingo.discovery.router.Destination;
import com.lego.namnv98.event.EventObservable;
import java.util.List;

public interface Keeper extends EventObservable {

  static Keeper snapshotKeeper() {
    return new SnapshotKeeper();
  }

  void addDestinationChangeEvent(List<DestinationChangeEvent> destinationChangeEvents);

  void addDestinations(List<Destination> destinations);

  List<Destination> listingDestinations();
}
