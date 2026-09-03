package com.lego.colony.api;

import com.lego.colony.ws.history.MessageHistoryRegistry;
import com.lego.colony.ws.user.UserRegistry;
import com.lego.namnv.core.common.token.JwtHelper;
import com.lego.namnv.core.common.token.NdlTokenException;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code GET /healthcheck} cho k8s {@code readinessProbe} (trả 200 lúc bình thường, 503 kể từ khi
 * {@code ChatSessionManager.drain()} bắt đầu — scale down/rolling update, để k8s ngừng route
 * session mới vào pod đang rút lui); {@code GET /messages} lấy lịch sử tin nhắn của 1 conversation
 * (xem {@link MessageHistoryRegistry}); {@code POST /register}/{@code POST /login} đăng ký/đăng
 * nhập tài khoản thật (username + password, trả về token JWT — xem {@link JwtHelper}); {@code GET
 * /users} liệt kê user; {@code PUT /users} đổi tên hiển thị của CHÍNH mình (bắt buộc {@code
 * Authorization: Bearer <token>}) — xem {@link UserRegistry}. Chỉ vài route đơn giản nên vẫn giữ
 * phong cách {@code HttpServer} thuần (không dùng {@code vertx-web} Router).
 */
@Slf4j
@RequiredArgsConstructor
public class RestApiVerticle extends AbstractVerticle {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final int MAX_USERNAME_LENGTH = 64;
  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final Duration TOKEN_TTL = Duration.ofDays(7);
  private static final String BEARER_PREFIX = "Bearer ";

