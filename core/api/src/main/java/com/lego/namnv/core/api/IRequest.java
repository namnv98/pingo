package com.lego.namnv.core.api;


import com.lego.namnv.core.message.request.LegoRequest;
import io.vertx.core.buffer.Buffer;


public interface IRequest extends LegoRequest<Buffer> {
    IApiKey getApiKey();
}
