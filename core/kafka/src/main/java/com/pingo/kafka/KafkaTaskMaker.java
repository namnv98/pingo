package com.pingo.kafka;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import com.pingo.async.AsyncRunnable;

import lombok.AllArgsConstructor;
import lombok.NonNull;

@FunctionalInterface
public interface KafkaTaskMaker<K, V> {

    static <K, V> KafkaTaskMaker<K, V> simple(Function<ConsumerRecord<K, V>, AsyncRunnable> handler) {
        return new SimpleTaskMaker<K, V>(handler);
    }

    static <K, V> KafkaTaskMaker<K, V> batching(Function<List<ConsumerRecord<K, V>>, AsyncRunnable> handler) {
        return new BatchingTaskMaker<K, V>(handler);
    }

    List<KafkaTask> makeTasks(List<ConsumerRecord<K, V>> records);
}

@AllArgsConstructor
class SimpleTaskMaker<K, V> implements KafkaTaskMaker<K, V> {

    private final @NonNull Function<ConsumerRecord<K, V>, AsyncRunnable> handler;

    @Override
    public List<KafkaTask> makeTasks(List<ConsumerRecord<K, V>> records) {
        return records.stream() //
                .map(record -> {
                    var runnable = handler.apply(record);
                    return KafkaTask.builder() //
                            .runnable(runnable) //
                            .offset(record.offset()) //
                            .build();
                }) //
                .collect(Collectors.toUnmodifiableList());
    }
}

@AllArgsConstructor
class BatchingTaskMaker<K, V> implements KafkaTaskMaker<K, V> {

    private final @NonNull Function<List<ConsumerRecord<K, V>>, AsyncRunnable> handler;

    @Override
    public List<KafkaTask> makeTasks(List<ConsumerRecord<K, V>> records) {
        var runnable = handler.apply(records);
        var lastOffset = records.get(records.size() - 1).offset();
        return List.of(KafkaTask.builder() //
                .runnable(runnable) //
                .offset(lastOffset) //
                .build());
    }
}
