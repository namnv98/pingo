package com.pingo.async;

import java.util.concurrent.CompletionStage;

public interface AsyncCallable<T> {

    CompletionStage<T> run();
}
