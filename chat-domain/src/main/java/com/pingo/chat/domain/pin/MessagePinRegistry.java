package com.pingo.chat.domain.pin;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Ghim tin nhắn -- 2 chế độ, 2 bảng riêng (xem javadoc {@code message_pins_shared}/
 * {@code message_pins_private} trong {@code postgres/helm/templates/configmap.yaml}):
 *
 * <ul>
 *   <li><b>shared</b> (chung): mọi thành viên conversation đều thấy, ai cũng ghim/bỏ ghim được
 *       (cùng quyền với reaction -- repo không có khái niệm admin/role), fan-out qua WS (xem
 *       {@code ChatSessionManager#handlePin}).
 *   <li><b>private</b> (riêng): chỉ đúng {@code userId} đã ghim thấy được, KHÔNG fan-out -- các
 *       tab/thiết bị khác của chính người đó tự đồng bộ lại qua {@link #listPins} lần sau mở tab
 *       Pins.
 * </ul>
 */
@RequiredArgsConstructor
public class MessagePinRegistry {

  private final JdbcConnectionSupplier supplier;

  /** Ghim chung -- {@code ON CONFLICT DO NOTHING}: tin đã ghim rồi thì giữ nguyên {@code pinned_by}/{@code pinned_at} gốc. */
  public CompletionStage<Void> pinShared(UUID conversationId, UUID messageId, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO message_pins_shared (message_id, conversation_id, pinned_by) VALUES (?, ?, ?) "
                + "ON CONFLICT (message_id) DO NOTHING")
        .execute(Tuple.of(messageId, conversationId, userId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  public CompletionStage<Void> unpinShared(UUID messageId) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM message_pins_shared WHERE message_id = ?")
        .execute(Tuple.of(messageId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Ghim riêng -- {@code ON CONFLICT DO NOTHING}: 1 user chỉ cần 1 dòng ghim cho 1 tin. */
  public CompletionStage<Void> pinPrivate(UUID conversationId, UUID messageId, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO message_pins_private (message_id, user_id, conversation_id) VALUES (?, ?, ?) "
                + "ON CONFLICT (message_id, user_id) DO NOTHING")
        .execute(Tuple.of(messageId, userId, conversationId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  public CompletionStage<Void> unpinPrivate(UUID messageId, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "DELETE FROM message_pins_private WHERE message_id = ? AND user_id = ?")
        .execute(Tuple.of(messageId, userId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /**
   * Toàn bộ tin đã ghim mà {@code userId} XEM ĐƯỢC trong {@code conversationId} -- ghim chung của cả
   * conversation UNION ghim riêng của ĐÚNG {@code userId} (không lộ ghim riêng của người khác), mới
   * ghim trước. Loại tin đã xoá mềm bằng JOIN {@code messages WHERE deleted_at IS NULL} -- không cần
   * dọn dòng ghim khi xoá tin (cùng tinh thần {@code listMessages}/{@code getReadCursor}).
   */
  public CompletionStage<JsonArray> listPins(UUID conversationId, UUID userId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            "SELECT p.message_id, p.pinned_by, "
                + "(extract(epoch from p.pinned_at) * 1000)::bigint AS pinned_ts, 'shared' AS scope, "
                + "m.from_user_id, m.body, (extract(epoch from m.created_at) * 1000)::bigint AS ts "
                + "FROM message_pins_shared p JOIN messages m ON m.id = p.message_id "
                + "WHERE p.conversation_id = ? AND m.deleted_at IS NULL "
                + "UNION ALL "
                + "SELECT p.message_id, p.user_id AS pinned_by, "
                + "(extract(epoch from p.pinned_at) * 1000)::bigint AS pinned_ts, 'private' AS scope, "
                + "m.from_user_id, m.body, (extract(epoch from m.created_at) * 1000)::bigint AS ts "
                + "FROM message_pins_private p JOIN messages m ON m.id = p.message_id "
                + "WHERE p.conversation_id = ? AND p.user_id = ? AND m.deleted_at IS NULL "
                + "ORDER BY pinned_ts DESC")
        .execute(Tuple.of(conversationId, conversationId, userId))
        .toCompletionStage()
        .thenApply(rows -> {
          var result = new JsonArray();
          for (var row : rows) {
            var bodyText = row.getString("body");
            result.add(new JsonObject()
                .put("messageId", row.getUUID("message_id").toString())
                .put("scope", row.getString("scope"))
                .put("pinnedBy", row.getUUID("pinned_by").toString())
                .put("pinnedAt", row.getLong("pinned_ts"))
                .put("fromUserId", row.getUUID("from_user_id").toString())
                .put("body", bodyText == null ? null : Json.decodeValue(bodyText))
                .put("ts", row.getLong("ts")));
          }
          return result;
        }));
  }
}
