package com.lego.namnv.core.common.comp;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import com.lego.namnv.core.common.support.Disposable;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class AutoStopLifeCycle extends AbstractLifeCycle {

    private static final CompletionStage<Void> COMPLETED_STAGE = CompletableFuture.completedStage(null);

    @FunctionalInterface
    protected static interface AsyncStopTask {
        CompletionStage<?> stop();

        static AsyncStopTask ofSync(@NonNull final StopTask task) {
            return () -> {
                try {
                    task.stop();
                    return COMPLETED_STAGE;
                } catch (Throwable e) {
                    return CompletableFuture.failedStage(e);
                }
            };
        }
    }

    @FunctionalInterface
    protected static interface StopTask {
        void stop() throws Throwable;
    }

    private final List<AsyncStopTask> stopTasks = new CopyOnWriteArrayList<>();

    protected Disposable registerStopTask(AsyncStopTask task, int index) {
        stopTasks.add(index, task);
        return () -> stopTasks.remove(task);
    }

    protected Disposable registerStopTask(AsyncStopTask task) {
        stopTasks.add(task);
        return () -> stopTasks.remove(task);
    }

    protected Disposable registerStopTask(StopTask task, int index) {
        return registerStopTask(AsyncStopTask.ofSync(task), index);
    }

    protected Disposable registerStopTask(StopTask task) {
        return registerStopTask(AsyncStopTask.ofSync(task));
    }

    @Override
    protected final void doStop() throws Exception {
        // do nothing
    }

    private CompletionStage<Void> executeStopTasks(Iterator<AsyncStopTask> tasks) {
        if (!tasks.hasNext())
            return COMPLETED_STAGE;

        return tasks.next().stop() //
            .exceptionally(ex -> {
                log.error("stop life cycle component error", ex);
                return null;
            }) //
            .thenCompose(any -> executeStopTasks(tasks));
    }

    @Override
    protected final void doStop(CompletableFuture<Void> stopFuture) {
        executeStopTasks(stopTasks.iterator()).whenComplete((r, e) -> {
            stopTasks.clear();
            if (e != null) {
                stopFuture.completeExceptionally(e);
                return;
            }
            stopFuture.complete(null);
        });
    }
}

