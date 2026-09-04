package com.pingo.core.common.event.info;

import com.pingo.core.common.AbstractEvent;
import com.pingo.core.common.event.SerializableConsumer;

import java.util.ArrayList;
import java.util.List;

public class DefaultEventInfoBuilder implements EventInfo.InfoBuilder {
  List<EventInfo> list = new ArrayList<>();

  public List<EventInfo> build() {
    return list;
  }

  @Override
  public <E extends AbstractEvent, T> DefaultEventInfoBuilder add(Class<E> evenClazz, SerializableConsumer<T> mapperMethod, String eventCode) {
    list.add(new DefaultEventInfo<>(evenClazz, mapperMethod, eventCode));
    return this;
  }
}
