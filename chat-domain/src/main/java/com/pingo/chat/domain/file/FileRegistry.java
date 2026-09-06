package com.pingo.chat.domain.file;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Backend metadata cho upload/download ảnh-video qua module {@code file-server} (nginx + Lua,
 * "openresty") -- KHÔNG lưu/đọc file thật (file-server tự làm việc đó trên đĩa của chính nó), chỉ
 * cấp/giữ {@code id}/{@code path}/{@code mime}/{@code name} theo đúng hợp đồng mà {@code jad.lua}
 * bên file-server đang gọi ra (3 route {@code create}/{@code update}/{@code get}, xem
 * {@code file-server/fileserver/v2/jad/jad.lua} + {@code fsconst.lua}) -- file-server nguyên bản
 * (copy từ dự án cũ lego-new) gọi ra 1 service tên "jad"/"message-chat" không tồn tại trong repo
 * này, hall đứng vai trò thay thế đúng 3 route đó (xem HallApiHandlers#createFile/updateFile/getFile),
 * fsconst.lua trỏ lại địa chỉ hall thay vì "message-chat...".
 *
 * <p>{@code path} luôn cố định {@link #STORAGE_PATH} (1 thư mục phẳng) -- đủ dùng cho quy mô demo/
 * test hiện tại, không cần phân vùng theo ngày/user như hệ thống lớn.
 */
@RequiredArgsConstructor
public class FileRegistry {

  /** Khớp {@code uploadFolder} trong file-server/helm values -- file thật nằm ở {@code uploadFolder + path + id}. */
  public static final String STORAGE_PATH = "files/";

  private final JdbcConnectionSupplier supplier;

  /**
   * {@code POST /file/create} -- cấp 1 fileId mới, ghi nhận người upload + conversationId (để tab
   * "Files" liệt kê lại được sau này, xem {@link #listForConversation}) -- mime/size/name điền sau ở
   * {@link #markUploaded}. {@code conversationId} null nếu client không gửi kèm (không chặn upload
   * vì lý do đó, chỉ là file đó sẽ không xuất hiện ở tab Files của conversation nào cả).
   */
  public CompletionStage<UUID> createFile(UUID userId, UUID conversationId) {
    var id = UUID.randomUUID();
    return supplier
        .execute(
            conn ->
                conn.preparedQuery("INSERT INTO files (id, user_id, conversation_id, path) VALUES (?, ?, ?, ?)")
                    .execute(Tuple.of(id, userId, conversationId, STORAGE_PATH))
                    .toCompletionStage())
        .thenApply(rows -> id);
  }

  /** {@code POST /file/update} -- file-server gọi SAU KHI đã ghi xong bytes lên đĩa, biết được size/mime thật. */
  public CompletionStage<Void> markUploaded(UUID fileId, String mime, long size, String name) {
    return supplier
        .execute(
            conn ->
                conn.preparedQuery("UPDATE files SET mime = ?, size = ?, name = COALESCE(?, name) WHERE id = ?")
                    .execute(Tuple.of(mime, size, name, fileId))
                    .toCompletionStage())
        .thenApply(rows -> null);
  }

  /** {@code GET /file/get} -- null nếu fileId không tồn tại (caller tự trả lỗi phù hợp, xem HallApiHandlers). */
  public CompletionStage<JsonObject> getFile(UUID fileId) {
    return supplier
        .executeReadOnly(
            conn ->
                conn.preparedQuery("SELECT id, path, mime, name FROM files WHERE id = ?")
                    .execute(Tuple.of(fileId))
                    .toCompletionStage())
        .thenApply(
            rows -> {
              var it = rows.iterator();
              if (!it.hasNext()) {
                return null;
              }
              var row = it.next();
              return new JsonObject()
                  .put("id", row.getUUID("id").toString())
                  .put("path", row.getString("path"))
                  .put("mime", row.getString("mime"))
                  .put("name", row.getString("name"));
            });
  }

  /**
   * Toàn bộ file đã upload gắn với 1 conversationId, mới nhất trước -- dùng cho tab "Files" panel
   * Info bên demo.html. Chỉ file ĐÃ UPLOAD XONG (mime/size đã điền qua {@link #markUploaded}) mới có
   * ý nghĩa hiển thị -- lọc {@code mime IS NOT NULL} để không lộ ra file đang upload dở/upload lỗi
   * giữa chừng (tạo xong {@link #createFile} nhưng chưa bao giờ tới {@link #markUploaded}).
   */
  public CompletionStage<JsonArray> listForConversation(UUID conversationId, int limit) {
    return supplier
        .executeReadOnly(
            conn ->
                conn.preparedQuery(
                        "SELECT id, user_id, mime, size, name, "
                            + "(extract(epoch from created_at) * 1000)::bigint AS ts "
                            + "FROM files WHERE conversation_id = ? AND mime IS NOT NULL "
                            + "ORDER BY created_at DESC LIMIT ?")
                    .execute(Tuple.of(conversationId, limit))
                    .toCompletionStage())
        .thenApply(
            rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                result.add(
                    new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("fromUserId", row.getUUID("user_id").toString())
                        .put("mime", row.getString("mime"))
                        .put("size", row.getLong("size"))
                        .put("name", row.getString("name"))
                        .put("ts", row.getLong("ts")));
              }
              return result;
            });
  }
}
