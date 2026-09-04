package com.pingo.core.message.response;

import lombok.Getter;
import lombok.NonNull;

import java.util.Map;

public class ProvidedBodyResponse<BodyType> extends AbstractResponse<BodyType> {

    @Getter
    private final @NonNull BodyType body;

    public ProvidedBodyResponse(Map<String, String> headers, BodyType body) {
        super(headers);
        this.body = body;
    }

    public ProvidedBodyResponse(BodyType body) {
        super(Map.of());
        this.body = body;
    }
}
