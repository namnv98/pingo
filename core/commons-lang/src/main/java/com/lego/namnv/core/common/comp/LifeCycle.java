package com.lego.namnv.core.common.comp;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import lombok.SneakyThrows;

public interface LifeCycle {

    CompletableFuture<Void> start();

    CompletableFuture<Void> stop();

    default void startSync() {
        try {
            start().get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    default void stopSync() {
        try {
            stop().get();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    boolean isStarted();
}

