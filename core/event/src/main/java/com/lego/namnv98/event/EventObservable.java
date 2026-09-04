package com.lego.namnv98.event;

import com.pingo.core.common.support.Disposable;
import com.lego.namnv98.event.impl.BaseEventConsumer;

import java.util.function.Consumer;

public interface EventObservable {

	Disposable subscribe(EventConsumer consumer);

	default <T extends Event> Disposable subscribe(String listenerName, Consumer<T> consumer) {
		return subscribe(BaseEventConsumer.<T>of(listenerName, consumer));
	}

	default <T extends Event> Disposable subscribe(Consumer<T> consumer) {
		return subscribe(null, consumer);
	}
}
