package com.lego.namnv.discovery.keeper;

import com.lego.namnv98.event.Event;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.lego.namnv.discovery.router.Destination;
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
public class DestinationChangeEvent implements Event {
  private ChangeType changeType;
  private Destination destination;
}
