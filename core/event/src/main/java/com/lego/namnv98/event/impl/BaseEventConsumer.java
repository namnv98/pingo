package com.lego.namnv98.event.impl;

import java.util.function.Consumer;

import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.Event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

@Log4j2
@Getter
@AllArgsConstructor(staticName = "of")
public class BaseEventConsumer<T extends Event> implements EventConsumer {

    private final String name;

    private final @NonNull Consumer<T> consumer;

    public static final <T extends Event> BaseEventConsumer<T> of(Consumer<T> consumer) {
        return of(null, consumer);
    }

    @Override
    public void onEvent(Event event) {
        try {
            consumer.accept(event.cast());
        } catch (ClassCastException e) {
            log.error("Event cannot be cast to target consumer expected", e);
        }
    }
}
