package com.pingo.core.message.response;

import com.pingo.core.message.ProvidedHeadersMessage;

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
