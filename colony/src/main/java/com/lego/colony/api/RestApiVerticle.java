package com.lego.colony.api;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;

/**
 * Health endpoint cho k8s {@code readinessProbe}: {@code GET /healthcheck} trả 200 lúc bình
 * thường, 503 kể từ khi {@code ChatSessionManager.drain()} bắt đầu (scale down/rolling update) —
 * để k8s ngừng route session mới vào pod đang rút lui.
 */
@RequiredArgsConstructor
public class RestApiVerticle extends AbstractVerticle {

  private final int port;
  private final BooleanSupplier ready;

  @Override
  public void start(Promise<Void> promise) {
    vertx
        .createHttpServer()
        .requestHandler(this::handleRequest)
        .listen(port)
        .<Void>mapEmpty()
        .onSuccess(promise::complete)
        .onFailure(promise::fail);
  }

  private void handleRequest(HttpServerRequest request) {
    HttpServerResponse response = request.response().putHeader("Content-Type", "text/plain");
    if (request.method() != HttpMethod.GET || !"/healthcheck".equals(request.path())) {
      response.setStatusCode(404).end("not found");
      return;
    }
    if (ready.getAsBoolean()) {
      response.setStatusCode(200).end("ok");
    } else {
      response.setStatusCode(503).end("draining");
    }
  }
}
