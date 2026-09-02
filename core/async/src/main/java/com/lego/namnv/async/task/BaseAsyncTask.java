package com.lego.namnv.async.task;

import java.util.concurrent.CompletionStage;

import com.lego.namnv.async.AsyncCancellable;
import com.lego.namnv.async.AsyncRunnable;

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
