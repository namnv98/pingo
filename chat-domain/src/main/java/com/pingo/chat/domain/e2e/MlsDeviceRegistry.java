package com.pingo.chat.domain.e2e;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Registry thiết bị MLS ({@code mls_devices}) -- 2 việc: (1) cho user TỰ xem danh sách thiết bị
 * của chính mình ở màn hình hồ sơ, (2) THU HỒI (revoke) 1 thiết bị ngay lập tức, dùng làm nền cho
 * {@code HallApiHandlers#resolveUserId}/{@code HarborSessionManager#handleAuth} từ chối MỌI request
 * (kể cả AUTH qua WebSocket) mang JWT của thiết bị đã revoke -- xem javadoc bảng {@code mls_devices}.
 * JWT tự nó là stateless (chỉ verify chữ ký + hạn dùng, không tra được trạng thái) nên đây chính là
 * cơ chế "thu hồi" bù vào, tách biệt hẳn khỏi {@link MlsRegistry} (đó là directory KeyPackage, dùng
 * 1 lần/claim; đây là danh tính + trạng thái sống-chết của thiết bị, không liên quan tới KeyPackage
 * còn lại bao nhiêu).
 */
@RequiredArgsConstructor
public class MlsDeviceRegistry {

  private final JdbcConnectionSupplier supplier;

  /**
   * Đăng ký (hoặc cập nhật label của) 1 thiết bị -- gọi mỗi lúc client publish KeyPackage (xem
   * {@code HallApiHandlers#publishMlsKeyPackages}), coi như "thiết bị vừa hoạt động". {@code ON
   * CONFLICT ... WHERE revoked_at IS NULL}: thiết bị ĐÃ bị revoke thì KHÔNG được "sống lại" qua
   * đường này -- registerDevice chỉ update label cho thiết bị còn sống, không đụng gì tới thiết bị
   * đã chết (client cầm deviceId cũ, đã revoke, cố publish lại KeyPackage vẫn bị JWT-level chặn ở
   * tầng auth trước khi tới được đây -- update này chỉ là phòng hộ thêm 1 lớp, không phải chặn chính).
   */
  public CompletionStage<Void> registerDevice(UUID userId, UUID deviceId, String label) {
    return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO mls_devices (device_id, user_id, label) VALUES (?, ?, ?) "
                    + "ON CONFLICT (device_id) DO UPDATE SET label = EXCLUDED.label "
                    + "WHERE mls_devices.revoked_at IS NULL")
            .execute(Tuple.of(deviceId, userId, label))
            .toCompletionStage())
        .thenApply(unused -> null);
  }

  /** Mọi thiết bị CÒN SỐNG (chưa revoke) của {@code userId}, mới nhất trước -- màn hình "Thiết bị của tôi". */
  public CompletionStage<JsonArray> listDevices(UUID userId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT device_id, label, created_at FROM mls_devices "
                    + "WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC")
            .execute(Tuple.of(userId))
            .toCompletionStage())
        .thenApply(rows -> {
          var arr = new JsonArray();
          for (var row : rows) {
            arr.add(new JsonObject()
                .put("deviceId", row.getUUID("device_id").toString())
                .put("label", row.getString("label"))
                .put("createdAt", row.getOffsetDateTime("created_at").toInstant().toEpochMilli()));
          }
          return arr;
        });
  }

  /**
   * Thu hồi NGAY 1 thiết bị của {@code userId} -- {@code userId} bắt buộc khớp chủ sở hữu (không ai
   * revoke hộ thiết bị người khác qua đường này). Trả {@code true} nếu vừa revoke thật (thiết bị tồn
   * tại, chưa revoke trước đó); {@code false} nếu không tìm thấy/đã revoke rồi (caller coi là no-op,
   * không phải lỗi -- xem {@code HallApiHandlers#deleteMlsDevice}).
   */
  public CompletionStage<Boolean> revokeDevice(UUID userId, UUID deviceId) {
    return supplier.execute(conn -> conn.preparedQuery(
                "UPDATE mls_devices SET revoked_at = now() "
                    + "WHERE device_id = ? AND user_id = ? AND revoked_at IS NULL")
            .execute(Tuple.of(deviceId, userId))
            .toCompletionStage())
        .thenApply(rows -> rows.rowCount() > 0);
  }

  /**
   * Thiết bị này đã bị revoke chưa -- gọi ở MỌI đường xác thực JWT (HallApiHandlers, harbor's
   * HarborSessionManager#handleAuth) khi token có kèm claim {@code deviceId}. Token CŨ (issue từ
   * trước khi có tính năng này, không có claim {@code deviceId}) coi như không thiết bị nào để tra
   * -- caller tự bỏ qua check này nếu claim rỗng, KHÔNG được coi thiếu claim là "revoked".
   */
  public CompletionStage<Boolean> isRevoked(UUID deviceId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT 1 FROM mls_devices WHERE device_id = ? AND revoked_at IS NOT NULL")
            .execute(Tuple.of(deviceId))
            .toCompletionStage())
        .thenApply(rows -> rows.iterator().hasNext());
  }

  /**
   * TOÀN BỘ device_id đã từng bị revoke (không lọc theo user) -- CHỈ dùng 1 lần lúc app khởi động để
   * hydrate {@link RevokedDeviceRegistry} (Hazelcast IMap, xem javadoc lớp đó) -- Postgres mới là
   * nguồn thật, tránh mất trạng thái thu hồi nếu cả cụm Hazelcast restart cùng lúc.
   */
  public CompletionStage<List<UUID>> listAllRevokedDeviceIds() {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT device_id FROM mls_devices WHERE revoked_at IS NOT NULL")
            .execute()
            .toCompletionStage())
        .thenApply(rows -> {
          var out = new ArrayList<UUID>();
          for (var row : rows) out.add(row.getUUID("device_id"));
          return out;
        });
  }
}
