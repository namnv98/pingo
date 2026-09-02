package com.lego.namnv.core.eventbus.exception;

import com.lego.namnv.core.common.exception.LegoException;
import com.lego.namnv.core.message.request.LegoRequest;
import io.vertx.core.buffer.Buffer;

public class RequestTimeoutException extends LegoException {

    private static final long serialVersionUID = -5332762708569714059L;

    public RequestTimeoutException(String address, LegoRequest<Buffer> request) {
        super("EventBus request timeout [topic=" + address + "] [request=" + request + "]");
    }

    public RequestTimeoutException() {
        super();
    }
}
