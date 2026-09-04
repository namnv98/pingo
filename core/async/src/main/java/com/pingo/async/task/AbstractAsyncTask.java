package com.pingo.async.task;

import com.pingo.core.common.support.Fulfilled;
import lombok.Getter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static com.pingo.core.common.support.FulfilledUtils.forwardEmpty;
import static com.pingo.core.common.support.FulfilledUtils.forwardException;

public abstract class AbstractAsyncTask implements AsyncTask {

    @Getter
    private volatile Throwable failedCause;
    private AtomicReference<CompletableFuture<Void>> futureHolder = new AtomicReference<>();

    @Override
    public void waitForDone() throws InterruptedException, ExecutionException {
        var future = futureHolder.get();
        if (future == null)
            throw new IllegalStateException("Kafka task not started yet");
        future.get();
    }

    @Override
    public void cancel() {
        var future = futureHolder.get();
        if (future == null)
            throw new IllegalStateException("Task isn't started yet, cannot cancel");
        future.cancel(true);
    }

    @Override
    public boolean isStarted() {
        return futureHolder.get() != null;
    }

    @Override
    public boolean isDone() {
        var future = futureHolder.get();
        if (future == null)
            return false;
        return future.isDone();
    }

    @Override
    public boolean isSuccess() {
        var future = futureHolder.get();
        if (future == null)
            return false;
        return future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally();
    }

    @Override
    public boolean isCancelled() {
        var future = futureHolder.get();
        if (future == null)
            return false;
        return future.isCancelled();
    }

    @Override
    public final CompletionStage<Void> execute() {
        var f = futureHolder.get();
        if (f != null)
            return f;
        var future = new CompletableFuture<Void>();
        if (futureHolder.compareAndSet(null, future)) {
            doExecute(future);
            return future;
        }
        return futureHolder.get();
    }

    protected void doExecute(CompletableFuture<Void> future) {
        try {
            doExecute() //
                    .thenAccept(forwardEmpty(future)) //
                    .exceptionally(forwardException(future::completeExceptionally));
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
    }

    protected CompletionStage<?> doExecute() {
        return Fulfilled.emptyStage();
    }
}
