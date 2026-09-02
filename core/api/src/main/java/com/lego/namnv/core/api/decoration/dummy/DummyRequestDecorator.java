package com.lego.namnv.core.api.decoration.dummy;

import com.lego.namnv.core.api.IRequest;
import com.lego.namnv.core.api.decoration.request.RequestDecorator;

import java.util.concurrent.CompletionStage;

public final class DummyRequestDecorator implements RequestDecorator<IRequest> {

    @Override
    public CompletionStage<IRequest> decorateRequest(IRequest request) {
        throw new UnsupportedOperationException();
    }
}
