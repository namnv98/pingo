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
 * Noti của người dùng — 2 nguồn ghi khác nhau, CÙNG 1 bảng/API đọc:
 *
 * <ul>
 *   <li><b>{@code type = "message"}</b>: "bạn có tin nhắn mới" cho user đang OFFLINE HOÀN TOÀN
 *       (không có session WebSocket nào sống ở bất kỳ pod harbor nào lúc tin đến — xem {@code
 *       com.pingo.chat.domain.presence.PresenceRegistry}) — ghi CÓ ĐIỀU KIỆN qua herald {@code
 *       NotificationConsumer}. Server hiện CHƯA có hạ tầng push thật (FCM/APNs/email), nên đây là
 *       nơi LƯU LẠI (queue) để người dùng thấy khi quay lại, đồng thời là điểm cắm mốc cho push
 *       thật sau này (chỉ cần đổi nơi gọi {@link #create} từ "lưu DB" sang "gọi FCM/APNs/email
 *       thật", schema/API không đổi).
 *   <li><b>{@code type = "mention"/"reply"/"reaction"}</b>: ai đó @nhắc tên/trả lời/thả cảm xúc vào
 *       tin CỦA MÌNH — ghi THẲNG từ colony {@code ChatSessionManager} lúc persist tin/reaction,
 *       LUÔN LƯU (không điều kiện online/offline, giống feed hoạt động Teams/Slack: vẫn hiện trong
 *       chuông dù bạn đã thấy tin đó trực tiếp trong khung chat).
 * </ul>
 *
 * {@code GET /notifications} bên herald trả về CẢ 2 loại lẫn lộn, mới nhất trước — client tự phân
 * biệt qua trường {@code type} để hiện icon/câu chữ khác nhau (xem renderNotifMenu trong
 * history-ws.js). Cùng pattern qua {@link JdbcConnectionSupplier} với {@code
 * ConversationMembershipRegistry}/{@code MessageHistoryRegistry}.
 */
@RequiredArgsConstructor
public class NotificationRegistry {

  private final JdbcConnectionSupplier supplier;

  /**
   * {@code tsEpochMillis}: lúc SỰ KIỆN xảy ra (tin tới/react/trả lời/nhắc tên) -- ghi vào {@code
   * created_at}, quyết định thứ tự "mới nhất trước" (xem {@link #listForUser}). {@code
   * messageTsEpochMillis}: giờ tin GỐC (được nhắc/được react/được trả lời) THẬT SỰ được gửi -- với
   * "reaction" có thể lệch xa {@code tsEpochMillis} (react vào 1 tin rất cũ); với "mention"/"reply"/
   * "message" thì luôn bằng nhau (tin gốc CHÍNH LÀ tin vừa gửi). Client dùng riêng giá trị này làm
   * mốc "seek" khi tin chưa có sẵn trong khung chat (xem jumpToMessage trong history-ws.js) -- lẫn
   * 2 giá trị này là bug thật đã gặp (xem javadoc {@code ChatSessionManager#createNotification}).
   */
  public CompletionStage<Void> create(
      UUID id,
      UUID userId,
      UUID conversationId,
      UUID fromUserId,
      UUID messageId,
      String type,
      String bodyPreview,
      long tsEpochMillis,
      long messageTsEpochMillis) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO notifications (id, user_id, conversation_id, from_user_id, message_id, type, body_preview, created_at, message_ts) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, to_timestamp(? / 1000.0), to_timestamp(? / 1000.0))")
        .execute(Tuple.from(List.of(
            id, userId, conversationId, fromUserId, messageId, type, bodyPreview == null ? "" : bodyPreview,
            tsEpochMillis, messageTsEpochMillis)))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Noti của CHÍNH mình, mới nhất trước — {@code unreadOnly} lọc còn {@code read_at IS NULL}. */
  public CompletionStage<JsonArray> listForUser(UUID userId, boolean unreadOnly, int limit) {
    var sql =
        "SELECT id, conversation_id, from_user_id, message_id, type, body_preview, "
            + "(extract(epoch from created_at) * 1000)::bigint AS ts, "
            + "(extract(epoch from message_ts) * 1000)::bigint AS message_ts, (read_at IS NOT NULL) AS read "
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
                var messageId = row.getUUID("message_id");
                var messageTs = (Long) row.getValue("message_ts");
                result.add(
                    new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("conversationId", row.getUUID("conversation_id").toString())
                        .put("fromUserId", row.getUUID("from_user_id").toString())
                        .put("messageId", messageId == null ? null : messageId.toString())
                        .put("type", row.getString("type"))
                        .put("bodyPreview", row.getString("body_preview"))
                        .put("ts", row.getLong("ts"))
                        .put("messageTs", messageTs)
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
