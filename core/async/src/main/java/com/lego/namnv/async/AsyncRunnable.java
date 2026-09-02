package com.lego.namnv.async;

import java.util.concurrent.CompletionStage;

public interface AsyncRunnable {

    CompletionStage<?> run();
}
