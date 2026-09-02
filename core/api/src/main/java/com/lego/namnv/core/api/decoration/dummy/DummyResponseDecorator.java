package com.lego.namnv.core.api.decoration.dummy;

import com.lego.namnv.core.api.decoration.response.ResponseDecorator;

import java.util.concurrent.CompletionStage;

public final class DummyResponseDecorator implements ResponseDecorator<Object> {

    @Override
    public CompletionStage<?> decorateResponse(Object request) {
        throw new UnsupportedOperationException();
    }
}
