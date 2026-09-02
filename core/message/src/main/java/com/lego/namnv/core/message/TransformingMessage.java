package com.lego.namnv.core.message;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.Delegate;

import java.util.function.Function;

import static java.util.Objects.isNull;

@AllArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class TransformingMessage<InputType, OutputType> implements LegoMessage<OutputType> {

    private final @NonNull Function<InputType, OutputType> transformer;

    @Override
    public final OutputType getBody() {
        var originBody = getOrigin().getBody();
        if (isNull(originBody))
            return null;
        return transformer.apply(originBody);
    }

    @Delegate(types = LegoMessage.class, excludes = Exclude.class)
    protected abstract LegoMessage<InputType> getOrigin();

    private interface Exclude<OutputType> {
        OutputType getBody();
    }
}
