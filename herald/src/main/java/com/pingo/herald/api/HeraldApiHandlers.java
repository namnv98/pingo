package com.pingo.herald.api;

import com.google.inject.Inject;
import com.pingo.chat.domain.notification.NotificationRegistry;
import com.pingo.chat.domain.notification.PushTokenRegistry;
import com.pingo.chat.domain.presence.PresenceRegistry;
import com.pingo.core.api.IRequest;
import com.pingo.core.api.annotaion.ApiMethod;
import com.pingo.core.api.annotaion.RegisterHandler;
import com.pingo.core.api.annotaion.RegisterIApi;
import com.pingo.core.api.annotaion.Type;
import com.pingo.core.common.exception.LegoBusinessException;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.common.token.NdlTokenException;
import com.pingo.herald.api.error.HeraldErrorKeys;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;

/** REST API của herald — đọc/đánh dấu đã đọc noti "bạn có tin nhắn mới" của CHÍNH mình (xem {@link NotificationRegistry}), đăng ký/huỷ token push FCM của thiết bị hiện tại (xem {@link PushTokenRegistry}), snapshot presence (xem {@link PresenceRegistry}). */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HeraldApiHandlers {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final int MAX_PRESENCE_USER_IDS = 200;
  private static final String BEARER_PREFIX = "Bearer ";

  private final NotificationRegistry notifications;
  private final PushTokenRegistry pushTokens;
  private final PresenceRegistry presence;
  private final JwtHelper jwtHelper;
  private final AtomicBoolean ready;

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "healthcheck", type = Type.HTTP)})
  public CompletionStage<byte[]> healthcheck(IRequest request) {
    if (!ready.get()) {
      throw new LegoBusinessException(HeraldErrorKeys.DRAINING, "draining");
    }
    return java.util.concurrent.CompletableFuture.completedStage(bytes(new JsonObject().put("status", "ok")));
  }

  /** {@code GET /notifications?unreadOnly=true&limit=50} (bắt buộc {@code Authorization: Bearer <token>}) — mới nhất trước. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "notifications", type = Type.HTTP)})
  public CompletionStage<byte[]> listNotifications(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var unreadOnly = "true".equalsIgnoreCase(request.getParam("unreadOnly"));
    var limit = parseLimit(request.getParam("limit"));
    return notifications.listForUser(userId, unreadOnly, limit).thenApply(HeraldApiHandlers::bytes);
  }

  /** {@code PUT /notifications?id=<uuid>} — đánh dấu đã đọc, chỉ tác dụng lên noti của CHÍNH mình. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "notifications", type = Type.HTTP)})
  public CompletionStage<byte[]> markRead(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID id;
    try {
      id = UUID.fromString(request.getParam("id"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HeraldErrorKeys.VALIDATION, "missing/invalid id");
    }
    return notifications.markRead(id, userId).thenApply(unused -> bytes(new JsonObject().put("id", id.toString()).put("read", true)));
  }

  /** {@code PUT /push-tokens} (body {@code {"token": "..."}}) — đăng ký/làm mới token push FCM của thiết bị hiện tại cho user đang đăng nhập. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "push-tokens", type = Type.HTTP)})
  public CompletionStage<byte[]> registerPushToken(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var token = new JsonObject(request.getBody()).getString("token");
    if (token == null || token.isBlank()) {
      throw new LegoBusinessException(HeraldErrorKeys.VALIDATION, "missing token");
    }
    return pushTokens.register(userId, token).thenApply(unused -> bytes(new JsonObject().put("registered", true)));
  }

  /** {@code DELETE /push-tokens?token=<token>} — huỷ đăng ký (vd lúc logout), không cần token đó thuộc đúng user gọi (1 token chỉ thuộc 1 thiết bị, xoá là an toàn). */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.DELETE, endpoint = "push-tokens", type = Type.HTTP)})
  public CompletionStage<byte[]> unregisterPushToken(IRequest request) {
    requireAuthenticatedUserId(request);
    var token = request.getParam("token");
    if (token == null || token.isBlank()) {
      throw new LegoBusinessException(HeraldErrorKeys.VALIDATION, "missing token");
    }
    return pushTokens.unregister(token).thenApply(unused -> bytes(new JsonObject().put("unregistered", true)));
  }

  /**
   * {@code GET /presence?userIds=a,b,c} (bắt buộc {@code Authorization: Bearer <token>}) — snapshot
   * online/offline HIỆN TẠI của danh sách userId cho trước, dùng lúc mới mở app/mở danh sách user
   * (WS chỉ báo lúc THAY ĐỔI qua frame {@code PRESENCE}, không tự biết trạng thái ban đầu). id
   * không hợp lệ/không tồn tại thì coi như offline (không ném lỗi, tránh 1 id rác làm hỏng cả request).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "presence", type = Type.HTTP)})
  public CompletionStage<byte[]> getPresence(IRequest request) {
    requireAuthenticatedUserId(request);
    var raw = request.getParam("userIds");
    if (raw == null || raw.isBlank()) {
      throw new LegoBusinessException(HeraldErrorKeys.VALIDATION, "missing userIds");
    }
    var ids = raw.split(",");
    if (ids.length > MAX_PRESENCE_USER_IDS) {
      throw new LegoBusinessException(HeraldErrorKeys.VALIDATION, "too many userIds (max " + MAX_PRESENCE_USER_IDS + ")");
    }
    var result = new JsonArray();
    for (var rawId : ids) {
      var userId = UUIDUtils.parseOrDefault(rawId.trim());
      if (userId == null) {
        continue;
      }
      result.add(new JsonObject().put("userId", userId.toString()).put("online", presence.isOnline(userId)));
    }
    return java.util.concurrent.CompletableFuture.completedStage(bytes(result));
  }

  /** Đọc + verify header {@code Authorization: Bearer <token>} -- ném 401 cho mọi lý do thất bại (thiếu header, token sai/hết hạn). */
  private UUID requireAuthenticatedUserId(IRequest request) {
    var header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      throw new LegoBusinessException(HeraldErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
    try {
      var decoded = jwtHelper.decode(header.substring(BEARER_PREFIX.length()).strip());
      var userId = decoded.getUUID("userId");
      if (userId == null) {
        throw new LegoBusinessException(HeraldErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
      }
      return userId;
    } catch (NdlTokenException e) {
      throw new LegoBusinessException(HeraldErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
  }

  private static byte[] bytes(JsonObject body) {
    return body.encode().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(JsonArray body) {
    return body.encode().getBytes(StandardCharsets.UTF_8);
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
}
