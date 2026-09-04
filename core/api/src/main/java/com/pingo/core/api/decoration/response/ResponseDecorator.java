package com.pingo.core.api.decoration.response;

import com.pingo.core.api.decoration.Decorator;

import java.util.concurrent.CompletionStage;

public interface ResponseDecorator<T> extends Decorator<T> {

    CompletionStage<?> decorateResponse(T response);
}
