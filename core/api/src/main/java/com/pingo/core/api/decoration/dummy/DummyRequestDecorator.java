package com.pingo.core.api.decoration.dummy;

import com.pingo.core.api.IRequest;
import com.pingo.core.api.decoration.request.RequestDecorator;

import java.util.concurrent.CompletionStage;

public final class DummyRequestDecorator implements RequestDecorator<IRequest> {

    @Override
    public CompletionStage<IRequest> decorateRequest(IRequest request) {
        throw new UnsupportedOperationException();
    }
}
