package com.lego.namnv.core.common.event.subscriber;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.lego.namnv.core.common.event.AppEventRegister;
import com.lego.namnv.core.common.event.DefaultAbstractEvent;
import com.lego.namnv.core.common.event.SerializableConsumer;

import java.util.List;
import java.util.Set;

public class DefaultServiceEventSubscriber extends AbstractDefaultEventSubscriber {

    private final Injector injector;
    private final Set<AppEventRegister> appEventRegisterSet;

    @Inject
    public DefaultServiceEventSubscriber(Injector injector, Set<AppEventRegister> appEventRegisterSet) {
        this.injector = injector;
        this.appEventRegisterSet = appEventRegisterSet;
        afterPropertiesSet();
    }

    @Override
    public void subscribe(DefaultAbstractEvent<?> event) {
        if (!super.containsEvent(event)) {
            return;
        }
        SerializableConsumer consumer = super.findMapperMethod(event);
        Object entity = event.getEntity();
        consumer.accept(entity);
    }

    @Override
    protected List<AppEventRegister> findRegisters() {
        return appEventRegisterSet.stream().toList();
    }

    @Override
    protected Injector getInjector() {
        return injector;
    }
}
