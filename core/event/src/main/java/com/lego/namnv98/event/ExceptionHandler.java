package com.lego.namnv98.event;

public interface ExceptionHandler {

    boolean onEventHandlingException(Event event, EventConsumer consumer, Throwable failedCause);
}
