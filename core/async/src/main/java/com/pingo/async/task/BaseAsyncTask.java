package com.pingo.async.task;

import java.util.concurrent.CompletionStage;

import com.pingo.async.AsyncCancellable;
import com.pingo.async.AsyncRunnable;

import lombok.AllArgsConstructor;
import lombok.NonNull;

@AllArgsConstructor
public class BaseAsyncTask extends AbstractAsyncTask {

    private final @NonNull AsyncRunnable runnable;

    @Override
    protected CompletionStage<?> doExecute() {
        return runnable.run();
    }

    @Override
    public void cancel() {
        if (runnable instanceof AsyncCancellable)
            ((AsyncCancellable) runnable).cancel();
        super.cancel();
    }
}
