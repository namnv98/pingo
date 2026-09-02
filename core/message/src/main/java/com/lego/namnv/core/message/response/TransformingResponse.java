package com.lego.namnv.core.message.response;

import com.lego.namnv.core.message.TransformingMessage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;

import java.util.function.Function;

public class TransformingResponse<BodyType, OutputType> extends TransformingMessage<BodyType, OutputType> implements LegoResponse<OutputType> {

    @Getter(value = AccessLevel.PROTECTED)
    private final @NonNull LegoResponse<BodyType> origin;

    protected TransformingResponse(LegoResponse<BodyType> origin, Function<BodyType, OutputType> transformer) {
        super(transformer);
        this.origin = origin;
    }

    @Override
    public String toString() {
        return origin.toString();
    }
}