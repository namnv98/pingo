package com.lego.namnv.core.message.response;

import com.lego.namnv.core.message.ProvidedHeadersMessage;

import java.util.Map;

public abstract class AbstractResponse<BodyType> extends ProvidedHeadersMessage<BodyType>
    implements LegoResponse<BodyType> {

    protected AbstractResponse(Map<String, String> headers) {
        super(headers);
    }

    protected AbstractResponse() {
        this(Map.of());
    }
}
