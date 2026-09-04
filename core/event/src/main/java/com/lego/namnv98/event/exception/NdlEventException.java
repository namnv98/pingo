package com.lego.namnv98.event.exception;

import com.pingo.core.common.exception.LegoException;

public class NdlEventException extends LegoException {

	private static final long serialVersionUID = -5949954040711164228L;

	public NdlEventException() {
		super();
	}

	public NdlEventException(String message) {
		super(message);
	}

	public NdlEventException(String message, Throwable cause) {
		super(message, cause);
	}

	public NdlEventException(Throwable cause) {
		super(cause);
	}
}
