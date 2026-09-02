package com.lego.namnv.discovery.versionvector;

import com.lego.namnv.discovery.keeper.Keeper;
import com.lego.namnv.discovery.router.Router;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

@AllArgsConstructor
@Getter
public class DestinationVersion {
  private final @NonNull Router consistentRouter;
  private final @NonNull Keeper keeper;
}
