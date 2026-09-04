package com.pingo.core.common.event.subscriber;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.pingo.core.common.AbstractEvent;
import com.pingo.core.common.EventPublisher;
import com.pingo.core.common.event.AppEventRegister;
import com.pingo.core.common.event.DefaultAbstractEvent;
import com.pingo.core.common.event.SerializableConsumer;
import com.pingo.core.common.event.info.DefaultEventInfo;
import org.apache.commons.collections4.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

public abstract class AbstractDefaultEventSubscriber implements DefaultEventSubscriber {
    List<Class<? extends DefaultAbstractEvent<?>>> eventTypes = new ArrayList<>();
    Map<Class<? extends DefaultAbstractEvent<?>>, DefaultEventInfo<?, ?>> eventInfoMap = new HashMap<>();

    protected abstract Injector getInjector();

    public AbstractDefaultEventSubscriber() {

    }

    public void afterPropertiesSet() {
        filterEventInfos(findRegisters())
            .forEach(
                e -> {
                    Class<? extends DefaultAbstractEvent<?>> eventClazz = e.getEventClazz();
                    eventTypes.add(eventClazz);
                    eventInfoMap.put(eventClazz, e);
                });
    }

    protected List<AppEventRegister> findRegisters() {
        try {
//            return new ArrayList<>(ApplicationContextHolder.getBeansOfType(AppEventRegister.class).values());
            final TypeLiteral<Set<AppEventRegister>> setOfString = new TypeLiteral<>() {};
            Key<Set<AppEventRegister>> setKey = Key.get(setOfString);
            Set<AppEventRegister> registers = getInjector().getInstance(setKey);
            return registers.stream().toList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<DefaultEventInfo> filterEventInfos(List<AppEventRegister> registers) {
        return registers.stream()
            .filter(e -> !CollectionUtils.isEmpty(e.registerEventInfo()))
            .flatMap(e -> e.registerEventInfo().stream())
            .filter(target -> target instanceof DefaultEventInfo)
            .map(target -> (DefaultEventInfo) target)
            .collect(Collectors.toList());
    }

    @Override
    public List<Class<? extends DefaultAbstractEvent<?>>> getEventTypes() {
        return eventTypes;
    }

    @Override
    public boolean statusChangeOnContextRefresh() {
        return false;
    }

    protected boolean containsEvent(DefaultAbstractEvent<?> event) {
        Class<?> eventClazz = event.cast().getClass();
        return eventInfoMap.containsKey(eventClazz);
    }

    protected SerializableConsumer<?> findMapperMethod(AbstractEvent event) {
        Class<?> eventClazz = event.cast().getClass();
        DefaultEventInfo info = eventInfoMap.get(eventClazz);
        if (info == null) {
            return null;
        }
        return info.getEventMethod();
    }

    @Override
    public void notifyPublisherStatus(EventPublisher.Status status) {
        DefaultEventSubscriber.super.notifyPublisherStatus(status);
//    if (status.equals(EventPublisher.Status.AFTER_REFRESHED)) {
//      afterPropertiesSet();
//    }
    }
}
