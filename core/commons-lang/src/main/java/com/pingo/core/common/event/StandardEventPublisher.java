package com.pingo.core.common.event;

import com.google.inject.Inject;
import com.pingo.core.common.AbstractEvent;
import com.pingo.core.common.EventPublisher;
import com.pingo.core.common.EventSubscriber;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


public class StandardEventPublisher implements EventPublisher {

    private final Queue<AbstractEvent> temporaryQueue = new ConcurrentLinkedQueue<>();
    private final Map<Class<?>, List<EventSubscriber<?>>> eventSubscriberMap = new HashMap<>();
    private volatile boolean contextRefreshDone = true;

    @Inject
    public StandardEventPublisher(Set<EventSubscriber<?>> eventSubscribers) {
        this.eventSubscribers = eventSubscribers.stream().toList();
        afterPropertiesSet();
    }

    private final List<EventSubscriber<?>> eventSubscribers;

    public synchronized void afterPropertiesSet() {
        notifyPublisherStatus(Status.AFTER_CREATED);
        contextRefreshDone = false;
        reloadSubscribers();
    }

    public synchronized void afterContextRefreshed() {
        notifyPublisherStatus(Status.AFTER_REFRESHED);
        contextRefreshDone = true;
        reloadSubscribers();
        flushTemporaryQueue();
        notifyPublisherStatus(Status.AFTER_TEMP_QUEUE_FLUSHED);
    }

    @Override
    public <T extends AbstractEvent> void publish(T event) {
        propagateEvent(event);
    }

    private synchronized void notifyPublisherStatus(Status status) {
        if (CollectionUtils.isEmpty(eventSubscribers)) {
            return;
        }
        for (EventSubscriber<?> subscriber : eventSubscribers) {
            subscriber.notifyPublisherStatus(status);
        }
    }

    private synchronized void reloadSubscribers() {
        if (CollectionUtils.isEmpty(eventSubscribers)) {
            return;
        }

        eventSubscriberMap.clear();
        for (EventSubscriber subscriber : eventSubscribers) {
            List<Class<?>> eventTypes = subscriber.getEventTypes();
            if (CollectionUtils.isEmpty(eventTypes)) {
                continue;
            }
            for (Class<?> eventType : eventTypes) {
                if (eventType == null) {
                    continue;
                }
                eventSubscriberMap.computeIfAbsent(eventType, l -> new ArrayList<>()).add(subscriber);
            }
        }
    }

    private synchronized void flushTemporaryQueue() {
        while (!temporaryQueue.isEmpty()) {
            AbstractEvent event = temporaryQueue.poll();
            List<EventSubscriber<?>> subscribers = eventSubscriberMap.get(event.getClass());
            if (CollectionUtils.isEmpty(subscribers)) {
                continue;
            }
            for (EventSubscriber subscriber : subscribers) {
                boolean changed = subscriber.statusChangeOnContextRefresh();
                if (changed) {
                    subscriber.subscribe(event);
                }
            }
        }
    }

    private void propagateEvent(AbstractEvent event) {
        if (!this.contextRefreshDone) {
            temporaryQueue.add(event);
        }
        List<EventSubscriber<?>> subscribers = eventSubscriberMap.get(event.getClass());
        if (subscribers == null) {
            return;
        }
        for (EventSubscriber subscriber : subscribers) {
            subscriber.subscribe(event);
        }
    }
}
