package com.lego.colony.ws.user;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Đăng ký username hiển thị cho 1 userId — KHÔNG phải hệ thống login (không có password/token gì
 * cả), cùng mức độ tin cậy với {@code fromUserId} client tự khai (xem ARCHITECTURE.md mục 8). Chỉ
 * để UI khỏi phải hiện UUID thô cho người dùng chọn (DM peer, thành viên group) — xem
 * {@code RestApiVerticle}.
 */
@RequiredArgsConstructor
public class UserRegistry {

  private final Pool pool;

  /** Client tự đặt/đổi tên hiển thị cho chính mình — upsert, không cần biết trước đã tồn tại hay chưa. */
  public CompletionStage<Void> upsertUsername(UUID id, String username) {
    return pool.preparedQuery("INSERT INTO users (id, username) VALUES ($1, $2) ON CONFLICT (id) DO UPDATE SET username = EXCLUDED.username")
        .execute(Tuple.of(id, username))
        .toCompletionStage()
        .thenApply(unused -> null);
  }

  /** Toàn bộ user đã từng xuất hiện — dùng cho UI hiện danh sách để chọn (DM peer/thành viên group). */
  public CompletionStage<JsonArray> listUsers() {
    // Nguoi da co ten hien thi len truoc (theo alphabet), roi moi toi nguoi chua dat ten (moi xuat
    // hien truoc) -- danh sach thuc te co the co rat nhieu UUID chua dat ten (vd tu load test), de
    // no lan at nhung user "that" da dat ten se kho tim.
    return pool.preparedQuery("SELECT id, username, first_seen_at FROM users ORDER BY (username IS NULL), username, first_seen_at DESC")
        .execute()
        .toCompletionStage()
        .thenApply(
            rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                result.add(
                    new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("username", row.getString("username"))
                        .put("firstSeenAt", row.getOffsetDateTime("first_seen_at").toInstant().toEpochMilli()));
              }
              return result;
            });
  }
}
