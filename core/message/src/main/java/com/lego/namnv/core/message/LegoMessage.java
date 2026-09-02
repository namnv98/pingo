package com.lego.namnv.core.message;

import java.util.Map;

public interface LegoMessage<BodyType> {

    String getHeader(String name);

    default String getHeader(String name, String defaultValue) {
        var value = getHeader(name);
        if (value == null || value.isBlank())
            return defaultValue;
        return value;
    }

    Iterable<Map.Entry<String, String>> getHeaders();

    BodyType getBody();
}