package com.pingo.core.common.exception;

public class LegoException extends RuntimeException {
    private static final long serialVersionUID = 1214147722601368570L;

    public LegoException() {
    }

    public LegoException(String message) {
        super(message);
    }

    public LegoException(String message, Throwable cause) {
        super(message, cause);
    }

    public LegoException(Throwable cause) {
        super(cause);
    }
}
