package com.pingo.async;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pingo.core.common.support.Fulfilled;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

public class AsyncExecutionChain<KeyType> {

    private final Cache<KeyType, AtomicReference<CompletableFuture<Void>>> executionChain;

    public AsyncExecutionChain(Duration idleTime) {
        this.executionChain = Caffeine.newBuilder() //
                .expireAfterAccess(idleTime) //
                .build();
    }

    private CompletionStage<Void> runThenForward(AsyncRunnable asyncRunnable, CompletableFuture<Void> output) {
        asyncRunnable.run() //
                .thenAccept(Fulfilled.forwardEmpty(output)) //
                .exceptionally(Fulfilled.forwardException(output));
        return output;
    }

    public CompletionStage<Void> execute(KeyType key, AsyncRunnable asyncRunnable, boolean ignorePrevFailure,
            CompletableFuture<Void> output) {
        var oldRef = new AtomicReference<CompletionStage<Void>>();
        var target = output != null ? output : new CompletableFuture<Void>();
        executionChain.get(key, k -> new AtomicReference<>(Fulfilled.emptyFuture())) //
                .accumulateAndGet(target, (prev, next) -> {
                    oldRef.set(prev);
                    return next;
                });

        var prev = ignorePrevFailure //
                ? oldRef.get().exceptionally(Fulfilled::logAndIgnore) //
                : oldRef.get().exceptionally(Fulfilled.forwardException(target));

        prev.thenCompose(unused -> target.isDone() ? target : runThenForward(asyncRunnable, target));

        return target;
    }

    public CompletionStage<Void> execute(KeyType key, AsyncRunnable asyncRunnable, boolean ignorePrevFailure) {
        return execute(key, asyncRunnable, ignorePrevFailure, null);
    }

    public CompletionStage<Void> execute(KeyType key, AsyncRunnable asyncRunnable, CompletableFuture<Void> output) {
        return execute(key, asyncRunnable, false, output);
    }

    public CompletionStage<Void> execute(KeyType key, AsyncRunnable asyncRunnable) {
        return execute(key, asyncRunnable, false);
    }

    public CompletionStage<Void> executeIgnorePrevFailure(KeyType key, AsyncRunnable asyncRunnable) {
        return execute(key, asyncRunnable, true);
    }

    public CompletionStage<Void> execute(KeyType key, Runnable runnable) {
        return executeIgnorePrevFailure(key, () -> {
            try {
                runnable.run();
            } catch (Throwable e) {
                return CompletableFuture.failedStage(e);
            }
            return Fulfilled.emptyStage();
        });
    }

}