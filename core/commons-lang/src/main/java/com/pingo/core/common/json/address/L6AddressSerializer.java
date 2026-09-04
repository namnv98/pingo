package com.pingo.core.common.json.address;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.pingo.core.common.support.L6Address;

import java.io.IOException;

public class L6AddressSerializer extends JsonSerializer<L6Address> {

    @Override
    public void serialize(L6Address value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeStartObject();
        gen.writeFieldName("host");
        gen.writeString(value.getHost());
        gen.writeFieldName("port");
        gen.writeNumber(value.getPort());
        gen.writeFieldName("ssl");
        gen.writeBoolean(value.isUseSsl());
        gen.writeEndObject();
    }

    @Override
    public Class<L6Address> handledType() {
        return L6Address.class;
    }

}
