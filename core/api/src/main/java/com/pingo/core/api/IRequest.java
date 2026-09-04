package com.pingo.core.api;


import com.pingo.core.message.request.LegoRequest;
import io.vertx.core.buffer.Buffer;


public interface IRequest extends LegoRequest<Buffer> {
    IApiKey getApiKey();
}
