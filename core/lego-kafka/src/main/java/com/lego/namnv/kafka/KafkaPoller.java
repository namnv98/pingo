package com.lego.namnv.kafka;

import com.lego.namnv.async.AsyncRunnable;
import com.lego.namnv.core.common.collection.CollectionUtils;
import com.lego.namnv.core.common.comp.AbstractLifeCycle;
import com.lego.namnv.core.common.exception.ExceptionUtils;
import com.lego.namnv.core.common.support.Disposable;
import com.lego.namnv.core.common.support.Fulfilled;
import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.EventEmitter;
import com.lego.namnv98.event.EventObservable;
import com.lego.namnv98.event.impl.BaseEventEmitter;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Log4j2
public class KafkaPoller<K, V> extends AbstractLifeCycle implements EventObservable {
    private static final AsyncRunnable ALWAYS_DONE_RUNNABLE = () -> Fulfilled.emptyStage();

    private final EventEmitter emitter = new BaseEventEmitter();

    private final boolean reconnectOnError;

    private final @NonNull Properties consumerProperties;

    private @NonNull Collection<String> topics;

    private final @NonNull Duration pollTimeout;

    private final @NonNull Map<TopicPartition, KafkaTaskChain> activeTasks = new ConcurrentHashMap<>();

    private final @NonNull KafkaTaskMaker<K, V> taskMaker;

    private Thread pollerThread;

