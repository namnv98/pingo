package com.pingo.core.common.support;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.util.concurrent.*;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.completedStage;

enum ScheduledExecutor {

    Service;

    private final ScheduledExecutorService schedExecSvc = Executors.newSingleThreadScheduledExecutor();

    public ScheduledExecutorService getDefault() {
        return schedExecSvc;
    }
}

@Getter
@AllArgsConstructor(staticName = "of")
public class Fulfilled<T> extends FulfilledUtils {
    private final T result;
    private final Throwable failedCause;

    public Fulfilled(T result) {
        this.result = result;
        this.failedCause = null;
    }

    public Fulfilled(@NonNull Throwable failedCause) {
        this.result = null;
        this.failedCause = failedCause;
    }

    public <R> CompletionStage<R> ifFailedCast(Object... unused) {
        if (failedCause == null)
            throw new IllegalStateException("cannot cast non-failed fulfilled");
        return CompletableFuture.failedStage(failedCause);
    }

    @SneakyThrows
    public T getResult() {
        if (failedCause != null)
            throw failedCause;
        return result;
    }

    protected CompletionStage<T> chainedResultStage(Object... any) {
        return failedCause != null //
                ? CompletableFuture.failedStage(failedCause) //
                : completedStage(result);
    }


    protected CompletionStage<Void> chainedVoidStage(Object... any) {
        return failedCause != null //
                ? CompletableFuture.failedStage(failedCause) //
                : completedStage(null);
    }

    public CompletionStage<T> toCompletionStage() {
        return chainedResultStage();
    }

    public CompletableFuture<T> toCompletableFuture() {
        return failedCause != null //
                ? CompletableFuture.failedFuture(failedCause)//
                : completedFuture(result);
    }

    public CompletableFuture<T> forward(CompletableFuture<T> output) {
        if (output == null)
            return toCompletableFuture();

        if (this.failedCause != null)
            output.completeExceptionally(failedCause);
        else
            output.complete(result);

        return output;
    }
}
