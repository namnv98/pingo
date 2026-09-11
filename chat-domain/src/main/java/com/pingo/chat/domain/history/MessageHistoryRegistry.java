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
    // search_text = phan chu THUAN cua tin (body.message) -- cung 1 cach trich nhu
    // ChatSessionManager#extractMessageLinks, chi set 1 LAN o day (enrichLinkPreview sau nay chi them
    // body.preview qua updateBodyJson, khong bao gio doi body.message nen khong can dong bo lai).
    // search_vector (generated column, xem schema) tu tinh lai tu cot nay, dung cho searchMessages().
    var searchText = body instanceof JsonObject j ? j.getString("message") : null;
    return supplier.execute(conn -> conn.preparedQuery(
            "INSERT INTO messages (id, conversation_id, from_user_id, body, created_at, search_text) "
                + "VALUES (?, ?, ?, ?, to_timestamp(? / 1000.0), ?)")
        .execute(Tuple.of(id, conversationId, fromUserId, Json.encode(body), tsEpochMillis, searchText))
        .toCompletionStage()
        .thenApply(unused -> null));
  }

  /**
   * Tim tin nhan theo noi dung (full-text search qua search_vector, xem schema) -- {@code
   * conversationId} null = tim TOAN CUC xuyen moi conversation cua {@code userId} (xem {@code
   * HallApiHandlers}'s {@code GET /messages/search}). Luon gioi han qua {@code conversation_members}
   * (giong {@code ConversationMembershipRegistry#listConversationsForUser}) du la tim trong 1
   * conversation hay toan cuc -- khong bao gio cho tim vao 1 conversation khong phai thanh vien, ke
   * ca khi client tu truyen conversationId tay.
   *
   * <p>Dung text search configuration {@code pingo_search} (xem schema) thay vi {@code simple} -- co
   * gan dictionary {@code unaccent} nen tim KHONG can go dau tieng Viet ("chao" van ra "chào"), van
   * giu nguyen van ban GOC co dau de hien thi snippet (unaccent chi anh huong luc so khop lexeme, xem
   * javadoc schema.sql).
   *
   * <p>{@code snippet} dung {@code ts_headline} voi StartSel/StopSel la 2 ky tu dieu khien hiem gap
   * (U+0001/U+0002) THAY VI the {@code <b>}/{@code </b>} mac dinh -- BAT BUOC, vi ts_headline KHONG
   * tu escape noi dung tin nhan goc: client phai escape HTML AN TOAN roi moi thay marker do bang
   * {@code <mark>} that (xem javadoc phia frontend, ensureSearchModal/renderSearchResults) -- neu de
   * marker la {@code <b>} that va chen thang vao innerHTML se la XSS luu tru that su (1 tin nhan cu
   * chua chu {@code <script>} dang text se thuc thi).
   */
  public CompletionStage<JsonArray> searchMessages(UUID userId, UUID conversationId, String queryText, int limit) {
    var sql = new StringBuilder(
        "SELECT id, conversation_id, from_user_id, body, "
            + "(extract(epoch from created_at) * 1000)::bigint AS ts, "
            + "ts_headline('pingo_search', search_text, websearch_to_tsquery('pingo_search', ?), ?) AS snippet "
            + "FROM messages "
            + "WHERE deleted_at IS NULL "
            + "AND search_vector @@ websearch_to_tsquery('pingo_search', ?) "
            + "AND conversation_id IN (SELECT conversation_id FROM conversation_members WHERE user_id = ?) ");
    if (conversationId != null) {
      sql.append("AND conversation_id = ? ");
    }
    sql.append("ORDER BY created_at DESC LIMIT ?");
    var headlineOptions = "StartSel=\u0001,StopSel=\u0002,MaxFragments=1,MaxWords=20,MinWords=5";
    var params = new java.util.ArrayList<Object>();
    params.add(queryText);
    params.add(headlineOptions);
    params.add(queryText);
    params.add(userId);
    if (conversationId != null) {
      params.add(conversationId);
    }
    params.add(limit);
    return supplier.executeReadOnly(conn -> conn.preparedQuery(sql.toString())
        .execute(Tuple.from(params))
        .toCompletionStage()
        .thenApply(rows -> {
          var result = new JsonArray();
          for (var row : rows) {
            var bodyText = row.getString("body");
            result.add(
                new JsonObject()
                    .put("id", row.getUUID("id").toString())
                    .put("conversationId", row.getUUID("conversation_id").toString())
                    .put("fromUserId", row.getUUID("from_user_id").toString())
                    .put("body", bodyText == null ? null : Json.decodeValue(bodyText))
                    .put("ts", row.getLong("ts"))
                    .put("snippet", row.getString("snippet")));
          }
          return result;
        }));
  }

  /**
   * Ghi đè {@code messages.body} của 1 tin ĐÃ lưu -- dùng cho link preview: colony lưu tin ngay để
   * ACK/fan-out không bị chậm, rồi SAU đó mới resolve og: và vá {@code body.preview} vào (giống Slack
   * {@code chat.unfurlLink} chạy sau {@code chat.postMessage}, chỉ khác là pingo không cần broadcast
   * {@code message_changed} -- client tự fallback vẽ preview lúc render nếu body chưa có, xem
   * demo.html {@code renderLinkPreview}). Gọi SAU khi {@link #saveMessage} đã xong nên không có race
   * INSERT/UPDATE; {@code rowCount() == 0} nghĩa là tin không tồn tại (đã bị xoá conversation), bỏ qua.
   */
  public CompletionStage<Integer> updateBodyJson(UUID messageId, String bodyJson) {
    return supplier.execute(conn -> conn.preparedQuery("UPDATE messages SET body = ? WHERE id = ?")
        .execute(Tuple.of(bodyJson, messageId))
        .toCompletionStage()
        .thenApply(io.vertx.sqlclient.RowSet::rowCount));
  }

  /**
   * XOÁ MỀM toàn bộ tin nhắn của 1 conversationId (đặt {@code deleted_at}, tái dùng đúng cơ chế soft-
   * delete từng tin lẻ đã có sẵn — xem {@link #markDeleted}) — KHÔNG {@code DELETE} thật nữa, xem
   * {@code HallApiHandlers#deleteConversation}. Trước đây hard-delete thẳng, nhưng chỉ riêng bảng này
   * — 6 bảng khác cũng tham chiếu conversationId/messageId của conversation đó ({@code message_reads},
   * {@code message_reactions}, {@code message_pins_shared}, {@code message_pins_private}, {@code
   * message_links}, {@code files}) và {@code notifications} KHÔNG hề được dọn theo (loose-schema,
   * không FK/cascade) — thành rác mồ côi vĩnh viễn dù có xoá {@code messages} hard hay không. Đổi
   * sang xoá mềm ở đây để ít nhất tin nhắn (dữ liệu nhạy cảm nhất) được ẩn khỏi API NGAY (danh sách
   * thành viên — {@code conversation_members} — vẫn bị xoá THẬT ngay lúc xoá conversation nên
   * conversation biến mất khỏi mọi nơi truy vấn theo membership) trong lúc chờ 1 job dọn dẹp định kỳ
   * riêng (chưa viết) quét sạch — bằng hard-DELETE thật — cả 7 bảng nói trên cho những conversationId
   * không còn dòng nào trong {@code conversation_members} nữa.
   */
  public CompletionStage<Void> deleteForConversation(UUID conversationId) {
    return supplier.execute(conn -> conn.preparedQuery(
            "UPDATE messages SET deleted_at = now() WHERE conversation_id = ? AND deleted_at IS NULL")
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
   * {@code from_user_id} của 1 tin -- dùng để biết "tin vừa được react/trả lời là CỦA AI" (xem
   * {@code ChatSessionManager#handleReaction}, tạo noti kiểu "reaction" cho đúng chủ tin). Optional
   * rỗng nếu id không tồn tại (tin đã bị xoá cứng -- không xảy ra trong app này, hoặc id sai).
   */
  /**
   * {@code fromUserId} CÙNG {@code createdAt} (epoch millis) của 1 tin -- {@code createdAt} LÀ tin
   * đó thật sự được gửi lúc nào, KHÁC hẳn "bây giờ" (lúc gọi hàm này, vd lúc ai đó vừa react). Bug
   * thật đã gặp: dùng nhầm "bây giờ" làm {@code ts} cho noti kiểu "reaction" (xem
   * {@code ChatSessionManager#handleReaction}) -- client dùng {@code ts} đó làm mốc tìm quanh
   * (seek, xem {@code jumpToMessage}) khi tin CHƯA có sẵn trong khung chat, tìm sai hẳn quanh "bây
   * giờ" thay vì quanh lúc tin gốc (có thể rất cũ) thật sự được gửi -- luôn báo "Không tìm thấy tin
   * gốc" dù tin vẫn còn nguyên, chỉ đơn giản là tìm sai chỗ.
   */
  public record MessageOwner(UUID fromUserId, long createdAtEpochMillis) {}

  public CompletionStage<java.util.Optional<MessageOwner>> getOwner(UUID messageId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            "SELECT from_user_id, (extract(epoch from created_at) * 1000)::bigint AS ts FROM messages WHERE id = ?")
        .execute(Tuple.of(messageId))
        .toCompletionStage()
        .thenApply(rows -> {
          var it = rows.iterator();
          if (!it.hasNext()) {
            return java.util.Optional.<MessageOwner>empty();
          }
          var row = it.next();
          return java.util.Optional.of(new MessageOwner(row.getUUID("from_user_id"), row.getLong("ts")));
        }));
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
