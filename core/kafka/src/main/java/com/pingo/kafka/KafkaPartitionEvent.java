package com.pingo.kafka;

import java.util.Collection;

import com.lego.namnv98.event.Event;
import org.apache.kafka.common.TopicPartition;


import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class KafkaPartitionEvent implements Event {

    public final static KafkaPartitionEvent assigned(Collection<TopicPartition> topicPartitions) {
        return new KafkaPartitionEvent(KafkaPartitionEventType.ASSIGNED, topicPartitions);
    }

    public final static KafkaPartitionEvent revoked(Collection<TopicPartition> topicPartitions) {
        return new KafkaPartitionEvent(KafkaPartitionEventType.REVOKED, topicPartitions);
    }

    public static enum KafkaPartitionEventType {
        ASSIGNED,
        REVOKED;
    }

    private final @NonNull KafkaPartitionEventType type;
    private final @NonNull Collection<TopicPartition> topicPartitions;

    @Override
    public String toString() {
        return "(" + getType() + ": " + topicPartitions + ")";
    }
}
