package com.lego.namnv.core.message.response;


import com.lego.namnv.core.message.LegoMessage;

import java.util.function.Function;

public interface LegoResponse<BodyType> extends LegoMessage<BodyType> {

    default <T> LegoResponse<T> transform(Function<BodyType, T> translator) {
        return new TransformingResponse<>(this, translator);
    }
}

