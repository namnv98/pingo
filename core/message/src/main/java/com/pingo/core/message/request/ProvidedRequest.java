package com.pingo.core.message.request;

import com.pingo.core.message.ProvidedHeadersMessage;
import lombok.Getter;
import lombok.NonNull;

import java.util.Map;
import java.util.Map.Entry;

public class ProvidedRequest<BodyType> extends ProvidedHeadersMessage<BodyType> implements LegoRequest<BodyType> {

    @Getter
    private final BodyType body;

    private final @NonNull Map<String, String> params;

    ProvidedRequest(Map<String, String> headers, Map<String, String> params, BodyType body) {
        super(headers);
        this.params = params;
        this.body = body;
    }

    @Override
    public String getParam(String name) {
        return params.get(name);
    }

    @Override
    public Iterable<Entry<String, String>> getParams() {
        return params.entrySet();
    }
}