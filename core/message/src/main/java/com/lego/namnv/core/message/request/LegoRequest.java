package com.lego.namnv.core.message.request;

import com.lego.namnv.core.message.LegoMessage;
import lombok.Builder;
import lombok.Singular;

import java.util.Map;

public interface LegoRequest<BodyType> extends LegoMessage<BodyType> {

    @Builder(builderMethodName = "providedBuilder")
    static <T> LegoRequest<T> provided( //
                                        @Singular Map<String, String> headers, //
                                        @Singular Map<String, String> params, //
                                        T body) {
        return new ProvidedRequest<>(headers, params, body);
    }

    String getParam(String name);

    default String getParam(String name, String defaultValue) {
        var value = getParam(name);
        if (value == null || value.isBlank())
            return defaultValue;
        return value;
    }

    default Integer getParamInt(String name) {
        var value = getParam(name, null);
        if (value == null)
            return null;
        return Integer.parseInt(value);
    }

    default Integer getParamInt(String name, int defaultValue) {
        var value = getParamInt(name);
        if (value == null)
            return defaultValue;
        return value;
    }

    Iterable<Map.Entry<String, String>> getParams();
}