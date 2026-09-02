package com.lego.namnv.core.common.event;

import com.lego.namnv.core.common.AbstractEvent;

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
