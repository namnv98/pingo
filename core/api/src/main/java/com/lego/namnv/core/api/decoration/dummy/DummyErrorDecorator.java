package com.lego.namnv.core.api.decoration.dummy;

import com.lego.namnv.core.api.decoration.error.ErrorDecorator;

import java.util.concurrent.CompletionStage;

public final class DummyErrorDecorator implements ErrorDecorator {

    @Override
    public CompletionStage<?> decorateError(Throwable ex) {
        throw new UnsupportedOperationException();
    }
}
