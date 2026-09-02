package com.lego.namnv.async.task;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

public interface AsyncTask {

    Throwable getFailedCause();

    boolean isStarted();

    boolean isDone();

    boolean isSuccess();

    boolean isCancelled();

    void waitForDone() throws InterruptedException, ExecutionException;

    void cancel();

    CompletionStage<Void> execute();
}
