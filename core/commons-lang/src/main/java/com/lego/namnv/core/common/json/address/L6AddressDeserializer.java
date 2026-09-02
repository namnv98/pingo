package com.lego.namnv.core.common.json.address;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.lego.namnv.core.common.support.L6Address;

import java.io.IOException;

public class L6AddressDeserializer extends JsonDeserializer<L6Address> {

    @Override
    public L6Address deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException, JsonProcessingException {
        var codec = p.getCodec();
        JsonNode node = codec.readTree(p);
        var host = node.get("host").asText();
        var port = node.get("port").asInt();
        var useSsl = node.get("ssl").asBoolean();
        return new L6Address(host, port, useSsl);
    }

    @Override
    public Class<L6Address> handledType() {
        return L6Address.class;
    }

}
