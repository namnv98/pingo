package com.lego.namnv98.event;

import com.lego.namnv98.event.exception.NdlEventException;

public interface EventHub {

	EventObservable lookupObservable(String name);

	default EventObservable lookupObserableMandatory(String name) {
		var result = this.lookupObservable(name);
		if (result != null){
			return result;
		}
		throw new NdlEventException("EventObservable cannot be found for name: " + name);
	}
}
