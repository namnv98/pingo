package com.pingo.core.http;

import com.google.inject.Injector;
import com.pingo.core.common.exception.ExceptionUtils;
import com.pingo.core.common.exception.LegoBusinessException;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.core.api.IApiKey;
import com.pingo.core.api.IRequest;
import com.pingo.core.api.OrgHandlerProvider;
import com.pingo.core.api.annotaion.ApiMethod;
import com.pingo.core.api.registry.IApiRegistry;
import com.pingo.core.http.config.HttpServerConfig;
import com.pingo.core.http.config.HttpStatusErrorMapping;
import com.pingo.core.message.response.JsonObjectResponse;
import com.pingo.core.message.response.LegoError;
import com.pingo.core.message.response.LegoResponse;
import com.pingo.core.message.response.ProvidedBodyResponse;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.concurrent.CompletableFuture.completedStage;

@Log4j2
public class LegoHttpServer extends AbstractVerticle {
    private final @NonNull IApiRegistry apiRegistry;
    private final @NonNull Injector injector;
    private static final String TRACE_ID = "traceId";

    private static final String DEPRECATION = "deprecation";

    private static final int HTTP_OK = 200;

    private static final String DEFAULT_VERSION_PREFIX = "v";

    private final String versionPrefix = DEFAULT_VERSION_PREFIX;

    private final @NonNull HttpServerConfig config;

    private final HttpStatusErrorMapping errorMapping;
    private final @NonNull Map<String, String> defaultHeaders;
    private final HttpErrorBodyBuilder errorBodyBuilder;

    @Builder
    private LegoHttpServer(
            HttpServerConfig config,
            IApiRegistry apiRegistry,
            HttpStatusErrorMapping errorMapping,
            HttpErrorBodyBuilder errorBuilder,
            Injector injector) {
        this.config = config;
        this.apiRegistry = apiRegistry;
        this.errorMapping = errorMapping;
        this.defaultHeaders = initDefaultHeaders(config.getHeaders());
        this.errorBodyBuilder = errorBuilder != null ? errorBuilder : HttpErrorBodyBuilder.DEFAULT;
        this.injector = injector;
    }

    private Map<String, String> initDefaultHeaders(Map<String, Object> headers) {
        return headers == null || headers.isEmpty() //
                ? Map.of() //
                : headers.entrySet().stream()
                .collect(
                        Collectors.toMap(
                                e -> e.getKey().trim().toLowerCase(), e -> e.getValue().toString()));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        vertx
                .createHttpServer() //
                .requestHandler(initRouter()) //
                .listen(config.getPort(), config.getHost()) //
                .map(any -> (Void) null) //
                .onSuccess(startPromise::complete) //
                .onFailure(startPromise::fail);
    }

    private Router initRouter() {
        var router = Router.router(vertx);
        var bodyHandler = BodyHandler.create().setBodyLimit(1024 * 1024);

        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("origin");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("Ngrok-Skip-Browser-Warning");

        Set<HttpMethod> allowedMethods = new HashSet<>();
        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);
        allowedMethods.add(HttpMethod.DELETE);
        allowedMethods.add(HttpMethod.PATCH);
        allowedMethods.add(HttpMethod.OPTIONS);
        allowedMethods.add(HttpMethod.PUT);

        router
                .route()
                .handler(
                        // Vert.x 5.x bo CorsHandler.create(String) -- create() khong tham so, roi
                        // khai bao origin qua addOriginWithRegex/addOrigin rieng. Giu dung regex cu
                        // (".*.", khop moi origin) qua addOriginWithRegex de khong doi hanh vi.
                        CorsHandler.create()
                                .addOriginWithRegex(".*.")
                                .allowCredentials(true)
                                .allowedMethods(allowedMethods)
                                .allowedHeaders(allowedHeaders));

        router.route().handler(bodyHandler);
        router
                .route()
                .handler(
                        routingContext -> {
                            routingContext
                                    .response()
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                                    .putHeader(
                                            "Access-Control-Allow-Headers",
                                            "X-API-KEY, Origin, X-Requested-With, Content-Type, Accept, Access-Control-Request-Method")
                                    .putHeader("Access-Control-Max-Age", "3600")
                                    .putHeader("Allow", "GET, POST, PUT, DELETE, OPTIONS");
                            routingContext.next();
                        });

