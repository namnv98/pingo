package com.lego.namnv.core.common.json.uuid;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.UUID;

public class UuidDeserializer extends JsonDeserializer<UUID> {

    @Override
    public Class<UUID> handledType() {
        return UUID.class;
    }

    @Override
    public UUID deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        var value = p.getValueAsString();
        return UUID.fromString(value);
    }

}
