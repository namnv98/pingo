package com.pingo.core.common.json.time;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.ZoneId;

public class ZoneIdSerializer extends JsonTimeSerializer<ZoneId> {

    public ZoneIdSerializer() {
        super(ZoneId.class);
    }

    @Override
    public void serialize(ZoneId value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeString(value.toString());
        }
    }

}
