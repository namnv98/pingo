package com.pingo.kafka;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

public interface UncommittedOffsetStorage {

    final UncommittedOffsetStorage DUMMY = new DummyUncommittedOffsetStorage();

    void save(Map<TopicPartition, OffsetAndMetadata> offsets);

    Map<TopicPartition, OffsetAndMetadata> fetch(Collection<TopicPartition> partitions);
}

@Log4j2
class DummyUncommittedOffsetStorage implements UncommittedOffsetStorage {

    @Override
    public void save(Map<TopicPartition, OffsetAndMetadata> offsets) {
        log.info("uncommitted offsets: {}", offsets);
    }

    @Override
    public Map<TopicPartition, OffsetAndMetadata> fetch(Collection<TopicPartition> partitions) {
        return Collections.emptyMap();
    }

}
