package com.lego.namnv.core.common;

public interface EventPublisher {
  <T extends AbstractEvent> void publish(T event);

  enum Status {
    AFTER_CREATED,
    AFTER_REFRESHED,
    AFTER_TEMP_QUEUE_FLUSHED
  }
}
