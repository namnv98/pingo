package com.lego.namnv.core.common.json.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.ZoneId;

public class ZoneIdDeserializer extends JsonDeserializer<ZoneId> {

    @Override
    public ZoneId deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        var data = p.getValueAsString();
        return ZoneId.of(data);
    }

    @Override
    public Class<?> handledType() {
        return ZoneId.class;
    }

}
