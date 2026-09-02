package com.lego.namnv.async.task;

import com.lego.namnv.core.common.support.Fulfilled;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@RequiredArgsConstructor
public class BaseAsyncTaskSequence<T extends AsyncTask> extends AbstractAsyncTask implements AsyncTaskSequence<T> {

    private final @NonNull List<T> tasks;
    private volatile boolean terminated;

    @Getter
    private volatile T currentTask;

    @Override
    public T getLastDoneTask() {
        T result = null;
        for (var task : tasks) {
            if (!task.isDone())
                break;
            result = task;
        }
        return result;
    }

    @Override
    public T getLastSuccededTask() {
        T result = null;
        for (var task : tasks) {
            if (!task.isSuccess())
                break;
            result = task;
        }
        return result;
    }

    @Override
    protected CompletionStage<?> doExecute() {
        return continueExecute(tasks.iterator());
    }

    private CompletionStage<Void> continueExecute(Iterator<T> it) {
        if (terminated) {
            if (currentTask != null)
                return currentTask.execute();
            return CompletableFuture.failedStage(new CancellationException());
        }

        if (!it.hasNext())
            return Fulfilled.emptyFuture();

        currentTask = it.next();
        return currentTask.execute().thenCompose(any -> continueExecute(it));
    }
}
