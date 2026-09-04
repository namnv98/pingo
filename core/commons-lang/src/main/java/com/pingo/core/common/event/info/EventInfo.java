package com.pingo.core.common.event.info;

import com.pingo.core.common.AbstractEvent;
import com.pingo.core.common.event.SerializableConsumer;

import java.util.List;

public interface EventInfo {
    static InfoBuilder builder() {
        return new DefaultEventInfoBuilder();
    }

    static <E extends AbstractEvent, T> List<EventInfo> build(Class<E> evenClazz, SerializableConsumer<T> mapperMethod, String eventCode) {
        return new DefaultEventInfoBuilder().add(evenClazz, mapperMethod, eventCode).build();
    }

    String getEventCode();

    Class<? extends AbstractEvent> getEventClazz();

    interface Builder {
        List<EventInfo> build();
    }

    interface InfoBuilder extends Builder {
        <E extends AbstractEvent, T> DefaultEventInfoBuilder add(Class<E> evenClazz, SerializableConsumer<T> mapperMethod, String eventCode);
    }
}
