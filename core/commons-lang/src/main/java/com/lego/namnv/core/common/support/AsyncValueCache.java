package com.lego.namnv.core.common.support;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@RequiredArgsConstructor
public class AsyncValueCache<T> {

    private final @NonNull Supplier<CompletionStage<T>> supplier;
    private final long ttlMillis;

    private volatile long updatedAt;
    private volatile CompletionStage<T> value;

    public AsyncValueCache(Supplier<CompletionStage<T>> supplier) {
        this(supplier, -1);
    }

    private boolean isDirty(long now) {
        if (ttlMillis <= 0)
            return value == null;
        return value == null || (now - updatedAt > ttlMillis);
    }

    public CompletionStage<T> get() {
        var now = System.currentTimeMillis();

        if (isDirty(now)) {
            synchronized (this) {
                if (isDirty(now)) {
                    value = fetchValue();
                    updatedAt = now;
                }
            }
        }

        return value;
    }

    private CompletableFuture<T> fetchValue() {
        try {
            var f = new CompletableFuture<T>();
            supplier.get() //
                    .thenAccept(f::complete) //
                    .exceptionally(e -> {
                        reset();
                        f.completeExceptionally(e);
                        return null;
                    });
            return f;
        } catch (Throwable e) {
            return null;
        }
    }

    public void reset() {
        synchronized (this) {
            value = null;
        }
    }
}
