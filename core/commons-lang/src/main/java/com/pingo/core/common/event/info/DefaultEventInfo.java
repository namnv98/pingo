package com.pingo.core.common.event.info;

import com.pingo.core.common.AbstractEvent;
import com.pingo.core.common.event.SerializableConsumer;

public class DefaultEventInfo<E extends AbstractEvent, T> implements EventInfo {
  private final Class<E> eventClazz;
  private final SerializableConsumer<T> eventMethod;
  private final String eventCode;

  public DefaultEventInfo(Class<E> eventClazz, SerializableConsumer<T> eventMethod, String eventCode) {
    this.eventClazz = eventClazz;
    this.eventMethod = eventMethod;
    this.eventCode = eventCode;
  }


  @Override
  public String getEventCode() {
    return eventCode;
  }

  @Override
  public Class<E> getEventClazz() {
    return eventClazz;
  }

  public SerializableConsumer<T> getEventMethod() {
    return eventMethod;
  }
}
