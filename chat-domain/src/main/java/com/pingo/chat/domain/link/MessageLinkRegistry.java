package com.pingo.chat.domain.link;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;

/**
 * Tab "Links" -- danh sách URL đã từng xuất hiện trong nội dung tin nhắn của 1 conversation, trích
 * sẵn ra bảng riêng {@code message_links} ngay lúc lưu tin (xem
 * {@code ChatSessionManager#persistMessage}) thay vì quét lại {@code messages.body} (TEXT chứa
 * JSON) mỗi lần mở tab.
 *
 * <p>Khác {@link com.pingo.chat.domain.preview.LinkPreviewService#soleUrl} (chỉ khớp tin CHỈ CHỨA
 * ĐÚNG 1 link, dùng cho auto-preview) -- {@link #URL_PATTERN} ở đây quét MỌI URL nằm bất kỳ đâu
 * trong câu, không neo đầu/cuối chuỗi, vì tab Links cần bắt cả link xen giữa chữ khác.
 */
@RequiredArgsConstructor
public class MessageLinkRegistry {

  private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

  private final JdbcConnectionSupplier supplier;

  /**
   * Best-effort, gọi SAU khi tin đã lưu xong -- {@code messageText} null/blank thì no-op (tin không
   * có {@code body.message}, vd tin chỉ có file đính kèm).
   */
  public CompletionStage<Void> extractAndSave(UUID conversationId, UUID messageId, UUID fromUserId, String messageText) {
    if (messageText == null || messageText.isBlank()) {
      return CompletableFuture.completedFuture(null);
    }
    var matcher = URL_PATTERN.matcher(messageText);
    CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
    while (matcher.find()) {
      var url = matcher.group();
      chain = chain.thenCompose(unused -> supplier.execute(conn -> conn.preparedQuery(
              "INSERT INTO message_links (id, message_id, conversation_id, from_user_id, url) VALUES (?, ?, ?, ?, ?)")
          .execute(Tuple.of(UUID.randomUUID(), messageId, conversationId, fromUserId, url))
          .toCompletionStage()
          .thenApply(rows -> null)));
    }
    return chain;
  }

  /**
   * Toàn bộ link đã trích của 1 conversation, mới nhất trước -- JOIN {@code messages} để loại link
   * thuộc tin ĐÃ XOÁ MỀM ({@code deleted_at IS NULL}), không cần dọn {@code message_links} riêng lúc
   * xoá tin.
   */
  public CompletionStage<JsonArray> listForConversation(UUID conversationId, int limit) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
            "SELECT ml.id, ml.url, ml.from_user_id, "
                + "(extract(epoch from ml.created_at) * 1000)::bigint AS ts "
                + "FROM message_links ml JOIN messages m ON m.id = ml.message_id "
                + "WHERE ml.conversation_id = ? AND m.deleted_at IS NULL "
                + "ORDER BY ml.created_at DESC LIMIT ?")
        .execute(Tuple.of(conversationId, limit))
        .toCompletionStage()
        .thenApply(rows -> {
          var result = new JsonArray();
          for (var row : rows) {
            result.add(new JsonObject()
                .put("id", row.getUUID("id").toString())
                .put("url", row.getString("url"))
                .put("fromUserId", row.getUUID("from_user_id").toString())
                .put("ts", row.getLong("ts")));
          }
          return result;
        }));
  }
}
