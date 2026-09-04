package com.pingo.connector;

import com.pingo.discovery.router.Destination;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Full-snapshot gossip reply: the current destination table as of {@code version}. */
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class SignalingResponse {
  private int version;
  private List<Destination> destinations;
}
