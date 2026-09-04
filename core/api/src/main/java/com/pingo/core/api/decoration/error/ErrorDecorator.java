package com.pingo.core.api.decoration.error;


import com.pingo.core.api.decoration.Decorator;

import java.util.concurrent.CompletionStage;

public interface ErrorDecorator extends Decorator<Throwable> {

    CompletionStage<?> decorateError(Throwable ex);
}
