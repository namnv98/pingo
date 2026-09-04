package com.pingo.core.boot.start.yaml.ex;


import com.pingo.core.common.exception.LegoException;

public class PrebootException extends LegoException {

    private static final long serialVersionUID = 1101095902445492418L;

    public PrebootException() {
        super();
    }

    public PrebootException(String message) {
        super(message);
    }

    public PrebootException(String message, Throwable cause) {
        super(message, cause);
    }

    public PrebootException(Throwable cause) {
        super(cause);
    }
}

