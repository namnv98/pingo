package com.pingo.chat.domain.history;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Lịch sử tin nhắn — lưu bền trong Postgres (bảng {@code messages}, {@code users}), theo pattern của
 * {@code ConversationMembershipRegistry} (qua {@link JdbcConnectionSupplier}, xem javadoc của lớp đó
 * để biết vì sao dùng placeholder {@code ?} thay vì {@code $1/$2}). Trước đây không có persistence
 * cho tin nhắn — chỉ tin "đang bay" được deliver real-time.
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
                + "VALUES (?, ?, ?, ?, to_timestamp(? / 1000.0))")
        .execute(Tuple.of(id, conversationId, fromUserId, Json.encode(body), tsEpochMillis))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Xoá TOÀN BỘ tin nhắn của 1 conversationId — xem {@code HallApiHandlers#deleteConversation}. */
  public CompletionStage<Void> deleteForConversation(UUID conversationId) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM messages WHERE conversation_id = ?")
        .execute(Tuple.of(conversationId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /**
   * Ghi "userId đã THỰC SỰ xem messageId" — nguồn thật cho dấu "đã xem" (✓✓), sống lâu dài qua
   * reload (khác {@code FrameType.SEEN} qua WS, chỉ là tín hiệu tạm thời để cập nhật UI ngay lúc
   * đó). Gọi từ {@code ChatSessionManager#handleSeen} bên colony, best-effort — không chặn fan-out
   * SEEN. {@code ON CONFLICT DO NOTHING}: 1 người chỉ cần 1 dòng "đã xem" cho 1 tin, đọc lại/gửi
   * SEEN nhiều lần (vd 2 tab) không tạo dòng trùng.
   *
   * <p>Đồng thời tiến "con trỏ đã đọc" ({@code conversation_reads}, xem {@link #getReadCursor}) —
   * CHỈ tiến về phía trước ({@code WHERE EXCLUDED.last_read_ts > conversation_reads.last_read_ts}):
   * xem lại 1 tin CŨ hơn con trỏ hiện tại (vd cuộn lên xem lại lịch sử) không được kéo lùi vị trí
   * "đã đọc tới đâu" — client dùng con trỏ này để quyết định cuộn tới đâu lúc mở lại conversation
   * (xem demo.html loadHistory), lùi lại sẽ khiến lần mở SAU hiện sai chỗ.
   */
  public CompletionStage<Void> markRead(UUID messageId, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO message_reads (message_id, user_id) VALUES (?, ?) ON CONFLICT (message_id, user_id) DO NOTHING")
        .execute(Tuple.of(messageId, userId))
        .toCompletionStage()
        .thenApply(unused -> null))
        .thenCompose(unused -> supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO conversation_reads (conversation_id, user_id, last_read_message_id, last_read_ts) "
                    + "SELECT m.conversation_id, ?, m.id, (extract(epoch from m.created_at) * 1000)::bigint FROM messages m WHERE m.id = ? "
                    + "ON CONFLICT (conversation_id, user_id) DO UPDATE SET last_read_message_id = EXCLUDED.last_read_message_id, last_read_ts = EXCLUDED.last_read_ts "
                    + "WHERE EXCLUDED.last_read_ts > conversation_reads.last_read_ts")
            .execute(Tuple.of(userId, messageId))
            .toCompletionStage()
            .thenApply(unused2 -> null)));
  }

  /**
   * Vị trí "đã đọc tới đâu" của {@code userId} trong {@code conversationId} — dùng lúc mở lại 1
   * conversation để cuộn tới ĐÚNG chỗ lần trước dừng lại (xem demo.html loadHistory), thay vì luôn
   * nhảy tới tin mới nhất. Trả {@code null} nếu chưa từng đọc tin nào (conversation mới/lần đầu mở
   * — client tự hiểu là nên cuộn xuống cuối như trước đây).
   */
  public CompletionStage<JsonObject> getReadCursor(UUID conversationId, UUID userId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            "SELECT last_read_message_id, last_read_ts FROM conversation_reads WHERE conversation_id = ? AND user_id = ?")
        .execute(Tuple.of(conversationId, userId))
        .toCompletionStage()
        .thenCompose(rows -> {
          var it = rows.iterator();
          if (!it.hasNext()) {
            return java.util.concurrent.CompletableFuture.completedStage(null);
          }
          var row = it.next();
          var lastReadMessageId = row.getUUID("last_read_message_id").toString();
          var lastReadTs = row.getLong("last_read_ts");
          // Đếm CHÍNH XÁC bằng SQL (COUNT, không phải đếm số dòng client đã lazy-load được) -- client
          // (demo.html) chỉ tải dần từng trang nhỏ (lazy load), nếu tự đếm bằng số phần tử đã tải sẽ
          // luôn bị chặn ở đúng cỡ 1 trang dù thực tế còn nhiều hơn (đã gặp thật: "sao con số hiển thị
          // trên chấm max là 30 à??"). Loại trừ from_user_id = userId (tin CỦA CHÍNH mình không bao
          // giờ "chưa đọc" với chính mình -- vô hại vì markRead đã tự advance con trỏ qua tin mình gửi
          // ngay lúc gửi, xem ChatSessionManager#persistMessage, nhưng lọc rõ ràng cho dễ đọc/chắc
          // chắn) và deleted_at (tin đã xoá thì không còn gì để "đọc").
          return supplier.executeReadOnly(conn2 -> conn2.preparedQuery(
                  "SELECT COUNT(*) AS c FROM messages "
                      + "WHERE conversation_id = ? AND created_at > to_timestamp(? / 1000.0) "
                      + "AND from_user_id != ? AND deleted_at IS NULL")
              .execute(Tuple.of(conversationId, lastReadTs, userId))
              .toCompletionStage()
              .thenApply(countRows -> new JsonObject()
                  .put("lastReadMessageId", lastReadMessageId)
                  .put("lastReadTs", lastReadTs)
                  .put("unreadCount", countRows.iterator().next().getLong("c"))));
        }));
  }

  /**
   * Đặt/đổi reaction (emoji) của {@code userId} lên {@code messageId} — kiểu Facebook: 1 người chỉ
   * có 1 reaction cho 1 tin tại 1 thời điểm, chọn emoji khác THAY THẾ (không cộng dồn), xem UNIQUE
   * {@code PRIMARY KEY (message_id, user_id)} của bảng {@code message_reactions}.
   */
  public CompletionStage<Void> setReaction(UUID messageId, UUID userId, String emoji) {
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO message_reactions (message_id, user_id, emoji) VALUES (?, ?, ?) "
                + "ON CONFLICT (message_id, user_id) DO UPDATE SET emoji = EXCLUDED.emoji, created_at = now()")
        .execute(Tuple.of(messageId, userId, emoji))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /** Bỏ reaction (bấm lại đúng emoji đang chọn để huỷ, giống Facebook). */
  public CompletionStage<Void> removeReaction(UUID messageId, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM message_reactions WHERE message_id = ? AND user_id = ?")
        .execute(Tuple.of(messageId, userId))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /**
   * Xoá MỀM (không xoá dòng, chỉ đánh dấu {@code deleted_at}) -- CHỈ khi đúng {@code from_user_id}
   * gốc VÀ chưa xoá trước đó (WHERE lọc cả 2), trả về true nếu THỰC SỰ vừa xoá (rowCount &gt; 0) để
   * caller biết có nên fan-out DELETE hay không (xem {@code ChatSessionManager#handleDelete}) --
   * không thì ai cũng "xoá" được tin của người khác chỉ bằng cách đoán đúng id.
   */
  public CompletionStage<Boolean> markDeleted(UUID messageId, UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "UPDATE messages SET deleted_at = now() WHERE id = ? AND from_user_id = ? AND deleted_at IS NULL")
        .execute(Tuple.of(messageId, userId))
        .toCompletionStage()
        .thenApply(rows -> rows.rowCount() > 0));
  }

  /**
   * Lấy lịch sử tin nhắn của 1 conversation, mới nhất trước — dùng cho phân trang kiểu "load thêm
   * tin cũ hơn": {@code beforeEpochMillis} là mốc thời gian (loại trừ), null nghĩa là trang đầu
   * tiên (tính từ "bây giờ"). Kèm {@code seen} (đã có ai KHÁC người gửi đọc chưa, xem
   * {@link #markRead}) và {@code reactions} (danh sách {@code {userId, emoji}}, xem
   * {@link #setReaction}) — 2 subquery {@code array_agg} riêng cho emoji/userId, PHẢI cùng 1
   * {@code ORDER BY user_id} ở cả 2 để Postgres đảm bảo 2 mảng tương ứng đúng vị trí (không có cách
   * nào gom {@code (emoji, userId)} thành 1 mảng "tuple" qua vertx-jdbc-client mà không lệch kiểu,
   * xem javadoc {@link com.pingo.chat.domain.membership.ConversationMembershipRegistry} về pitfall
   * SQL array qua JDBC).
   */
  public CompletionStage<JsonArray> listMessages(UUID conversationId, int limit, Long beforeEpochMillis) {
    var before = beforeEpochMillis == null ? System.currentTimeMillis() : beforeEpochMillis;
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            SELECT_COLUMNS + "WHERE m.conversation_id = ? AND m.created_at < to_timestamp(? / 1000.0) "
                + "ORDER BY m.created_at DESC LIMIT ?")
        .execute(Tuple.of(conversationId, before, limit))
        .toCompletionStage()
        .thenApply(MessageHistoryRegistry::toMessagesArray));
  }

  /**
   * Lấy tin nhắn MỚI HƠN {@code afterEpochMillis} (loại trừ), tăng dần theo thời gian, tối đa
   * {@code limit} dòng — dùng để nạp "đoạn tin chưa đọc" từ ngay sau con trỏ đã đọc
   * ({@link #getReadCursor}) xuống tới hiện tại, khi mở lại 1 conversation (xem demo.html
   * loadHistory) — khác {@link #listMessages} (nạp NGƯỢC từ 1 mốc trở về trước, dùng cho "cuộn lên
   * xem tin cũ hơn"). Cùng bộ cột trả về, thứ tự khác (ASC thay vì DESC) nên KHÔNG cần đảo lại ở
   * client như listMessages.
   */
  public CompletionStage<JsonArray> listMessagesAfter(UUID conversationId, long afterEpochMillis, int limit) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            SELECT_COLUMNS + "WHERE m.conversation_id = ? AND m.created_at > to_timestamp(? / 1000.0) "
                + "ORDER BY m.created_at ASC LIMIT ?")
        .execute(Tuple.of(conversationId, afterEpochMillis, limit))
        .toCompletionStage()
        .thenApply(MessageHistoryRegistry::toMessagesArray));
  }

  private static final String SELECT_COLUMNS =
      "SELECT m.id, m.conversation_id, m.from_user_id, m.body, m.deleted_at, "
          + "(extract(epoch from m.created_at) * 1000)::bigint AS ts, "
          + "EXISTS (SELECT 1 FROM message_reads mr WHERE mr.message_id = m.id AND mr.user_id != m.from_user_id) AS seen, "
          + "(SELECT array_agg(mrx.emoji ORDER BY mrx.user_id) FROM message_reactions mrx WHERE mrx.message_id = m.id) AS reaction_emojis, "
          + "(SELECT array_agg(mrx.user_id::text ORDER BY mrx.user_id) FROM message_reactions mrx WHERE mrx.message_id = m.id) AS reaction_user_ids "
          + "FROM messages m ";

  private static JsonArray toMessagesArray(io.vertx.sqlclient.RowSet<io.vertx.sqlclient.Row> rows) {
    var result = new JsonArray();
    for (var row : rows) {
      // Tin đã xoá (mềm): KHÔNG trả body/reactions gốc cho bất kỳ ai (kể cả chính người gửi) --
      // placeholder "đã bị xoá" hiển thị ở client dựa vào cờ "deleted" này, xem demo.html
      // renderMessageContent(). Dòng vẫn còn trong DB (deleted_at) cho mục đích audit/khôi phục sau
      // này, chỉ ẩn NỘI DUNG khỏi API đọc.
      var deleted = row.getValue("deleted_at") != null;
      var bodyText = row.getString("body");
      result.add(
          new JsonObject()
              .put("id", row.getUUID("id").toString())
              .put("conversationId", row.getUUID("conversation_id").toString())
              .put("fromUserId", row.getUUID("from_user_id").toString())
              .put("body", deleted ? null : (bodyText == null ? null : Json.decodeValue(bodyText)))
              .put("ts", row.getLong("ts"))
              .put("seen", row.getBoolean("seen"))
              .put("deleted", deleted)
              .put("reactions", deleted ? new JsonArray() : toReactionsArray(row)));
    }
    return result;
  }

  private static JsonArray toReactionsArray(io.vertx.sqlclient.Row row) {
    var rawEmojis = (Object[]) row.getValue("reaction_emojis");
    var rawUserIds = (Object[]) row.getValue("reaction_user_ids");
    var result = new JsonArray();
    if (rawEmojis == null || rawUserIds == null) {
      return result;
    }
    var emojis = Arrays.stream(rawEmojis).map(String::valueOf).toList();
    var userIds = Arrays.stream(rawUserIds).map(String::valueOf).toList();
    for (var i = 0; i < emojis.size() && i < userIds.size(); i++) {
      result.add(new JsonObject().put("userId", userIds.get(i)).put("emoji", emojis.get(i)));
    }
    return result;
  }
}
