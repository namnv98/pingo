package com.pingo.core.message;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public class CommonResponseUtils {
    public static final Map<String, String> DEFAULT_SUCCESS = Map.of("message", "SUCCESS");
    public static final Buffer DEFAULT_SUCCESS_JSON = new JsonObject().put("data", DEFAULT_SUCCESS).toBuffer();
}
