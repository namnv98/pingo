package com.lego.namnv.core.http;

import com.lego.namnv.core.common.exception.LegoBusinessException;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public interface HttpErrorBodyBuilder {

    static final HttpErrorBodyBuilder DEFAULT = new DefaultHttpErrorBodyBuilder();

    JsonObject build(String errorKey, Map<String, ?> data);

    default JsonObject build(String errorKey, String dataKey, Object dataValue) {
        return build(errorKey, Map.of(dataKey, dataValue));
    }

    default JsonObject build(LegoBusinessException ex) {
        return build(ex.getKey(), ex.getItems());
    }

}

class DefaultHttpErrorBodyBuilder implements HttpErrorBodyBuilder {

    private static final String DATA = "data";
    private static final String ERROR = "error";

    @Override
    public JsonObject build(String errorKey, Map<String, ?> data) {
        return new JsonObject() //
            .put(ERROR, errorKey) //
            .put(DATA, data);
    }
}
