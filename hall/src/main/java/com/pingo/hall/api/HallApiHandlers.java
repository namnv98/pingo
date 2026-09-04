package com.pingo.hall.api;

import com.google.inject.Inject;
import com.pingo.colony.domain.history.MessageHistoryRegistry;
import com.pingo.colony.domain.membership.ConversationMembershipRegistry;
import com.pingo.colony.domain.user.UserRegistry;
import com.pingo.core.api.IRequest;
import com.pingo.core.api.annotaion.ApiMethod;
import com.pingo.core.api.annotaion.RegisterHandler;
import com.pingo.core.api.annotaion.RegisterIApi;
import com.pingo.core.api.annotaion.Type;
import com.pingo.core.common.exception.LegoBusinessException;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.common.token.NdlTokenException;
import com.pingo.hall.api.error.HallErrorKeys;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;

/**
 * Handler cho toàn bộ REST API của `hall` — dispatch qua {@code IApiRegistry} (quét
 * {@code @RegisterHandler} lúc khởi động, xem {@code HallAppModule}), KHÔNG còn tự viết
 * {@code HttpServer}/routing tay như {@code RestApiVerticle} cũ. Trả về {@code byte[]} (JSON thô)
 * thay vì để framework tự bọc `{"data": ...}` — giữ đúng wire format cũ (client/e2e test đang parse
 * field phẳng như {@code token}/{@code id}, không phải {@code data.token}).
 */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HallApiHandlers {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final int MAX_USERNAME_LENGTH = 64;
  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final Duration TOKEN_TTL = Duration.ofDays(7);
  private static final String BEARER_PREFIX = "Bearer ";

  private final MessageHistoryRegistry history;
  private final UserRegistry users;
  private final JwtHelper jwtHelper;
  private final ConversationMembershipRegistry membership;
  private final AtomicBoolean ready;

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "healthcheck", type = Type.HTTP)})
  public CompletionStage<byte[]> healthcheck(IRequest request) {
    if (!ready.get()) {
      throw new LegoBusinessException(HallErrorKeys.DRAINING, "draining");
    }
    return completed(new JsonObject().put("status", "ok"));
  }

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "messages", type = Type.HTTP)})
  public CompletionStage<byte[]> listMessages(IRequest request) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var limit = parseLimit(request.getParam("limit"));
    Long before = parseBefore(request.getParam("before"));
    return history.listMessages(conversationId, limit, before).thenApply(HallApiHandlers::bytes);
  }

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "users", type = Type.HTTP)})
  public CompletionStage<byte[]> listUsers(IRequest request) {
    return users.listUsers().thenApply(HallApiHandlers::bytes);
  }

  /** {@code POST /register} — body JSON {@code {username, password}}. id do server tự sinh, password được hash trước khi lưu. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "register", type = Type.HTTP)})
  public CompletionStage<byte[]> register(IRequest request) {
    var body = parseJsonBody(request);
    var validationError = validateCredentials(body);
    if (validationError != null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, validationError);
    }
    var username = body.getString("username").strip();
    var password = body.getString("password");
    return users
        .registerUser(username, password)
        .thenApply(created -> bytes(withToken(created)))
        .exceptionally(ex -> {
          if (unwrap(ex) instanceof UserRegistry.UsernameTakenException) {
            throw new LegoBusinessException(HallErrorKeys.CONFLICT, "username taken");
          }
          throw rethrow(ex);
        });
  }

  /** {@code POST /login} — body JSON {@code {username, password}}. Trả 401 chung chung cho cả "không tồn tại" lẫn "sai mật khẩu". */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "login", type = Type.HTTP)})
  public CompletionStage<byte[]> login(IRequest request) {
    var body = parseJsonBody(request);
    var username = body.getString("username");
    var password = body.getString("password");
    if (isBlank(username) || isBlank(password)) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing username/password");
    }
    return users
        .verifyLogin(username.strip(), password)
        .thenApply(found -> {
          if (found.isEmpty()) {
            throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "invalid credentials");
          }
          return bytes(withToken(found.get()));
        });
  }

  /**
   * {@code PUT /users?username=<tên mới>} — đổi tên hiển thị của CHÍNH mình. id lấy từ token đã
   * verify, không nhận qua query param nữa (lỗ hổng cũ: ai cũng đổi tên được user khác).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "users", type = Type.HTTP)})
  public CompletionStage<byte[]> setUsername(IRequest request) {
    var id = requireAuthenticatedUserId(request);
    var rawUsername = request.getParam("username");
    if (rawUsername == null || rawUsername.isBlank()) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing username");
    }
    var username = rawUsername.strip();
    if (username.length() > MAX_USERNAME_LENGTH) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "username too long");
    }
    return users
        .updateUsername(id, username)
        .thenApply(unused -> bytes(new JsonObject().put("id", id.toString()).put("username", username)))
        .exceptionally(ex -> {
          if (unwrap(ex) instanceof UserRegistry.UsernameTakenException) {
            throw new LegoBusinessException(HallErrorKeys.CONFLICT, "username taken");
          }
          throw rethrow(ex);
        });
  }

  /** {@code GET /conversations} (bắt buộc {@code Authorization: Bearer <token>}) — mọi conversation mà CHÍNH mình đang là thành viên, mới hoạt động gần đây trước. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "conversations", type = Type.HTTP)})
  public CompletionStage<byte[]> listConversations(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    return membership.listConversationsForUser(userId).thenApply(HallApiHandlers::bytes);
  }

  /** Đọc + verify header {@code Authorization: Bearer <token>} -- ném 401 cho mọi lý do thất bại (thiếu header, token sai/hết hạn). */
  private UUID requireAuthenticatedUserId(IRequest request) {
    var header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
    try {
      var decoded = jwtHelper.decode(header.substring(BEARER_PREFIX.length()).strip());
      var userId = decoded.getUUID("userId");
      if (userId == null) {
        throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
      }
      return userId;
    } catch (NdlTokenException e) {
      throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
  }

  private String issueToken(UUID userId, String username) {
    return jwtHelper.tokenBuilder().withClaim("userId", userId).withClaim("username", username).withClaim("exp", Instant.now().plus(TOKEN_TTL)).build();
  }

  private JsonObject withToken(JsonObject user) {
    return user.copy().put("token", issueToken(UUID.fromString(user.getString("id")), user.getString("username")));
  }

  private static JsonObject parseJsonBody(IRequest request) {
    try {
      return request.getBody().toJsonObject();
    } catch (Exception e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "invalid json body");
    }
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

  private static CompletionStage<byte[]> completed(JsonObject body) {
    return java.util.concurrent.CompletableFuture.completedStage(bytes(body));
  }

  private static byte[] bytes(JsonObject body) {
    return body.encode().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(JsonArray body) {
    return body.encode().getBytes(StandardCharsets.UTF_8);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static Throwable unwrap(Throwable ex) {
    return ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
  }

  private static RuntimeException rethrow(Throwable ex) {
    return ex instanceof RuntimeException re ? re : new RuntimeException(ex);
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
