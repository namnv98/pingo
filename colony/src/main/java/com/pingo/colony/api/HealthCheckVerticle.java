package com.pingo.colony.api;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Health endpoint riêng cho k8s {@code readinessProbe}: {@code GET /healthcheck} trả 200 lúc bình
 * thường, 503 kể từ khi {@code ChatSessionManager.drain()} bắt đầu (scale down/rolling update) —
 * cùng pattern với harbor's {@code HealthCheckVerticle}. Từ khi REST API (register/login/messages/
 * users/conversations) tách sang service {@code hall} riêng, colony không còn route HTTP nào
 * khác ngoài cái này.
 */
@Slf4j
@RequiredArgsConstructor
public class HealthCheckVerticle extends AbstractVerticle {

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

  private void handleRequest(io.vertx.core.http.HttpServerRequest request) {
    var response = request.response().putHeader("Content-Type", "text/plain");
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
