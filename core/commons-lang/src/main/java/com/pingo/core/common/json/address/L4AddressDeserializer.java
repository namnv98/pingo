package com.pingo.core.common.json.address;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.pingo.core.common.support.L4Address;

import java.io.IOException;

public class L4AddressDeserializer extends JsonDeserializer<L4Address> {

    @Override
    public L4Address deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException, JsonProcessingException {
        var codec = p.getCodec();
        JsonNode node = codec.readTree(p);

        // try catch block
        var hostNode = node.get("host");
        var portNode = node.get("port");
        return new L4Address(hostNode.asText(), portNode.asInt());
    }

    @Override
    public Class<L4Address> handledType() {
        return L4Address.class;
    }
}
