package com.pingo.core.eventbus.client;


import com.pingo.core.common.exception.ExceptionUtils;
import com.pingo.core.common.exception.LegoBusinessException;
import com.pingo.core.eventbus.EventBusConsts;
import com.pingo.core.eventbus.exception.NoHandlerException;
import com.pingo.core.eventbus.exception.RequestTimeoutException;
import com.pingo.core.message.request.LegoRequest;
import com.pingo.core.message.response.LegoResponse;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

public interface EventBusRpcSender {

    static EventBusRpcSender of(EventBus eventBus) {
        return new DefaultEventBusRpcSender(eventBus);
    }

    CompletionStage<LegoResponse<Buffer>> request(String address, LegoRequest<Buffer> request);

    CompletionStage<Void> send(String address, LegoRequest<Buffer> request);

    CompletionStage<Void> publish(String address, LegoRequest<Buffer> request);
}

@AllArgsConstructor
class DefaultEventBusRpcSender implements EventBusRpcSender {

    private final @NonNull EventBus eventBus;

    @Override
    public CompletionStage<LegoResponse<Buffer>> request(String address, LegoRequest<Buffer> request) {
        return eventBus.request(address, request.getBody(), getDeliveryOptions(request)) //
            .toCompletionStage() //
            .thenApply(resp -> convertResponse(resp, address, request)) //
            .exceptionally(ex -> handlerException(ex, address, request));
    }

    @Override
    public CompletionStage<Void> send(String address, LegoRequest<Buffer> request) {
        return eventBus.sender(address, getDeliveryOptions(request))
            .write(request.getBody())
            .toCompletionStage();
    }

    @Override
    public CompletionStage<Void> publish(String address, LegoRequest<Buffer> request) {
        return eventBus.publisher(address, getDeliveryOptions(request))
            .write(request.getBody())
            .toCompletionStage();
    }

    private DeliveryOptions getDeliveryOptions(LegoRequest<Buffer> request) {
        var options = new DeliveryOptions();
        for (var header : request.getHeaders())
            options.addHeader(header.getKey(), header.getValue());

        var params = request.getParams();
        if (params != null) {
            var paramCompressor = ParamCompressor.prefixedHeader(EventBusConsts.PARAM_PREFIX, options);
            for (var param : params)
                paramCompressor.setParam(param.getKey(), param.getValue());
        }
        return options;
    }

    private LegoResponse<Buffer> convertResponse(Message<?> message, String address, LegoRequest<Buffer> request) {
        var error = message.headers().get(EventBusConsts.ERROR_HEADER);
        if (error == null || error.isBlank())
            return new EventBusClientResponse(message);

        var body = message.body();
        JsonObject json;
        if (body instanceof JsonObject jo)
            json = jo;
        else if (body instanceof Buffer buff)
            json = buff.toJsonObject();
        else if (body instanceof byte[] bytes)
            json = Buffer.buffer(bytes).toJsonObject();
        else
            throw new UnsupportedResponseException(body);

        var map = new HashMap<>(json.getMap());
        map.put("__address", address);
        map.put("__request", request.toString());
        throw new LegoBusinessException(error, map);
    }

    private LegoResponse<Buffer> handlerException(Throwable error, String address, LegoRequest<Buffer> request) {
        var cause = ExceptionUtils.extractMeaningfulCause(error);
        if (!(cause instanceof ReplyException replyEx)) {
            ExceptionUtils.rethrows(cause);
            return null;
        }
        switch (replyEx.failureType()) {
            case TIMEOUT -> throw new RequestTimeoutException(address, request);
            case NO_HANDLERS -> throw new NoHandlerException(address, request);
            case RECIPIENT_FAILURE -> {
                var msg = replyEx.getMessage();
                if (isNull(msg)) {
                    throw new LegoBusinessException("Receive no error message");
                }
                var errorData = new JsonObject(msg);
                var data = errorData.getJsonObject("data");
                throw new LegoBusinessException(errorData.getString("error"), nonNull(data) ? data.getMap() : Map.of());
            }
            default -> ExceptionUtils.rethrows(cause);
        }
        return null;
    }
}