    private @NonNull String pollerThreadName = "kafka-poller";

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);

    private final Map<TopicPartition, Long> committedOffsets = new ConcurrentHashMap<>();

    private Consumer<Collection<String>> onTopicsUpdateHandler;

    private final UncommittedOffsetStorage uncommittedOffsetStorage;

    private static final Properties correctProps(Properties origin) {
        var props = new Properties();
        if (origin != null)
            props.putAll(origin);
        if (!props.containsKey("enable.auto.commit"))
            props.setProperty("enable.auto.commit", String.valueOf(false));
        return props;
    }

    @Builder
    private KafkaPoller( //
                         String pollerThreadName, //
                         Boolean reconnectOnError, //
                         Properties consumerProperties, //
                         Collection<String> topics, //
                         Duration pollTimeout, //
                         KafkaTaskMaker<K, V> taskMaker, //
                         UncommittedOffsetStorage uncommittedOffsetStorage) {

        this.topics = topics;
        this.taskMaker = taskMaker;
        this.consumerProperties = correctProps(consumerProperties);
        this.pollTimeout = pollTimeout == null ? Duration.ofMillis(100) : pollTimeout;
        this.reconnectOnError = reconnectOnError == null ? true : reconnectOnError.booleanValue();

        this.uncommittedOffsetStorage = uncommittedOffsetStorage == null //
                ? UncommittedOffsetStorage.DUMMY //
                : uncommittedOffsetStorage;

        if (pollerThreadName != null)
            this.pollerThreadName = pollerThreadName;
    }

    @Override
    public Disposable subscribe(EventConsumer consumer) {
        return emitter.subscribe(consumer);
    }

    private void terminateActiveTaskOnPartitions(KafkaConsumer<K, V> kafka, Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty())
            return;

        var offsets = new HashMap<TopicPartition, OffsetAndMetadata>();
        for (var partition : partitions) {
            var currentTasks = activeTasks.remove(partition);
            if (currentTasks == null)
                continue;

            currentTasks.cancel();
            try {
                try {
                    currentTasks.waitForDone();
                } catch (Exception ex) {
                    var cause = ExceptionUtils.extractMeaningfulCause(ex);
                    if (!(cause instanceof CancellationException))
                        log.error("error while waiting task for done", cause);
                }
                var successOffset = currentTasks.getLastSuccededOffset();
                if (successOffset > 0)
                    offsets.put(partition, new OffsetAndMetadata(successOffset));
            } catch (Exception e) {
                log.error("error while waiting task for done", e);
                continue;
            }
        }

        if (!offsets.isEmpty()) {
            // kafka.commitSync(offsets);
            uncommittedOffsetStorage.save(offsets);
        }
    }

    /**
     * @param records
     * @return
     */
    private KafkaTaskChain handlePartitionRecords(final List<ConsumerRecord<K, V>> records) {
        var tasks = taskMaker.makeTasks(records);

        var lastOffset = records.get(records.size() - 1).offset();
        tasks = CollectionUtils.<KafkaTask>listBuilder() //
                .addAll(tasks) //
                .add(KafkaTask.builder() //
                        .runnable(ALWAYS_DONE_RUNNABLE) //
                        .offset(lastOffset + 1) //
                        .build()) //
                .build();

        return new KafkaTaskChain(tasks);
    }

    /**
     * batching records by partition
     *
     * @param records
     */
    private void handleFetchedRecords(KafkaConsumer<K, V> kafkaConsumer, ConsumerRecords<K, V> records) {
        if (records.isEmpty())
            return;

        kafkaConsumer.pause(records.partitions());
        records.partitions().forEach(partition -> {
            var partitionRecords = records.records(partition);
            log.debug("[KAFKA POLLER] handling {} messages from topic {}", partitionRecords.size(), partition);
            var chain = handlePartitionRecords(partitionRecords);
            activeTasks.put(partition, chain);
            chain.execute();
        });
    }

    private void checkAndCommitOffsets(KafkaConsumer<K, V> kafka) {
        var tobeCommitted = new HashMap<TopicPartition, OffsetAndMetadata>();
        var it = activeTasks.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var chain = entry.getValue();

            var lastSuccessOffset = chain.getLastSuccededOffset();
            if (lastSuccessOffset < 0)
                continue;

            var topicPartition = entry.getKey();
            var lastCommittedOffset = committedOffsets.get(topicPartition);
            if (lastCommittedOffset == null || lastCommittedOffset.longValue() < lastSuccessOffset)
                tobeCommitted.put(topicPartition, new OffsetAndMetadata(lastSuccessOffset));
        }

        if (tobeCommitted.isEmpty())
            return;

        kafka.commitAsync(tobeCommitted, (committed, ex) -> {
            if (ex == null) {
                onCommitted(kafka, committed);
                return;
            }
            log.error("skip resuming and wait for next check on error while commit offsets {}", tobeCommitted, ex);
        });
    }

    private void onCommitted(KafkaConsumer<K, V> kafka, Map<TopicPartition, OffsetAndMetadata> committed) {
        log.debug("committed offsets: {}", committed);
        var tobeResumed = new HashSet<TopicPartition>();
        for (var entry : committed.entrySet()) {
            var topicPartition = entry.getKey();
            var committedOffset = entry.getValue().offset();
            committedOffsets.put(topicPartition, committedOffset);

            var chain = activeTasks.get(topicPartition);
            if (chain == null || !chain.isDone() || chain.getLastSuccededOffset() != committedOffset)
                continue;

            activeTasks.remove(topicPartition);
            if (chain.isSuccess()) {
                tobeResumed.add(topicPartition);
                continue;
            }

            log.error("error while executing task at offset {} from partition {}, stop consuming",
                    chain.getLastDoneOffset(), topicPartition, chain.getFailedCause());
        }

        if (!tobeResumed.isEmpty())
            kafka.resume(tobeResumed);
    }

    private ConsumerRebalanceListener createListener(KafkaConsumer<K, V> kafkaConsumer) {
        return new ConsumerRebalanceListener() {

            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                log.debug("Partition revoked: {}", partitions);
                terminateActiveTaskOnPartitions(kafkaConsumer, partitions);
                emitter.dispatch(KafkaPartitionEvent.revoked(partitions));
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.debug("Partition assigned: {}", partitions);

                var uncommitedOffsets = uncommittedOffsetStorage.fetch(partitions);
                if (uncommitedOffsets != null && !uncommitedOffsets.isEmpty()) {
                    log.info("seeking to uncommitted offsets: {}", uncommitedOffsets);
                    uncommitedOffsets.forEach(kafkaConsumer::seek);
                }

                emitter.dispatch(KafkaPartitionEvent.assigned(partitions));
            }
        };
    }

    @Override
    protected void doStop() {
        this.stopFlag.set(true);
        this.pollerThread.interrupt();
        this.pollerThread = null;
    }

    public void setTopics(Collection<String> topics) {
        this.topics = topics;
        if (onTopicsUpdateHandler != null)
            onTopicsUpdateHandler.accept(topics);
    }

    public void setTopics(String... topics) {
        setTopics(Set.of(topics));
    }

    @Override
    protected void doStart(final CompletableFuture<Void> startFuture) {
        this.pollerThread = new Thread(() -> {
            do {
                try (var kafkaConsumer = new KafkaConsumer<K, V>(consumerProperties)) {
                    var listener = createListener(kafkaConsumer);
                    kafkaConsumer.subscribe(topics, listener);
                    onTopicsUpdateHandler = ts -> kafkaConsumer.subscribe(ts, listener);
                    if (!startFuture.isDone())
                        startFuture.complete(null);
                    loop(kafkaConsumer);
                } catch (Throwable e) {
                    if (!startFuture.isDone())
                        startFuture.completeExceptionally(e);

                    if (e instanceof InterruptedException)
                        return;

                    log.error("Error occur while polling kafka, recreate new kafka consumer", e);

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e1) {
                        log.error("interupted while sleeping before re-create kafka consumer, break loop", e1);
                        return;
                    }
                } finally {
                    log.info("KafkaConsumer stopped");
                    onTopicsUpdateHandler = null;
                }
            } while (reconnectOnError && !stopFlag.get());
        }, this.pollerThreadName);
        this.pollerThread.start();
    }

    private void loop(KafkaConsumer<K, V> kafkaConsumer) {
        var lastPrint = 0l;
        var count = 0l;
        while (!stopFlag.get() && !Thread.currentThread().isInterrupted()) {
            var records = kafkaConsumer.poll(pollTimeout);
            count += records.count();
            var now = System.currentTimeMillis();
            if (now - lastPrint >= 5000 && count > 0) {
                lastPrint = now;
                log.debug("fetched {} record(s) from kafka", count);
                count = 0;
            }

            handleFetchedRecords(kafkaConsumer, records);
            checkAndCommitOffsets(kafkaConsumer);
        }
    }
}
