package com.lego.namnv.core.common.json.vertx;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

public class JsonObjectSerializer extends JsonSerializer<JsonObject> {

    @Override
    public Class<JsonObject> handledType() {
        return JsonObject.class;
    }

    @Override
    public void serialize(JsonObject value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeStartObject();
        var it = value.iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var k = entry.getKey();
            var v = entry.getValue();
            if (v == null)
                gen.writeNullField(k);
            else if (v instanceof String s)
                gen.writeStringField(k, s);
            else if (v instanceof Boolean b)
                gen.writeBooleanField(k, b);
            else if (v instanceof Integer i)
                gen.writeNumberField(k, i);
            else if (v instanceof Long l)
                gen.writeNumberField(k, l);
            else if (v instanceof Double d)
                gen.writeNumberField(k, d);
            else if (v instanceof Float f)
                gen.writeNumberField(k, f);
            else if (v instanceof Byte b)
                gen.writeNumberField(k, b);
            else if (v instanceof Short s)
                gen.writeNumberField(k, s);
            else if (v instanceof BigInteger bi)
                gen.writeNumberField(k, bi);
            else if (v instanceof BigDecimal bd)
                gen.writeNumberField(k, bd);
            else
                gen.writePOJOField(k, v);
        }
        gen.writeEndObject();
    }
}
