package com.lego.namnv.async.support;

import static java.util.concurrent.CompletableFuture.completedStage;
import static java.util.concurrent.CompletableFuture.failedStage;

import java.util.concurrent.CompletionStage;

import com.lego.namnv.core.common.support.Fulfilled;
import io.vertx.core.Vertx;

public class Asyncs {

    @FunctionalInterface
    public static interface ThrowableSupplier<T> {
        T get() throws Throwable;
    }

    @FunctionalInterface
    public static interface ThrowableRunnable {
        void run() throws Throwable;
    }

    public static CompletionStage<Void> tryExecuteNonBlocking(ThrowableRunnable runnable) {
        return tryExecuteNonBlocking(() -> {
            runnable.run();
            return null;
        });
    }

    public static <T> CompletionStage<T> tryExecuteNonBlocking(ThrowableSupplier<T> supplier) {
        var ctx = Vertx.currentContext();
        if (ctx == null)
            return Fulfilled.emptyStage().thenComposeAsync(any -> {
                try {
                    return completedStage(supplier.get());
                } catch (Throwable e1) {
                    return failedStage(e1);
                }
            });

        return ctx.<T>executeBlocking(promise -> {
            try {
                promise.complete(supplier.get());
            } catch (Throwable e) {
                promise.fail(e);
            }
        }).toCompletionStage();
    }

}
