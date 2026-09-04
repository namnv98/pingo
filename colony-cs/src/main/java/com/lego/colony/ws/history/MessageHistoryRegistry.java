package com.lego.colony.ws.history;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Lịch sử tin nhắn — lưu bền trong Postgres (bảng {@code messages}, {@code users}, xem
 * postgres/helm/templates/configmap.yaml), theo đúng pattern của {@code ConversationMembershipRegistry}
 * (raw SQL qua {@code Pool} reactive, không ORM). Trước đây hoàn toàn không có persistence nào cho
 * tin nhắn (xem ARCHITECTURE.md mục 8/11) — chỉ tin "đang bay" được deliver real-time, không lưu
 * lại đâu để replay/xem lịch sử. Đây là lần đầu wire persistence thật vào cho phần này.
 */
@RequiredArgsConstructor
public class MessageHistoryRegistry {

  private final Pool pool;

  /**
   * Ghi 1 tin nhắn vào lịch sử — best-effort, KHÔNG chặn đường đi real-time (deliverLocally/
   * forwardToOwningNode/ACK ở {@code ChatSessionManager.handleMessage} không đợi write này xong).
   * Đây là log lịch sử, không phải durability guarantee cho việc delivery — cùng triết lý ưu tiên
   * độ trễ thấp đã áp dụng cho toàn bộ tầng transport này (xem ARCHITECTURE.md mục 9).
   *
   * <p>KHÔNG còn tự upsert {@code users} ở đây nữa — {@code users.username} giờ là {@code NOT
   * NULL} (bắt buộc phải có tên, xem {@code UserRegistry}), nên không còn cách nào "đăng ký" 1 id
   * mà không có tên đi kèm. {@code from_user_id} vẫn KHÔNG có FK (giữ đúng quy ước loose-schema có
   * sẵn) — nếu 1 client gửi MESSAGE mà chưa từng gọi {@code PUT /users} đặt tên (vd script test
   * qua thẳng WebSocket, bỏ qua UI), tin nhắn vẫn được lưu bình thường, chỉ là {@code from_user_id}
   * đó không khớp dòng nào trong {@code users} — chấp nhận được, UI là nơi ép buộc đặt tên trước,
   * không phải giao thức (client vẫn chưa cần "login" thật, xem ARCHITECTURE.md mục 8).
   */
  public CompletionStage<Void> saveMessage(UUID id, UUID conversationId, UUID fromUserId, Object body, long tsEpochMillis) {
    return pool.preparedQuery(
            "INSERT INTO messages (id, conversation_id, from_user_id, body, created_at) "
                + "VALUES ($1, $2, $3, $4, to_timestamp($5 / 1000.0))")
        .execute(Tuple.of(id, conversationId, fromUserId, Json.encode(body), tsEpochMillis))
        .toCompletionStage()
        .thenApply(unused -> null);
  }

  /**
   * Lấy lịch sử tin nhắn của 1 conversation, mới nhất trước — dùng cho phân trang kiểu "load thêm
   * tin cũ hơn": {@code beforeEpochMillis} là mốc thời gian (loại trừ), null nghĩa là trang đầu
   * tiên (tính từ "bây giờ").
   */
  public CompletionStage<JsonArray> listMessages(UUID conversationId, int limit, Long beforeEpochMillis) {
    var before = beforeEpochMillis == null ? System.currentTimeMillis() : beforeEpochMillis;
    return pool.preparedQuery(
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
            });
  }
}
