package com.pingo.core.api.decoration.request;

import com.pingo.core.api.IRequest;
import com.pingo.core.api.decoration.Decorator;

import java.util.concurrent.CompletionStage;

public interface RequestDecorator<T extends IRequest> extends Decorator<T> {

    CompletionStage<? extends IRequest> decorateRequest(T request);
}
