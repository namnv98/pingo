package com.lego.namnv.async;

import java.util.concurrent.CompletionStage;

public interface AsyncCallable<T> {

    CompletionStage<T> run();
}
