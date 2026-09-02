package com.lego.namnv.core.eventbus.server;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import lombok.NonNull;

import java.util.Map;
import java.util.Map.Entry;

public interface ParamExtractor {

    ParamExtractor UNAVAILABLE = new ParamExtractor() {

        private static final Iterable<Entry<String, String>> EMPTY_ITERABLE = Map.<String, String>of().entrySet();

        @Override
        public String extract(String name) {
            return null;
        }

        @Override
        public Iterable<Entry<String, String>> extractAll() {
            return EMPTY_ITERABLE;
        }
    };

    String extract(String name);

    Iterable<Entry<String, String>> extractAll();
}

class PrefixedHeaderParamExtractor implements ParamExtractor {

    private final @NonNull String prefix;
    private final @NonNull MultiMap source;
    private final int prefixLength;

    PrefixedHeaderParamExtractor(Message<Buffer> message, String prefix) {
        this.source = message.headers();
        this.prefix = prefix;
        this.prefixLength = prefix.length();
    }

    @Override
    public String extract(String name) {
        return source.get(prefix + name);
    }

    @Override
    public Iterable<Entry<String, String>> extractAll() {
        return source.entries().stream() //
            .filter(e -> e.getKey().startsWith(prefix)) //
            .map(e -> Map.entry(e.getKey().substring(prefixLength), e.getValue())) //
            .toList();
    }
}

class JsonEncodedHeaderParamExtractor implements ParamExtractor {

    private final @NonNull Map<String, Object> source;

    JsonEncodedHeaderParamExtractor(Message<?> message, String header) {
        var json = message.headers().get(header);
        source = json == null ? Map.of() : new JsonObject(json).getMap();
    }

    @Override
    public String extract(String name) {
        var value = source.get(name);
        if (value == null)
            return null;
        return value.toString();
    }

    @Override
    public Iterable<Entry<String, String>> extractAll() {
        return source.entrySet().stream() //
            .map(e -> Map.entry(e.getKey(), e.getValue().toString())) //
            .toList();
    }
}
