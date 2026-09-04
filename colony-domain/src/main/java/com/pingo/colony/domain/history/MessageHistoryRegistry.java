package com.pingo.colony.domain.history;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Lịch sử tin nhắn — lưu bền trong Postgres (bảng {@code messages}, {@code users}), theo pattern của
 * {@code ConversationMembershipRegistry} (raw SQL qua {@link JdbcConnectionSupplier}, không ORM).
 * Trước đây không có persistence cho tin nhắn — chỉ tin "đang bay" được deliver real-time.
 */
@RequiredArgsConstructor
public class MessageHistoryRegistry {

  private final JdbcConnectionSupplier supplier;

  /**
   * Ghi 1 tin nhắn vào lịch sử — best-effort, không chặn đường real-time (deliverLocally/
   * forwardToOwningNode/ACK ở {@code ChatSessionManager.handleMessage} không đợi write này).
   *
   * <p>Không upsert {@code users} ở đây — {@code users.username} giờ NOT NULL nên không thể "đăng
   * ký" 1 id thiếu tên. {@code from_user_id} vẫn không có FK (loose-schema): nếu client gửi MESSAGE
   * mà chưa từng {@code PUT /users} đặt tên, tin nhắn vẫn lưu bình thường, chỉ {@code from_user_id}
   * không khớp dòng nào trong {@code users} — chấp nhận được, UI ép buộc đặt tên trước, không phải
   * giao thức.
   */
  public CompletionStage<Void> saveMessage(UUID id, UUID conversationId, UUID fromUserId, Object body, long tsEpochMillis) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO messages (id, conversation_id, from_user_id, body, created_at) "
                + "VALUES ($1, $2, $3, $4, to_timestamp($5 / 1000.0))")
        .execute(Tuple.of(id, conversationId, fromUserId, Json.encode(body), tsEpochMillis))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /**
   * Lấy lịch sử tin nhắn của 1 conversation, mới nhất trước — dùng cho phân trang kiểu "load thêm
   * tin cũ hơn": {@code beforeEpochMillis} là mốc thời gian (loại trừ), null nghĩa là trang đầu
   * tiên (tính từ "bây giờ").
   */
  public CompletionStage<JsonArray> listMessages(UUID conversationId, int limit, Long beforeEpochMillis) {
    var before = beforeEpochMillis == null ? System.currentTimeMillis() : beforeEpochMillis;
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            "SELECT id, conversation_id, from_user_id, body, "
                + "(extract(epoch from created_at) * 1000)::bigint AS ts "
                + "FROM messages "
                + "WHERE conversation_id = $1 AND created_at < to_timestamp($2 / 1000.0) "
                + "ORDER BY created_at DESC LIMIT $3")
        .execute(Tuple.of(conversationId, before, limit))
        .toCompletionStage()
        .thenApply(
            rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                var bodyText = row.getString("body");
                result.add(
                    new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("conversationId", row.getUUID("conversation_id").toString())
                        .put("fromUserId", row.getUUID("from_user_id").toString())
                        .put("body", bodyText == null ? null : Json.decodeValue(bodyText))
                        .put("ts", row.getLong("ts")));
              }
              return result;
            }));
  }
}
