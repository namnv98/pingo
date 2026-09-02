package com.lego.namnv.core.eventbus.exception;

import com.lego.namnv.core.common.exception.LegoException;
import com.lego.namnv.core.message.request.LegoRequest;
import io.vertx.core.buffer.Buffer;

public class NoHandlerException extends LegoException {

    private static final long serialVersionUID = -3716879239777071021L;

    public NoHandlerException(String address, LegoRequest<Buffer> request) {
        super("EventBus request timeout [topic=" + address + "] [request=" + request + "]");
    }

    public NoHandlerException(String address) {
        super("EventBus request timeout [topic=" + address + "]");
    }

    public NoHandlerException() {
        super();
    }
}
