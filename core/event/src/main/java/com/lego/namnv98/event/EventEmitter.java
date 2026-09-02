package com.lego.namnv98.event;

import com.lego.namnv98.event.impl.BaseEventEmitter;

public interface EventEmitter extends EventObservable, EventDispatcher {

	static EventEmitter newEmitter() {
		return new BaseEventEmitter();
	}

	static EventEmitter newEmitter(ExceptionHandler exceptionHandler) {
		return new BaseEventEmitter(exceptionHandler);
	}

	void removeAllSubscription();

	<T extends Event> T dispatch(T event);

}
