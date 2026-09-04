package com.pingo.core.eventbus.client;

import com.pingo.core.eventbus.EventbusUtils;
import com.pingo.core.message.response.LegoResponse;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Map.Entry;

@RequiredArgsConstructor
class EventBusClientResponse implements LegoResponse<Buffer> {

    private final @NonNull Message<?> message;

    @Override
    public String getHeader(String name) {
        return message.headers().get(name);
    }

    @Override
    public Iterable<Entry<String, String>> getHeaders() {
        return message.headers();
    }

    @Override
    public Buffer getBody() {
        return EventbusUtils.getBuffer(message);
    }

}
