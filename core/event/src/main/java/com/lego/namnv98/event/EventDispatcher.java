package com.lego.namnv98.event;

public interface EventDispatcher {

    <T extends Event> T dispatch(T event);
}
