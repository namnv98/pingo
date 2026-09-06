package com.pingo.chat.domain.notification;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Noti "bạn có tin nhắn mới" cho user đang OFFLINE HOÀN TOÀN (không có session WebSocket nào sống
 * ở bất kỳ pod harbor nào lúc tin đến — xem {@code com.pingo.chat.domain.presence.PresenceRegistry})
 * — server hiện CHƯA có hạ tầng push thật (FCM/APNs/email), nên đây là nơi LƯU LẠI (queue) để
 * người dùng thấy khi quay lại ({@code GET /notifications} bên herald), đồng thời là điểm cắm mốc
 * cho push thật sau này (chỉ cần đổi nơi gọi {@link #create} từ "lưu DB" sang "gọi FCM/APNs/email
 * thật", schema/API không đổi). Cùng pattern qua {@link JdbcConnectionSupplier} với
 * {@code ConversationMembershipRegistry}/{@code MessageHistoryRegistry}.
 */
@RequiredArgsConstructor
public class NotificationRegistry {

  private final JdbcConnectionSupplier supplier;

  public CompletionStage<Void> create(
      UUID id, UUID userId, UUID conversationId, UUID fromUserId, String bodyPreview, long tsEpochMillis) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO notifications (id, user_id, conversation_id, from_user_id, body_preview, created_at) "
                + "VALUES (?, ?, ?, ?, ?, to_timestamp(? / 1000.0))")
        .execute(Tuple.from(List.of(id, userId, conversationId, fromUserId, bodyPreview == null ? "" : bodyPreview, tsEpochMillis)))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Noti của CHÍNH mình, mới nhất trước — {@code unreadOnly} lọc còn {@code read_at IS NULL}. */
  public CompletionStage<JsonArray> listForUser(UUID userId, boolean unreadOnly, int limit) {
    var sql =
        "SELECT id, conversation_id, from_user_id, body_preview, "
            + "(extract(epoch from created_at) * 1000)::bigint AS ts, (read_at IS NOT NULL) AS read "
            + "FROM notifications WHERE user_id = ? "
            + (unreadOnly ? "AND read_at IS NULL " : "")
            + "ORDER BY created_at DESC LIMIT ?";
    return supplier.executeReadOnly(conn -> conn.preparedQuery(sql)
        .execute(Tuple.of(userId, limit))
        .toCompletionStage()
        .thenApply(
            rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                result.add(
                    new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("conversationId", row.getUUID("conversation_id").toString())
                        .put("fromUserId", row.getUUID("from_user_id").toString())
                        .put("bodyPreview", row.getString("body_preview"))
                        .put("ts", row.getLong("ts"))
                        .put("read", row.getBoolean("read")));
              }
              return result;
            }));
  }

  /** Xoá TOÀN BỘ notification liên quan tới 1 conversationId (của mọi user) — xem {@code HallApiHandlers#deleteConversation}. */
  public CompletionStage<Void> deleteForConversation(UUID conversationId) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM notifications WHERE conversation_id = ?")
        .execute(Tuple.of(conversationId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Đánh dấu đã đọc — chỉ tác dụng nếu {@code id} đúng là noti của CHÍNH {@code userId} (không cho đọc hộ người khác). */
  public CompletionStage<Void> markRead(UUID id, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "UPDATE notifications SET read_at = now() WHERE id = ? AND user_id = ? AND read_at IS NULL")
        .execute(Tuple.of(id, userId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }
}
