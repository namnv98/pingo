package com.pingo.kafka;

import com.pingo.async.AsyncRunnable;
import com.pingo.async.task.BaseAsyncTask;

import lombok.Builder;
import lombok.Getter;

public class KafkaTask extends BaseAsyncTask {

    @Getter
    private final long offset;

    @Builder
    private KafkaTask(AsyncRunnable runnable, long offset) {
        super(runnable);
        this.offset = offset;
    }
}
