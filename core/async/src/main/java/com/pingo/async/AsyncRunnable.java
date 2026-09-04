package com.pingo.async;

import java.util.concurrent.CompletionStage;

public interface AsyncRunnable {

    CompletionStage<?> run();
}
