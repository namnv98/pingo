package com.pingo.core.common;

public class AbstractEvent extends ApplicationEvent {
  protected transient Object source;
  protected transient String id;

  public AbstractEvent(Object source) {
    super(source);
    this.source = source;
  }

  @Override
  public Object getSource() {
    return source;
  }

  public void setSource(Object source) {
    this.source = source;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }
}
