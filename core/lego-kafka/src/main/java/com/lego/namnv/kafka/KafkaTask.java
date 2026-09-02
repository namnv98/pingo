package com.lego.namnv.kafka;

import com.lego.namnv.async.AsyncRunnable;
import com.lego.namnv.async.task.BaseAsyncTask;

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
