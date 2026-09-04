package com.lego.namnv98.event.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.pingo.core.common.support.Disposable;
import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.ExceptionHandler;
import com.lego.namnv98.event.Event;
import com.lego.namnv98.event.EventEmitter;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

@Log4j2
@AllArgsConstructor
public class BaseEventEmitter implements EventEmitter {

	private final @NonNull ExceptionHandler exceptionHandler;

	private final List<EventConsumer> subscribers = new CopyOnWriteArrayList<>();

	public BaseEventEmitter() {
		this(BaseEventEmitter::defaultExceptionHandler);
	}

	private static boolean defaultExceptionHandler(Event event, EventConsumer consumer, Throwable failedCause) {
		log.error("Error while handling event: {}, on consumer: {}", event, consumer.getName(), failedCause);
		return false;
	}

	@Override
	public void removeAllSubscription() {
		subscribers.clear();
	}

	@Override
	public Disposable subscribe(@NonNull EventConsumer consumer) {
		if (subscribers.add(consumer))
			return () -> subscribers.remove(consumer);
		return null;
	}

	@Override
	public <T extends Event> T dispatch(T event) {
		for (var subscriber : subscribers) {
			try {
				subscriber.onEvent(event);
			} catch (Throwable e) {
				if (!exceptionHandler.onEventHandlingException(event, subscriber, e))
					break;
			}
		}
		return event;
	}
}
