package com.lego.namnv.core.common;

import java.util.List;

public interface EventSubscriber<T extends AbstractEvent> {
  default void notifyPublisherStatus(EventPublisher.Status status) {}

  List<Class<? extends T>> getEventTypes();

  boolean statusChangeOnContextRefresh();

  void subscribe(T event);
}
