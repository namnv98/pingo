package com.pingo.core.api.decoration.dummy;

import com.pingo.core.api.decoration.error.ErrorDecorator;

import java.util.concurrent.CompletionStage;

public final class DummyErrorDecorator implements ErrorDecorator {

    @Override
    public CompletionStage<?> decorateError(Throwable ex) {
        throw new UnsupportedOperationException();
    }
}
