package com.lego.namnv.core.eventbus.server;


import com.lego.namnv.core.api.IApiKey;
import com.lego.namnv.core.api.IRequest;
import com.lego.namnv.core.eventbus.EventbusUtils;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import lombok.Getter;
import lombok.NonNull;

import java.util.Map;

class EventBusServerRequest implements IRequest {

    private final @NonNull Message<?> message;

    @Getter
    private final @NonNull IApiKey apiKey;

    private final @NonNull ParamExtractor paramExtractor;

    @Getter
    private final @NonNull Buffer body;

    EventBusServerRequest(Message<?> message, IApiKey apiKey, ParamExtractor paramExtractor) {
        this.apiKey = apiKey;
        this.message = message;
        this.body = getBuffer(message);
        this.paramExtractor = paramExtractor == null ? ParamExtractor.UNAVAILABLE : paramExtractor;
    }

    static String toString(Message<?> message, IApiKey apiKey) {
        var sb = new StringBuilder() //
            .append("--------\n") //
            .append("EVENTBUS:\n  topic: ").append(message.address()).append("\n") //
            .append("API KEY:\n  ").append(apiKey).append("\n") //
            .append("HEADERS:\n");
        var it = message.headers().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            sb.append("  ") //
                .append(entry.getKey()) //
                .append(": ") //
                .append(entry.getValue()) //
                .append("\n");
        }
        sb.append("BODY:\n  ") //
            .append(getBuffer(message)) //
            .append("\n");
        return sb.append("--------") //
            .toString();
    }

    private static Buffer getBuffer(Message<?> message) {
        return EventbusUtils.getBuffer(message);
    }

    @Override
    public String getParam(String name) {
        return paramExtractor.extract(name);
    }

    @Override
    public Iterable<Map.Entry<String, String>> getParams() {
        return paramExtractor.extractAll();
    }

    @Override
    public String getHeader(String name) {
        return message.headers().get(name);
    }

    @Override
    public Iterable<Map.Entry<String, String>> getHeaders() {
        return message.headers();
    }

//    @Override
//    public InetAddress remoteAddress() {
//        return null;
//    }

    @Override
    public String toString() {
        return toString(message, apiKey);
    }

}

