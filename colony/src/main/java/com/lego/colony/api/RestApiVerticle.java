package com.lego.colony.api;

import com.lego.colony.ws.history.MessageHistoryRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 2 endpoint: {@code GET /healthcheck} cho k8s {@code readinessProbe} (trả 200 lúc bình thường,
 * 503 kể từ khi {@code ChatSessionManager.drain()} bắt đầu — scale down/rolling update, để k8s
 * ngừng route session mới vào pod đang rút lui), và {@code GET /messages} để lấy lịch sử tin nhắn
 * của 1 conversation (xem {@link MessageHistoryRegistry}) — chỉ mới có 1 route trước đây nên vẫn
 * giữ nguyên phong cách {@code HttpServer} thuần (không dùng {@code vertx-web} Router).
 */
@Slf4j
@RequiredArgsConstructor
public class RestApiVerticle extends AbstractVerticle {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;

  private final int port;
  private final BooleanSupplier ready;
  private final MessageHistoryRegistry history;

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
    if (request.method() == HttpMethod.GET && "/healthcheck".equals(request.path())) {
      handleHealthcheck(request.response());
      return;
    }
    if (request.method() == HttpMethod.GET && "/messages".equals(request.path())) {
      handleListMessages(request);
      return;
    }
    request.response().putHeader("Content-Type", "text/plain").setStatusCode(404).end("not found");
  }

  private void handleHealthcheck(HttpServerResponse response) {
    response.putHeader("Content-Type", "text/plain");
    if (ready.getAsBoolean()) {
      response.setStatusCode(200).end("ok");
    } else {
      response.setStatusCode(503).end("draining");
    }
  }

  private void handleListMessages(HttpServerRequest request) {
    var response = request.response().putHeader("Content-Type", "application/json");
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      response.setStatusCode(400).end(new JsonObject().put("error", "missing/invalid conversationId").encode());
      return;
    }
    var limit = parseLimit(request.getParam("limit"));
    Long before = parseBefore(request.getParam("before"));

    history
        .listMessages(conversationId, limit, before)
        .thenAccept(messages -> response.setStatusCode(200).end(messages.encode()))
        .exceptionally(
            ex -> {
              log.error("failed to list messages for conversation {}", conversationId, ex);
              response.setStatusCode(500).end(new JsonObject().put("error", "internal error").encode());
              return null;
            });
  }

  private static int parseLimit(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_LIMIT;
    }
    try {
      return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw)));
    } catch (NumberFormatException e) {
      return DEFAULT_LIMIT;
    }
  }

  private static Long parseBefore(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
