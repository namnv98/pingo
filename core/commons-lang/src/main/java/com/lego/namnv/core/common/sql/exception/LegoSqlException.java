package com.lego.namnv.core.common.sql.exception;

import com.lego.namnv.core.common.exception.LegoException;

public class LegoSqlException extends LegoException {

	private static final long serialVersionUID = -4573805486203338688L;

	public LegoSqlException() {
		super();
	}

	public LegoSqlException(String message) {
		super(message);
	}

	public LegoSqlException(String message, Throwable cause) {
		super(message, cause);
	}

	public LegoSqlException(Throwable cause) {
		super(cause);
	}
}
