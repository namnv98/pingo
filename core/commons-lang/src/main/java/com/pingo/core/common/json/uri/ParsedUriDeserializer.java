package com.pingo.core.common.json.uri;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.pingo.core.common.support.ParsedUri;
import com.pingo.core.common.support.Splitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.function.Consumer;

public class ParsedUriDeserializer extends JsonDeserializer<ParsedUri> {

    private void doSetString(JsonNode node, String field, Consumer<String> setter) {
        var value = node.get(field);
        if (value != null)
            setter.accept(value.textValue());
    }

    @Override
    public ParsedUri deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
        var codec = p.getCodec();
        JsonNode node = codec.readTree(p);

        if (node.getNodeType() == JsonNodeType.STRING)
            return ParsedUri.parse(node.asText());

        var parsed = new ParsedUri();
        doSetString(node, "scheme", parsed::setScheme);
        doSetString(node, "user", parsed::setUser);
        doSetString(node, "password", parsed::setPassword);
        doSetString(node, "path", parsed::setPath);

        var addresses = node.get("addresses");
        if (addresses == null)
            addresses = node.get("address");

        if (addresses != null) {
            if (addresses.isArray()) {
                var list = new LinkedList<String>();
                var it = addresses.iterator();
                while (it.hasNext())
                    list.add(it.next().textValue());
                parsed.setAddresses(list);
            } else if (addresses.isTextual())
                parsed.setAddresses(Splitter.COMMA.splitToList(addresses.asText()));
            else
                throw new JsonParseException(p, "invalid `addresses` field, expected string or list of string, got" + node);
        }

        var params = node.get("params");
        if (params != null && params.isObject()) {
            var map = new HashMap<String, String>();
            var it = params.fields();
            while (it.hasNext()) {
                var ele = it.next();
                map.put(ele.getKey(), ele.getValue().textValue());
            }
            parsed.setParams(map);
        }

        return parsed;
    }

    @Override
    public Class<ParsedUri> handledType() {
        return ParsedUri.class;
    }
}
