package com.lego.namnv.core.common.comp;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractLifeCycle implements LifeCycle {

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<Void>> startFuture = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> stopFuture = new AtomicReference<>();

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public final CompletableFuture<Void> start() {
        var f = startFuture.get();
        if (f != null)
            return f;

        f = new CompletableFuture<Void>();
        if (startFuture.compareAndSet(null, f)) {
            f.whenComplete(this::onStarted);
            try {
                doStart(f);
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
            return f;
        }
        return startFuture.get();
    }

    private void onStarted(Void any, Throwable ex) {
        if (ex == null) {
            started.set(true);
            return;
        }

        startFuture.set(null);
        onStarted(ex);
    }

    protected void onStarted(Throwable ex) {
        // do nothing
    }

    protected void doStart(CompletableFuture<Void> startFuture) throws Exception {
        doStart();
        startFuture.complete(null);
    }

    protected void doStart() throws Exception {

    }

    @Override
    public final CompletableFuture<Void> stop() {
        var f = stopFuture.get();
        if (f != null)
            return f;

        f = new CompletableFuture<Void>();
        if (stopFuture.compareAndSet(null, f)) {
            f.whenComplete(this::onStopped);
            try {
                doStop(f);
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
            return f;
        }
        return stopFuture.get();
    }

    private void onStopped(Void any, Throwable ex) {
        started.set(false);
        startFuture.set(null);
        stopFuture.set(null);
        onStopped(ex);
    }

    protected void onStopped(Throwable ex) {
        // do nothing
    }

    protected void doStop(CompletableFuture<Void> stopFuture) {
        try {
            doStop();
            stopFuture.complete(null);
        } catch (Exception e) {
            stopFuture.completeExceptionally(e);
        }
    }

    protected void doStop() throws Exception {

    }
}

