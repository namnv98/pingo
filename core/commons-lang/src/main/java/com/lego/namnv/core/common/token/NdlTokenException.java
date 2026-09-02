package com.lego.namnv.core.common.token;

import com.lego.namnv.core.common.exception.LegoException;

public class NdlTokenException extends LegoException {

    private static final long serialVersionUID = -4332721744898319294L;

    public NdlTokenException() {
        super();
    }

    public NdlTokenException(String message) {
        super(message);
    }

    public NdlTokenException(String message, Throwable cause) {
        super(message, cause);
    }

    public NdlTokenException(Throwable cause) {
        super(cause);
    }
}