        router
                .options("/*")
                .handler(
                        routingContext -> {
                            routingContext
                                    .response()
                                    .putHeader("Access-Control-Allow-Origin", "*")
                                    .putHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                                    .putHeader(
                                            "Access-Control-Allow-Headers",
                                            "X-API-KEY, Origin, X-Requested-With, Content-Type, Accept, Access-Control-Request-Method")
                                    .putHeader("Access-Control-Max-Age", "3600")
                                    .putHeader("Allow", "GET, POST, PUT, DELETE, OPTIONS")
                                    .setStatusCode(200)
                                    .end();
                        });
        var listApi = apiRegistry.getAllKey();
        for (IApiKey key : listApi) {
            var fullEndpoint = "/" + key.getEndpoint();
            router
                    .route(toHttpMethod(key.getMethod()), fullEndpoint) //
                    .handler(createApiHandler(key, apiRegistry.lookup(key)));
            log.debug(fullEndpoint);
        }
        return router;
    }

    private HttpMethod toHttpMethod(ApiMethod method) {
        return HttpMethod.valueOf(method.name());
    }

    private void callApi(IApiKey key, OrgHandlerProvider api, RoutingContext rc) {
        IRequest request;
        try {
            request = new WrappedHttpRequest(key, rc);
        } catch (Throwable e) {
            handleFinalException(key, rc, e);
            return;
        }

        tryCallApi(request, api) //
                .exceptionally(ex -> translateException(request, ex)) //
                .thenCompose(resp -> sendResponse(key, resp, rc.response())) //
                .exceptionally(ex -> handleFinalException(key, rc, ex));
    }

    private CompletionStage<Void> sendResponse(
            IApiKey key, LegoResponse<Buffer> response, HttpServerResponse responder) {
        var error = response instanceof LegoError<?> err ? err.getError() : null;
        populateStatusCode(responder, error);
        populateHeaders(key, responder, response.getHeaders());
        var body = response.getBody();
        return (isNull(body) ? responder.end() : responder.end(response.getBody())).toCompletionStage();
    }

    private void populateStatusCode(HttpServerResponse responder, String error) {
        if (error == null || error.isBlank()) {
            responder.setStatusCode(HTTP_OK);
            return;
        }
        responder.setStatusCode(errorMapping.lookup(error));
    }

    private void populateHeaders(
            IApiKey apiKey, HttpServerResponse responder, Iterable<Map.Entry<String, String>> headers) {
        for (Map.Entry<String, String> entry : headers) {
            responder.putHeader(entry.getKey(), entry.getValue());
        }
    }

    private Handler<RoutingContext> createApiHandler(IApiKey key, OrgHandlerProvider api) {
        return rc -> callApi(key, api, rc);
    }

    private CompletionStage<LegoResponse<Buffer>> tryCallApi(
            IRequest request, OrgHandlerProvider api) {
        try {
            return api.getHandler(injector)
                    .handle(request)
                    .thenCompose(r -> completedStage(convertResponse(r)));
        } catch (Throwable e) {
            return CompletableFuture.failedStage(e);
        }
    }

    public LegoResponse<Buffer> convertResponse(Object objectResponse) {
        if (Objects.isNull(objectResponse)) {
            //            return new ProvidedBodyResponse<>(defaultHeaders,
            // CommonResponseUtils.DEFAULT_SUCCESS_JSON);
        }
        if (objectResponse instanceof byte[] bytes) {
            return new ProvidedBodyResponse<>(defaultHeaders, Buffer.buffer(bytes));
        }
        return new JsonObjectResponse(defaultHeaders, new JsonObject().put("data", objectResponse))
                .transform(JsonObject::toBuffer);
    }

    private LegoError<Buffer> translateException(IRequest request, Throwable throwable) {
        printStackTrace(throwable);

        var unwrapped = ExceptionUtils.extractMeaningfulCause(throwable);
        if (unwrapped instanceof LegoBusinessException bizEx)
            return LegoError.of(bizEx.getKey(), errorBodyBuilder.build(bizEx), defaultHeaders) //
                    .transform(JsonObject::toBuffer);

        var exTypeName = throwable.getClass().getName();
        var errorKey =
                errorMapping.isMapped(exTypeName) //
                        ? exTypeName //
                        : errorMapping.getDefaultErrorKey();
        var traceId = UUIDUtils.timeBasedUuidAsString();
        log.error(
                "Internal server error ({}): [traceId={}]\n{}", errorKey, traceId, request, throwable);
        return LegoError.of(
                        errorKey, errorBodyBuilder.build(errorKey, TRACE_ID, traceId), defaultHeaders) //
                .transform(JsonObject::toBuffer);
    }

    private Void handleFinalException(IApiKey key, RoutingContext rc, Throwable throwable) {
        printStackTrace(throwable);

        var responder = rc.response();
        var traceId = UUIDUtils.timeBasedUuidAsString();
        var request = rc.request();
        try {
            var errorKey = errorMapping.getDefaultErrorKey();
            populateStatusCode(responder, errorKey);
            populateHeaders(key, responder, defaultHeaders.entrySet());
            var payload =
                    LegoError.of(errorKey, errorBodyBuilder.build(errorKey, TRACE_ID, traceId)) //
                            .transform(JsonObject::mapFrom) //
                            .transform(JsonObject::toBuffer);
            log.error(
                    "fail to handle request [traceId={}]: {} {}\n--------\n",
                    traceId,
                    request.method(),
                    request.absoluteURI(),
                    throwable);
            responder
                    .end(payload.getBody()) //
                    .onFailure(
                            fail ->
                                    log.error(
                                            "still fail to send response to request (traceId={}): {}",
                                            traceId,
                                            request,
                                            fail));
        } catch (Throwable th) {
            var meaning = ExceptionUtils.extractMeaningfulCause(th);
            if (meaning instanceof IllegalStateException ise) return null;
            log.error("error while doing last try to response: {}\n{}", traceId, request, th);
        } finally {
            if (!responder.ended() && !responder.closed()) responder.end();
        }
        return null;
    }

    private void printStackTrace(Throwable throwable) {
        if (config.isPrintStackTrace()) {
            throwable.printStackTrace();
        }
    }
}
