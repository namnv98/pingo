package com.lego.namnv.core.eventbus.server;

import com.google.inject.Injector;
import com.lego.namnv.core.common.exception.ExceptionUtils;
import com.lego.namnv.core.common.exception.LegoBusinessException;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.namnv.core.api.IApiKey;
import com.lego.namnv.core.api.IRequest;
import com.lego.namnv.core.api.OrgHandlerProvider;
import com.lego.namnv.core.api.annotaion.Type;
import com.lego.namnv.core.api.registry.IApiRegistry;
import com.lego.namnv.core.api.support.IConsts;
import com.lego.namnv.core.eventbus.EventBusConsts;
import com.lego.namnv.core.message.CommonResponseUtils;
import com.lego.namnv.core.message.response.JsonObjectResponse;
import com.lego.namnv.core.message.response.LegoError;
import com.lego.namnv.core.message.response.LegoResponse;
import com.lego.namnv.core.message.response.ProvidedBodyResponse;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.Objects.isNull;
import static java.util.concurrent.CompletableFuture.completedStage;

@Log4j2
@RequiredArgsConstructor
public class LegoEventBusServer extends AbstractVerticle {

    private static final String VERTX_EB = "vertx_eb";

    private static final String TRACE_ID = "traceId";
    private final @NonNull EventBusServerConfig config;
    private final @NonNull IApiRegistry apiRegistry;
    private final @NonNull EndpointExtractor endpointExtractor;
    private ParamExtractorProvider paramExtractorProvider = ParamExtractorProvider.prefixed(EventBusConsts.PARAM_PREFIX);
    private boolean replyBytes = false;
    private boolean errorReplyViaHeader = false;
    private Map<String, ApiInfo> endpointApis;
    private final @NonNull Injector injector;

    @Builder
    public LegoEventBusServer(EventBusServerConfig config, IApiRegistry apiRegistry,
                              EndpointExtractor endpointExtractor, ParamExtractorProvider paramExtractorProvider, @NonNull Injector injector) {
        this.config = config;
        this.apiRegistry = apiRegistry;
        this.endpointExtractor = endpointExtractor;
        this.paramExtractorProvider = paramExtractorProvider;
        this.replyBytes = config.isReplyBytes();
        this.errorReplyViaHeader = config.isErrorReplyViaHeader();
        this.injector = injector;
    }

    private static Map<String, ApiInfo> generateEndpointApis(Map<IApiKey, OrgHandlerProvider> apis) {
        var endpointApis = new HashMap<String, ApiInfo>();
        for (var e : apis.entrySet()) {
            var key = e.getKey();
            endpointApis.put(key.getEndpoint(), new ApiInfo(key, e.getValue()));
        }
        return Collections.unmodifiableMap(endpointApis);
    }

    @Override
    public void start() throws Exception {
        var apis = apiRegistry.lookup(Type.EVENT_BUS);
        endpointApis = generateEndpointApis(apis);
        vertx.eventBus().consumer(config.getAddress(), this::onEventBusMessage);
    }

    private ApiInfo extractApiInfo(Message<Buffer> message) {
        var endpoint = endpointExtractor.extract(message);
        if (isNull(endpoint))
            throw new IllegalArgumentException("no endpoint");

        var apiInfo = endpointApis.get(endpoint);
        if (isNull(apiInfo))
            throw new RuntimeException("NoHandlerException(endpoint)");

        return apiInfo;
    }

    private void onEventBusMessage(Message<Buffer> message) {
        ApiInfo apiInfo;
        try {
            apiInfo = extractApiInfo(message);
        } catch (Throwable e) {
            message.fail(1, "{\"error\": \"Cannot infer api-info from message\"}");
            return;
        }

        try {
            handleUnsafe(apiInfo, message);
        } catch (Throwable e) {
            var resp = translateException(message, null, e);
            reply(apiInfo, message, resp);
        }
    }

    private void reply(ApiInfo apiInfo, Message<Buffer> message, LegoResponse<Buffer> response) {
        if (!apiInfo.key().isRpc())
            return;

        var options = new DeliveryOptions();
        for (var header : response.getHeaders())
            options.addHeader(header.getKey(), header.getValue());

        if (response instanceof LegoError<Buffer> err) {
            options.addHeader(EventBusConsts.ERROR_HEADER, err.getError());
            if (!errorReplyViaHeader) {
                message.fail(1, new JsonObject().put("error", err.getError()).put("data", err.getBody().toJsonObject())
                    .encode());
                return;
            }
        }

        if (replyBytes) {
            message.reply(response.getBody().getBytes(), options);
            return;
        }
        message.reply(response.getBody(), options);
    }

    @SneakyThrows
    private void handleUnsafe(ApiInfo apiInfo, Message<Buffer> message) {
        var apiKey = apiInfo.key();
        var paramExtractor = paramExtractorProvider == null //
            ? ParamExtractor.UNAVAILABLE //
            : paramExtractorProvider.getExtractor(apiKey, message);

        var request = new EventBusServerRequest(message, apiKey, paramExtractor);
        tryCallApi(request, apiInfo.api)
            .exceptionally(ex -> translateException(message, apiKey, ex)) //
            .thenAccept(resp -> reply(apiInfo, message, resp)) //
            .exceptionally(ex -> {
                log.error("an error occur while sending reply: [message={}]", message, ex);
                return ExceptionUtils.rethrows(ex);
            });
    }

    private CompletionStage<LegoResponse<Buffer>> tryCallApi(IRequest request, OrgHandlerProvider api) {
        try {
            return api.getHandler(injector).handle(request).thenCompose(r -> completedStage(convertResponse(r)));
        } catch (Throwable e) {
            return CompletableFuture.failedStage(e);
        }
    }

    public LegoResponse<Buffer> convertResponse(Object objectResponse) {
        if (Objects.isNull(objectResponse)) {
            return new ProvidedBodyResponse<>(CommonResponseUtils.DEFAULT_SUCCESS_JSON);
        }
        if (objectResponse instanceof byte[] bytes) {
            return new ProvidedBodyResponse<>(Buffer.buffer(bytes));
        }
        return new JsonObjectResponse(new JsonObject().put("data", objectResponse)).transform(JsonObject::toBuffer);
    }


    private LegoError<Buffer> translateException(Message<Buffer> message, IApiKey apiKey, Throwable exception) {
        printStackTrace(exception);
        var cause = ExceptionUtils.extractMeaningfulCause(exception);
        if (cause instanceof LegoBusinessException bizEx)
            return LegoError.of(bizEx.getKey(), bizEx.getItems()) //
                .transform(JsonObject::mapFrom) //
                .transform(JsonObject::toBuffer);

        var traceId = UUIDUtils.timeBasedUuidAsString();
        log.error("Internal server error: [traceId={}]\n{}", traceId, EventBusServerRequest.toString(message, apiKey),
            exception);
        return LegoError.of(IConsts.UNKNOWN_ERROR, TRACE_ID, traceId) //
            .transform(JsonObject::mapFrom) //
            .transform(JsonObject::toBuffer);
    }

    public static void main(String[] args) {
        var traceId = UUIDUtils.timeBasedUuidAsString();

       var a= LegoError.of(IConsts.UNKNOWN_ERROR, TRACE_ID, traceId) //
                .transform(JsonObject::mapFrom);
        System.out.println(a);
    }
    private record ApiInfo(IApiKey key, OrgHandlerProvider api) {
    }

    private void printStackTrace(Throwable throwable) {
        if (config.isPrintStackTrace()) {
            throwable.printStackTrace();
        }
    }
}
