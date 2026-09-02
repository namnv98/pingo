package com.lego.namnv.core.eventbus.client;

import io.vertx.core.eventbus.DeliveryOptions;
import lombok.AllArgsConstructor;
import lombok.NonNull;

public interface ParamCompressor {

    static ParamCompressor prefixedHeader(String prefix, DeliveryOptions options) {
        return new PrefixedHeaderParamCompressor(prefix, options);
    }

    void setParam(String name, String value);
}

@AllArgsConstructor
class PrefixedHeaderParamCompressor implements ParamCompressor {

    private final @NonNull String prefix;
    private final @NonNull DeliveryOptions options;

    @Override
    public void setParam(String name, String value) {
        options.addHeader(prefix + name, value);
    }
}
