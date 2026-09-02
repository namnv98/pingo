package com.lego.namnv98.event;

public interface EventConsumer {

	void onEvent(Event event);

	String getName();
}
