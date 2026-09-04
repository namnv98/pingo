package com.pingo.core.eventbus.server;

import io.vertx.core.eventbus.Message;

public interface EndpointExtractor {

    String extract(Message<?> message);

}