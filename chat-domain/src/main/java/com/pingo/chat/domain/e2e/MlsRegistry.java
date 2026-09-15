package com.pingo.chat.domain.e2e;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Directory KeyPackage cho mã hoá đầu cuối bản MLS (RFC 9420, engine ts-mls phía client —
 * frontend/vendor/mls.js) — server CHỈ làm Delivery Service tối giản theo RFC 9750: chứa
 * KeyPackage public để người khác "claim" (dùng 1 lần) khi thêm thành viên vào nhóm MLS, và
 * trung chuyển ciphertext qua đúng hàng đợi to-device đã có (queue/drain tái dùng
 * {@link E2eKeyRegistry} — cùng bảng, cùng cơ chế relay sống EventBus, chỉ đổi payload).
 *
 * <p>So với Olm cũ: KHÔNG còn identity key per-device (credential user nằm TRONG KeyPackage,
 * do ts-mls ký, server không parse — chỉ là chuỗi base64 opaque), KHÔNG còn GroupInfo/epoch
 * trên server (1 MLS group/1 conversation, mọi Commit/Welcome đi qua hàng đợi to-device như
 * tin báo hiệu bình thường). {@code e2e_devices}/{@code e2e_one_time_prekeys}/{@code
 * e2e_device_link_requests} của bản Olm vẫn còn ở schema cho tới khi dọn sạch (client mới
 * không đọc/ghi chúng nữa; bảng device-link vẫn dùng cho "liên kết thiết bị" chuyển lịch sử
 * đã giải mã — xem frontend mls-crypto.js).
 */
@RequiredArgsConstructor
public class MlsRegistry {

  private final JdbcConnectionSupplier supplier;

  /**
   * Thêm 1 lô KeyPackage MỚI của {@code deviceId} (thuộc {@code userId}) — client tự sinh id
   * (UUID), {@code ON CONFLICT DO NOTHING} phòng retry mạng trùng id. Không thay thế lô cũ
   * (khác Olm: không có khái niệm "mark published" — keypackage cũ hết hạn tự nhiên theo
   * lifetime MLS, claim FIFO cũ→mới giống claimOneDeviceBundle bên Olm).
   */
  public CompletionStage<Void> publishKeyPackages(UUID userId, UUID deviceId, List<String> keyPackagesBase64) {
    if (keyPackagesBase64.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    var placeholders = String.join(",", Collections.nCopies(keyPackagesBase64.size(), "(?, ?, ?, ?)"));
    var params = new ArrayList<Object>(keyPackagesBase64.size() * 4);
    for (var kp : keyPackagesBase64) {
      params.add(UUID.randomUUID());
      params.add(deviceId);
      params.add(userId);
      params.add(kp);
    }
    return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO mls_key_packages (id, device_id, user_id, key_package) VALUES " + placeholders + " ON CONFLICT DO NOTHING")
            .execute(Tuple.from(params))
            .toCompletionStage()
            .thenApply(unused -> null));
  }

  /** Số KeyPackage còn lại của THIẾT BỊ này — client tự quyết lúc nào cần top-up (giống /e2e/prekey-count cũ). */
  public CompletionStage<Long> countKeyPackages(UUID deviceId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT COUNT(*) AS c FROM mls_key_packages WHERE device_id = ?")
            .execute(Tuple.of(deviceId))
            .toCompletionStage()
            .thenApply(rows -> rows.iterator().next().getLong("c")));
  }

  /**
   * Claim (lấy + XOÁ luôn, dùng 1 lần) {@code limit} KeyPackage CŨ NHẤT của {@code targetUserId}
   * — dùng lúc thêm thành viên vào 1 nhóm MLS: mỗi người được add tiêu tốn đúng 1 KeyPackage.
   * Phát sinh {@code needed = limit} nhưng user có NHIỀU thiết bị thì chia đều theo thiết bị
   * (thiết bị nào có KeyPackage cũng được đại diện — không cần biết trước thiết bị nào, khác
   * fan-out per-device của Olm: MLS ciphertext giải được bởi MỌI client trong group). Trả mảng
   * rỗng (không phải lỗi) nếu user chưa từng bật MLS — "chưa có keypackage" là trạng thái hợp lệ
   * để client báo "người này chưa bật mã hoá" và KHÔNG thêm được vào nhóm.
   */
  public CompletionStage<JsonArray> claimKeyPackages(UUID targetUserId, int limit) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
                    "SELECT device_id FROM mls_key_packages WHERE user_id = ? GROUP BY device_id ORDER BY MIN(created_at) ASC")
                    .execute(Tuple.of(targetUserId))
                    .toCompletionStage())
            .thenApply(rows -> {
              List<UUID> devices = new ArrayList<>();
              for (var row : rows) {
                devices.add(row.getUUID("device_id"));
              }
              return devices;
            })
            .thenCompose(devices -> claimRoundRobin(devices, new JsonArray(), Math.max(1, limit), 0));
  }

  /**
   * Claim tối đa {@code limit} KeyPackage, chia round-robin qua {@code devices} để mỗi thiết bị của
   * user đều có cơ hội được đại diện (thiết bị nào cạn thì vòng đó trả null, bỏ qua). Dừng ngay khi
   * đủ limit hoặc {@code MAX_CLAIM_ROUNDS} vòng mà không lấy thêm được gì (user gần hết keypackage).
   */
  private static final int MAX_CLAIM_ROUNDS = 200;

  private CompletableFuture<JsonArray> claimRoundRobin(List<UUID> devices, JsonArray acc, int limit, int round) {
    if (acc.size() >= limit || devices.isEmpty() || round >= MAX_CLAIM_ROUNDS) {
      return CompletableFuture.completedFuture(acc);
    }
    var perDevice = devices.stream()
        .map(d -> claimOneKeyPackage(d).thenApply(kp -> {
          if (kp != null) {
            kp.put("deviceId", d.toString());
          }
          return kp;
        }))
        .toList();
    return CompletableFuture.allOf(perDevice.toArray(CompletableFuture[]::new))
        .thenCompose(unused -> {
          perDevice.forEach(f -> {
            var kp = f.join();
            if (kp != null && acc.size() < limit) {
              acc.add(kp);
            }
          });
          return claimRoundRobin(devices, acc, limit, round + 1);
        });
  }

  private CompletableFuture<JsonObject> claimOneKeyPackage(UUID deviceId) {
    return supplier.execute(conn -> conn.preparedQuery(
                    "DELETE FROM mls_key_packages WHERE id = ("
                            + "  SELECT id FROM mls_key_packages WHERE device_id = ? ORDER BY created_at ASC LIMIT 1"
                            + ") RETURNING key_package")
                    .execute(Tuple.of(deviceId))
                    .toCompletionStage())
            .thenApply(rows -> {
              var it = rows.iterator();
              if (!it.hasNext()) {
                return null;
              }
              return new JsonObject().put("keyPackage", it.next().getString("key_package"));
            })
            .toCompletableFuture();
  }

  /** Gỡ toàn bộ KeyPackage CHƯA DÙNG của 1 thiết bị — "xoá thiết bị" bản MLS (khác Olm: không có session nào để thu hồi, device chưa join group nào thì keypackage là mọi thứ còn lại). */
  public CompletionStage<Void> deleteKeyPackagesOfDevice(UUID deviceId, UUID ownerUserId) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM mls_key_packages WHERE device_id = ? AND user_id = ?")
            .execute(Tuple.of(deviceId, ownerUserId))
            .toCompletionStage())
            .thenApply(unused -> null);
  }
}
