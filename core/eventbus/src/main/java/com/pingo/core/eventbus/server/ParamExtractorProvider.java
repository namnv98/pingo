package com.pingo.core.eventbus.server;

import com.pingo.core.api.IApiKey;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;

public interface ParamExtractorProvider {

    static ParamExtractorProvider prefixed(String prefix) {
        return (apiKey, message) -> new PrefixedHeaderParamExtractor(message, prefix);
    }

    static ParamExtractorProvider jsonEncoded(String header) {
        return (apiKey, message) -> new JsonEncodedHeaderParamExtractor(message, header);
    }

    ParamExtractor getExtractor(IApiKey apiKey, Message<Buffer> message);
}

