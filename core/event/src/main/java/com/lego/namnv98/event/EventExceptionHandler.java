package com.lego.namnv98.event;

public interface EventExceptionHandler {

	void onException(String dispatcherName, EventConsumer consumer, Event event);
}
