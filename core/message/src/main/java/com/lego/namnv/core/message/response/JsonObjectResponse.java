package com.lego.namnv.core.message.response;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.Singular;

import java.util.Map;

public class JsonObjectResponse extends ProvidedBodyResponse<JsonObject> {

    @Builder
    public JsonObjectResponse(@Singular Map<String, String> headers, JsonObject body) {
        super(headers, body);
    }

    public JsonObjectResponse(JsonObject body) {
        super(Map.of(), body);
    }

    public LegoResponse<Buffer> toBufferResponse() {
        return transform(JsonObject::toBuffer);
    }
}
