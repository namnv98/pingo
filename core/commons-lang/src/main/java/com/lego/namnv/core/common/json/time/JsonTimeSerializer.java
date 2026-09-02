package com.lego.namnv.core.common.json.time;

import com.fasterxml.jackson.databind.JsonSerializer;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;

@AllArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class JsonTimeSerializer<T> extends JsonSerializer<T> {

    private final Class<T> myType;

    @Override
    public Class<T> handledType() {
        return myType;
    }

}
