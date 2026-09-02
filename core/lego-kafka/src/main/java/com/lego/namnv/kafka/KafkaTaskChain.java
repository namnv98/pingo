package com.lego.namnv.kafka;

import com.lego.namnv.async.task.BaseAsyncTaskSequence;

import java.util.List;


class KafkaTaskChain extends BaseAsyncTaskSequence<KafkaTask> {

    KafkaTaskChain(List<KafkaTask> tasks) {
        super(tasks);
    }

    public long getLastSuccededOffset() {
        var lastSuccessedTask = getLastSuccededTask();
        if (lastSuccessedTask != null)
            return lastSuccessedTask.getOffset();
        return -1l;
    }

    public long getLastDoneOffset() {
        var lastDoneTask = getLastDoneTask();
        if (lastDoneTask != null)
            return lastDoneTask.getOffset();
        return -1l;
    }
}
