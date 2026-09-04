package com.pingo.core.common.json.address;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.pingo.core.common.support.L4Address;

import java.io.IOException;

public class L4AddressSerializer extends JsonSerializer<L4Address> {

    @Override
    public void serialize(L4Address value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeFieldName("host");
        gen.writeString(value.getHost());
        gen.writeFieldName("port");
        gen.writeNumber(value.getPort());
        gen.writeEndObject();
    }

    @Override
    public Class<L4Address> handledType() {
        return L4Address.class;
    }

}
