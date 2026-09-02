package com.lego.namnv.discovery.router;


import com.lego.namnv.discovery.keeper.Keeper;

public interface Router {
  static Router newConsistent(Keeper keeper, int lookupTableSize) {
    return new ConsistentRouter(keeper, lookupTableSize);
  }

  static Router newConsistent(Keeper keeper) {
    return new ConsistentRouter(keeper);
  }

  Destination route(RoutingKey routingEntity);
}
