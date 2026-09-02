package com.lego.namnv.core.message;

import lombok.NonNull;

import java.util.Collections;
import java.util.Map;

public abstract class ProvidedHeadersMessage<BodyType> extends AbstractMessage<BodyType> {

    private final @NonNull Map<String, String> headers;

    protected ProvidedHeadersMessage(Map<String, String> headers) {
        this.headers = headers == null ? Map.of() : Collections.unmodifiableMap(headers);
    }

    protected ProvidedHeadersMessage() {
        this(Map.of());
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Iterable<Map.Entry<String, String>> getHeaders() {
        return headers.entrySet();
    }
}