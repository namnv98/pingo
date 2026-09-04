package com.pingo.core.common.event;

import com.pingo.core.common.AbstractEvent;

public abstract class DefaultAbstractEvent<T> extends AbstractEvent {
  private final T entity;

  public DefaultAbstractEvent(Object source, T entity) {
    super(source);
    this.entity = entity;
  }

  public T getEntity() {
    return entity;
  }
}