  private final int port;
  private final BooleanSupplier ready;
  private final MessageHistoryRegistry history;
  private final UserRegistry users;
  private final JwtHelper jwtHelper;

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
    if (request.method() == HttpMethod.OPTIONS) {
      // Preflight -- can tu khi PUT /users doi hoi header Authorization (custom header), cac route
      // GET don gian truoc do khong bao gio trigger preflight nen chua can xu ly OPTIONS.
      request
          .response()
          .putHeader("Access-Control-Allow-Origin", "*")
          .putHeader("Access-Control-Allow-Methods", "GET,PUT,POST,OPTIONS")
          .putHeader("Access-Control-Allow-Headers", "Content-Type,Authorization")
          .setStatusCode(204)
          .end();
      return;
    }
    if (request.method() == HttpMethod.GET && "/healthcheck".equals(request.path())) {
      handleHealthcheck(request.response());
      return;
    }
    if (request.method() == HttpMethod.GET && "/messages".equals(request.path())) {
      handleListMessages(request);
      return;
    }
    if (request.method() == HttpMethod.GET && "/users".equals(request.path())) {
      handleListUsers(request);
      return;
    }
    if (request.method() == HttpMethod.PUT && "/users".equals(request.path())) {
      handleSetUsername(request);
      return;
    }
    if (request.method() == HttpMethod.POST && "/register".equals(request.path())) {
      handleRegister(request);
      return;
    }
    if (request.method() == HttpMethod.POST && "/login".equals(request.path())) {
      handleLogin(request);
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
    // CORS: cho phep goi tu browser dang mo demo.html (file:// hoac 1 origin khac han port nay) --
    // request GET don gian, khong co custom header nen khong can xu ly preflight OPTIONS rieng.
    var response = jsonResponse(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      response.setStatusCode(400).end(err("missing/invalid conversationId"));
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
              response.setStatusCode(500).end(err("internal error"));
              return null;
            });
  }

  private void handleListUsers(HttpServerRequest request) {
    var response = jsonResponse(request);
    users
        .listUsers()
        .thenAccept(list -> response.setStatusCode(200).end(list.encode()))
        .exceptionally(
            ex -> {
              log.error("failed to list users", ex);
              response.setStatusCode(500).end(err("internal error"));
              return null;
            });
  }

  /** {@code POST /register} — body JSON {@code {username, password}}. id do server tự sinh, password được hash trước khi lưu. */
  private void handleRegister(HttpServerRequest request) {
    var response = jsonResponse(request);
    request.bodyHandler(
        buffer -> {
          JsonObject body;
          try {
            body = buffer.toJsonObject();
          } catch (Exception e) {
            response.setStatusCode(400).end(err("invalid json body"));
            return;
          }
          var validationError = validateCredentials(body);
          if (validationError != null) {
            response.setStatusCode(400).end(err(validationError));
            return;
          }
          var username = body.getString("username").strip();
          var password = body.getString("password");
          users
              .registerUser(username, password)
              .thenAccept(created -> response.setStatusCode(200).end(withToken(created).encode()))
              .exceptionally(
                  ex -> {
                    if (unwrap(ex) instanceof UserRegistry.UsernameTakenException) {
                      response.setStatusCode(409).end(err("username taken"));
                    } else {
                      log.error("failed to register user {}", username, ex);
                      response.setStatusCode(500).end(err("internal error"));
                    }
                    return null;
                  });
        });
  }

  /** {@code POST /login} — body JSON {@code {username, password}}. Trả 401 chung chung cho cả "không tồn tại" lẫn "sai mật khẩu". */
  private void handleLogin(HttpServerRequest request) {
    var response = jsonResponse(request);
    request.bodyHandler(
        buffer -> {
          JsonObject body;
          try {
            body = buffer.toJsonObject();
          } catch (Exception e) {
            response.setStatusCode(400).end(err("invalid json body"));
            return;
          }
          var username = body.getString("username");
          var password = body.getString("password");
          if (isBlank(username) || isBlank(password)) {
            response.setStatusCode(400).end(err("missing username/password"));
            return;
          }
          users
              .verifyLogin(username.strip(), password)
              .thenAccept(
                  found -> {
                    if (found.isEmpty()) {
                      response.setStatusCode(401).end(err("invalid credentials"));
                      return;
                    }
                    response.setStatusCode(200).end(withToken(found.get()).encode());
                  })
              .exceptionally(
                  ex -> {
                    log.error("failed to login user {}", username, ex);
                    response.setStatusCode(500).end(err("internal error"));
                    return null;
                  });
        });
  }

  /**
   * {@code PUT /users?username=<ten hien thi moi>} — đổi tên hiển thị của CHÍNH mình. id lấy từ
   * token đã verify (header {@code Authorization: Bearer <token>}), KHÔNG còn nhận id qua query
   * param nữa (trước đây bất kỳ ai cũng đổi tên được bất kỳ user nào — lỗ hổng thật khi username
   * giờ là credential đăng nhập).
   */
  private void handleSetUsername(HttpServerRequest request) {
    var response = jsonResponse(request);
    var userId = authenticatedUserId(request);
    if (userId.isEmpty()) {
      response.setStatusCode(401).end(err("missing/invalid/expired token"));
      return;
    }
    var id = userId.get();
    var rawUsername = request.getParam("username");
    if (rawUsername == null || rawUsername.isBlank()) {
      response.setStatusCode(400).end(err("missing username"));
      return;
    }
    var username = rawUsername.strip();
    if (username.length() > MAX_USERNAME_LENGTH) {
      response.setStatusCode(400).end(err("username too long"));
      return;
    }

    users
        .updateUsername(id, username)
        .thenAccept(unused -> response.setStatusCode(200).end(new JsonObject().put("id", id.toString()).put("username", username).encode()))
        .exceptionally(
            ex -> {
              if (unwrap(ex) instanceof UserRegistry.UsernameTakenException) {
                response.setStatusCode(409).end(err("username taken"));
              } else {
                log.error("failed to update username for user {}", id, ex);
                response.setStatusCode(500).end(err("internal error"));
              }
              return null;
            });
  }

  /** Đọc + verify header {@code Authorization: Bearer <token>} -- Optional rỗng cho mọi lý do thất bại (thiếu header, token sai/hết hạn). */
  private Optional<UUID> authenticatedUserId(HttpServerRequest request) {
    var header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      return Optional.empty();
    }
    try {
      var decoded = jwtHelper.decode(header.substring(BEARER_PREFIX.length()).strip());
      return Optional.ofNullable(decoded.getUUID("userId"));
    } catch (NdlTokenException e) {
      return Optional.empty();
    }
  }

  private String issueToken(UUID userId, String username) {
    return jwtHelper.tokenBuilder().withClaim("userId", userId).withClaim("username", username).withClaim("exp", Instant.now().plus(TOKEN_TTL)).build();
  }

  private JsonObject withToken(JsonObject user) {
    return user.copy().put("token", issueToken(UUID.fromString(user.getString("id")), user.getString("username")));
  }

  private static String validateCredentials(JsonObject body) {
    var username = body.getString("username");
    var password = body.getString("password");
    if (isBlank(username)) {
      return "missing username";
    }
    if (username.strip().length() > MAX_USERNAME_LENGTH) {
      return "username too long";
    }
    if (isBlank(password)) {
      return "missing password";
    }
    if (password.length() < MIN_PASSWORD_LENGTH) {
      return "password too short (min " + MIN_PASSWORD_LENGTH + " chars)";
    }
    return null;
  }

  private static HttpServerResponse jsonResponse(HttpServerRequest request) {
    return request.response().putHeader("Content-Type", "application/json").putHeader("Access-Control-Allow-Origin", "*");
  }

  private static String err(String message) {
    return new JsonObject().put("error", message).encode();
  }

  private static Throwable unwrap(Throwable ex) {
    return ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
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
