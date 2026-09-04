package com.pingo.core.message.response;


import com.pingo.core.message.LegoMessage;

import java.util.function.Function;

public interface LegoResponse<BodyType> extends LegoMessage<BodyType> {

    default <T> LegoResponse<T> transform(Function<BodyType, T> translator) {
        return new TransformingResponse<>(this, translator);
    }
}

