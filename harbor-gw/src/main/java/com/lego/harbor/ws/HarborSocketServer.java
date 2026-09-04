package com.lego.harbor.ws;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import lombok.Builder;
import lombok.NonNull;

@Builder
public class HarborSocketServer extends AbstractVerticle {

    private final int port;
    private final @NonNull String serverId;
    private final @NonNull String host;
    private final @NonNull String path;
    private final HarborSessionManager sessionManager;

    @Override
    public void start(Promise<Void> promise) {
        var router = Router.router(vertx);
        var normalizedPath = path.trim();
        if (!normalizedPath.endsWith("/")) {
            normalizedPath += "/";
        }
        if (!normalizedPath.endsWith("*")) {
            normalizedPath += "*";
        }

        router.route(normalizedPath)
                .subRouter(SockJSHandler.create(vertx)
                        .socketHandler(sessionManager::onConnection));

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(port, host)
                .<Void>mapEmpty()
                .onSuccess(promise::complete)
                .onFailure(promise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
    }
}
