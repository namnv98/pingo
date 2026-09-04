package com.pingo.connector;

import com.pingo.discovery.keeper.DestinationChangeEvent;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Incremental gossip update: the destination changes since the previous version. */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class Payload {
  private int version;
  private List<DestinationChangeEvent> allElements;
}
