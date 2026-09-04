package com.pingo.discovery.versionvector;

import com.pingo.discovery.keeper.Keeper;
import com.pingo.discovery.router.Router;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

@AllArgsConstructor
@Getter
public class DestinationVersion {
  private final @NonNull Router consistentRouter;
  private final @NonNull Keeper keeper;
}
