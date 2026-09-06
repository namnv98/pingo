package com.pingo.chat.domain.notification;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Token FCM (Firebase Cloud Messaging) của từng thiết bị/trình duyệt — xem {@code PushService} bên
 * herald (chỉ herald gọi push thật). {@code token} là PRIMARY KEY (không phải {@code userId}): 1
 * token chỉ thuộc về 1 thiết bị/lần cài đặt tại 1 thời điểm — {@link #register} UPSERT để token
 * luôn trỏ về đúng user hiện tại (vd đăng xuất rồi đăng nhập user khác trên cùng máy).
 */
@RequiredArgsConstructor
public class PushTokenRegistry {

  private final JdbcConnectionSupplier supplier;

  public CompletionStage<Void> register(UUID userId, String token) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO push_tokens (token, user_id, created_at, updated_at) VALUES (?, ?, now(), now()) "
                + "ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, updated_at = now()")
        .execute(Tuple.of(token, userId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Gọi lúc client chủ động logout, hoặc khi Firebase báo token đã UNREGISTERED/hết hạn (xem {@code PushService}). */
  public CompletionStage<Void> unregister(String token) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM push_tokens WHERE token = ?")
        .execute(Tuple.of(token))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  public CompletionStage<List<String>> tokensForUser(UUID userId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT token FROM push_tokens WHERE user_id = ?")
        .execute(Tuple.of(userId))
        .toCompletionStage()
        .thenApply(
            rows -> {
              var tokens = new ArrayList<String>();
              for (var row : rows) {
                tokens.add(row.getString("token"));
              }
              return tokens;
            }));
  }
}
