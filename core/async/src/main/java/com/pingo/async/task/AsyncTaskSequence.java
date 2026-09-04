package com.pingo.async.task;

public interface AsyncTaskSequence<T extends AsyncTask> {

    T getCurrentTask();

    T getLastSuccededTask();

    T getLastDoneTask();
}
