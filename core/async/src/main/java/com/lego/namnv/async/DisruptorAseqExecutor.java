package com.lego.namnv.async;

import com.lego.namnv.core.common.comp.AbstractLifeCycle;
import com.lmax.disruptor.*;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Log4j2
public class DisruptorAseqExecutor extends AbstractLifeCycle implements AseqExecutor {

    private static class AsyncTaskEvent {
        private long sequence;
        private AsyncRunnable task;

        void clear() {
            this.sequence = -1;
            this.task = null;
        }
    }

    private final RingBuffer<AsyncTaskEvent> ringBuffer;
    private SequenceBarrier sequenceBarrier;
    private Sequence sequence;

    @Builder
    private DisruptorAseqExecutor(Integer ringBufferSize, WaitStrategy waitStrategy) {
        var _ringBufferSize = ringBufferSize != null ? ringBufferSize : 1024;
        var _waitStrategy = waitStrategy != null ? waitStrategy : new BlockingWaitStrategy();
        ringBuffer = RingBuffer.createSingleProducer(AsyncTaskEvent::new, _ringBufferSize, _waitStrategy);
        sequenceBarrier = ringBuffer.newBarrier();
        sequence = new Sequence(-1);
        ringBuffer.addGatingSequences(sequence);
    }

    @Override
    public void submit(@NonNull AsyncRunnable task) {
        ringBuffer.publishEvent((e, s) -> {
            e.task = task;
            e.sequence = s;
        });
    }

    @Override
    protected void doStart() throws Exception {
        var bootstrap = new Thread(() -> {
            sequenceBarrier.clearAlert();
            consume();
        });
        bootstrap.start();
    }

    private void consume() {
        var nextSequence = sequence.get() + 1l;
        try {
            final long availableSequence = sequenceBarrier.waitFor(nextSequence);
            var tasks = new LinkedList<AsyncTaskEvent>();
            while (nextSequence <= availableSequence) {
                var event = ringBuffer.get(nextSequence);
                tasks.add(event);
                nextSequence++;
            }

            executeChain(tasks.iterator()).whenCompleteAsync((any, ex) -> {
                sequence.set(availableSequence);
                consume();
            });
        } catch (Exception e) {
            log.error("Error while consume task", e);
        }
    }

    private CompletionStage<?> executeChain(Iterator<AsyncTaskEvent> it) {
        if (!it.hasNext())
            return CompletableFuture.completedStage(null);

        final var event = it.next();
        final var id = event.sequence;
        final var task = event.task;
        event.clear();

        return task.run() //
                .exceptionally(cause -> onException(id, task, cause)) //
                .thenCompose(any -> executeChain(it));
    }

    private <T> T onException(long id, AsyncRunnable task, Throwable cause) {
        log.error("Error while running task with id {}", id, cause);
        return null;
    }
}
