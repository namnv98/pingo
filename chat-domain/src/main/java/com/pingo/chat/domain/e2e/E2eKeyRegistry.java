package com.pingo.chat.domain.e2e;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * Class này NGUYÊN GỐC phục vụ hệ mã hoá E2E kiểu Olm/Megolm (Double Ratchet + X3DH, kiểu Signal) --
 * đã THAY HẲN bằng MLS (RFC 9420, xem {@link MlsRegistry}/{@link MlsDeviceRegistry}, client dùng
 * frontend/vendor/mls.js). Server CHỈ trung chuyển public key/KeyPackage + ciphertext "to-device",
 * KHÔNG bao giờ thấy private key hay nội dung tin nhắn đã giải mã -- điều đó vẫn đúng dù Olm hay MLS.
 * 2 nhóm việc SAU ĐÂY vẫn đang được MLS TÁI DÙNG:
 *
 * <p>1) Hàng đợi "to-device" ({@link #queueToDeviceMessage}/{@link #drainToDeviceMessages}) -- gửi
 * tin báo hiệu mã hoá THẲNG cho 1 user cụ thể (mọi thiết bị đang sống của họ, mỗi thiết bị tự lọc
 * lấy phần của mình qua {@code targetDeviceId} trong body), không gắn với 1 conversationId để
 * fan-out theo Maglev như message thường (xem {@code HallApiHandlers}'s {@code POST}/{@code
 * GET /e2e/to-device}). TRƯỚC ĐÂY mang tin thiết lập Olm session/phân phối Megolm session key; NAY
 * mang Welcome/Commit MLS + payload "liên kết thiết bị" (xem mục 2) -- cùng 1 cơ chế relay câm, chỉ
 * đổi NỘI DUNG body mà server không đọc/không cần biết.
 *
 * <p>2) Mã liên kết thiết bị (gõ tay, không QR, {@link #createDeviceLinkRequest}/
 * {@link #claimDeviceLinkRequest}) -- CHỈ dùng để chuyển giao LỊCH SỬ (cache plaintext đã giải mã +
 * group state MLS) sang thiết bị mới, KHÔNG chuyển giao identity (mỗi thiết bị tự có identity/leaf
 * MLS riêng từ đầu). Field {@code identityKey}/{@code oneTimeKeyId}/{@code oneTimeKey} vẫn giữ TÊN
 * cũ thời Olm (identity key + one-time-prekey) nhưng NAY chỉ là chuỗi opaque chứa 1 KeyPackage MLS
 * đã encode (tạm, dùng 1 lần cho việc link, không publish ở {@code /mls/key-packages}) -- server
 * không phân biệt/không quan tâm nội dung thật là gì, chỉ trung chuyển mù đúng như Olm trước đây.
 *
 * <p>Các hàm CÒN LẠI trong file này ({@link #upsertDevice}, {@link #listDevices}, {@link
 * #deleteDevice}, {@link #listDevicesForUser}, {@link #addOneTimePrekeys}, {@link
 * #countOneTimePrekeys}, {@link #claimKeyBundlesForUser}) là phần identity-key/one-time-prekey
 * THẬT của hệ Olm/Megolm cũ -- KHÔNG còn nơi nào gọi tới nữa (đã kiểm tra: không có call site nào
 * trong repo ngoài chính file này) từ khi {@link MlsDeviceRegistry} thay thế hoàn toàn cho việc
 * quản lý danh sách thiết bị + key. Còn giữ lại (chưa xoá) vì ngoài phạm vi rà soát luồng
 * backup/restore/link hiện tại -- CẦN 1 quyết định dọn dẹp riêng (kèm kiểm tra bảng {@code
 * e2e_devices}/{@code e2e_one_time_prekeys} có còn dữ liệu/được service khác đọc trực tiếp không)
 * trước khi xoá hẳn.
 */
@RequiredArgsConstructor
public class E2eKeyRegistry {

  private final JdbcConnectionSupplier supplier;

  /**
   * Đăng ký/cập nhật identity key của 1 THIẾT BỊ -- {@code deviceId} do CLIENT tự sinh 1 lần/trình
   * duyệt (UUID ngẫu nhiên, lưu localStorage, KHÔNG BAO GIỜ đổi). Upsert đơn giản, KHÔNG còn khái
   * niệm "xung đột identity" của bản trước (1 identity dùng chung cả tài khoản) -- mỗi thiết bị có
   * 1 hàng RIÊNG ở đây, mở thêm bao nhiêu thiết bị/trình duyệt cũng không ai ghi đè ai. {@code label}
   * (tuỳ chọn, vd "Chrome trên macOS") chỉ để NGƯỜI DÙNG nhận diện thiết bị trong danh sách quản lý
   * (xem {@code GET /e2e/devices}) -- KHÔNG có ý nghĩa bảo mật/kỹ thuật gì, cập nhật lại mỗi lần
   * upload để tự khớp nếu trình duyệt/OS đổi.
   */
  public CompletionStage<Void> upsertDevice(UUID deviceId, UUID userId, String identityKeyBase64, String label) {
    return supplier.execute(conn -> conn.preparedQuery(
                    "INSERT INTO e2e_devices (device_id, user_id, identity_key, label) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (device_id) DO UPDATE SET identity_key = EXCLUDED.identity_key, label = EXCLUDED.label")
            .execute(Tuple.of(deviceId, userId, identityKeyBase64, label))
            .toCompletionStage()
            .thenApply(unused -> null));
  }

  /** {@code GET /e2e/devices} -- toàn bộ thiết bị hiện có của CHÍNH MÌNH, mới nhất trước, để hiện màn "Thiết bị của tôi" (không kèm one-time prekey, không cần cho việc này). */
  public CompletionStage<JsonArray> listDevices(UUID userId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery(
                    "SELECT device_id, label, (extract(epoch from created_at) * 1000)::bigint AS created_at_ms "
                            + "FROM e2e_devices WHERE user_id = ? ORDER BY created_at DESC")
            .execute(Tuple.of(userId))
            .toCompletionStage()
            .thenApply(rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                result.add(new JsonObject()
                        .put("deviceId", row.getUUID("device_id").toString())
                        .put("label", row.getString("label"))
                        .put("createdAt", row.getLong("created_at_ms")));
              }
              return result;
            }));
  }

  /**
   * Gỡ 1 thiết bị của CHÍNH MÌNH ("đăng xuất từ xa" 1 thiết bị -- vd máy cũ đã mất/không dùng nữa)
   * -- xoá cả one-time prekey còn lại của nó. {@code ownerUserId} PHẢI khớp {@code user_id} đã lưu
   * (chặn xoá thiết bị của người KHÁC) -- atomic, trả {@code false} nếu không tìm thấy/không phải
   * chủ. KHÔNG tự thu hồi các Olm session người khác ĐÃ thiết lập với thiết bị này trước đó (đã trót
   * gửi rồi thì thôi) -- chỉ ngăn KHÔNG cho ai thiết lập session MỚI với nó nữa (claim bundle sẽ
   * không còn thấy thiết bị này).
   */
  public CompletionStage<Boolean> deleteDevice(UUID deviceId, UUID ownerUserId) {
    return supplier.execute(conn -> conn.preparedQuery("DELETE FROM e2e_devices WHERE device_id = ? AND user_id = ? RETURNING device_id")
                    .execute(Tuple.of(deviceId, ownerUserId))
                    .toCompletionStage())
            .thenCompose(rows -> {
              if (!rows.iterator().hasNext()) {
                return CompletableFuture.completedFuture(false);
              }
              return supplier.execute(conn -> conn.preparedQuery("DELETE FROM e2e_one_time_prekeys WHERE device_id = ?")
                              .execute(Tuple.of(deviceId))
                              .toCompletionStage())
                      .thenApply(unused -> true);
            });
  }

  /**
   * Liệt kê thiết bị (chỉ {@code deviceId}+{@code identityKey}, KHÔNG claim/xoá prekey nào -- khác
   * {@link #claimKeyBundlesForUser}) của {@code userId} -- dùng để KIỂM TRA xem người nhận có thiết
   * bị nào CHƯA từng nhận 1 khoá phiên Megolm cụ thể hay không, mà KHÔNG phải trả giá tiêu tốn 1
   * one-time prekey mỗi lần chỉ để kiểm tra (prekey chỉ nên bị tiêu khi THỰC SỰ thiết lập session
   * mới, xem frontend's e2eGetOrCreateOutboundGroupSession -- bug thật đã gặp trước khi tách API
   * này riêng: dùng chung {@code claimKeyBundlesForUser} để "kiểm tra" làm hao prekey vô ích).
   */
  public CompletionStage<JsonArray> listDevicesForUser(UUID userId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT device_id, identity_key FROM e2e_devices WHERE user_id = ?")
            .execute(Tuple.of(userId))
            .toCompletionStage()
            .thenApply(rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                result.add(new JsonObject().put("deviceId", row.getUUID("device_id").toString()).put("identityKey", row.getString("identity_key")));
              }
              return result;
            }));
  }

  /**
   * Thêm 1 lô one-time prekey MỚI (Curve25519, public, base64) của {@code deviceId} — client tự
   * sinh {@code keyId} (vd UUID), KHÔNG cần lo trùng giữa nhiều lần gọi vì insert nhiều dòng bằng
   * {@code VALUES (?,?,?),...} (không dùng {@code unnest()} qua vertx-jdbc-client, cùng lý do đã
   * ghi ở {@code ConversationMembershipRegistry#addMembers}) — {@code ON CONFLICT DO NOTHING}
   * phòng gọi lại đúng {@code keyId} cũ (vd retry mạng).
   */
  public CompletionStage<Void> addOneTimePrekeys(UUID deviceId, Map<String, String> keyIdToPublicKey) {
    if (keyIdToPublicKey.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    var placeholders = String.join(",", Collections.nCopies(keyIdToPublicKey.size(), "(?, ?, ?)"));
    var params = new ArrayList<Object>(keyIdToPublicKey.size() * 3);
    for (var entry : keyIdToPublicKey.entrySet()) {
      params.add(deviceId);
      params.add(entry.getKey());
      params.add(entry.getValue());
    }
    return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO e2e_one_time_prekeys (device_id, key_id, public_key) VALUES " + placeholders + " ON CONFLICT DO NOTHING")
            .execute(Tuple.from(params))
            .toCompletionStage()
            .thenApply(unused -> null));
  }

  public CompletionStage<Long> countOneTimePrekeys(UUID deviceId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT COUNT(*) AS c FROM e2e_one_time_prekeys WHERE device_id = ?")
            .execute(Tuple.of(deviceId))
            .toCompletionStage()
            .thenApply(rows -> rows.iterator().next().getLong("c")));
  }

  private record DeviceRow(UUID deviceId, String identityKey) {}

  /**
   * Lấy TOÀN BỘ thiết bị hiện có của {@code targetUserId} -- MỖI thiết bị tự CHIẾM (xoá) riêng 1
   * one-time prekey CŨ NHẤT của chính nó, độc lập với các thiết bị khác (atomic từng cái, cùng
   * khuôn {@code DELETE ... WHERE key_id = (SELECT ... LIMIT 1) RETURNING} như bản 1-identity cũ,
   * chỉ khác là lặp qua NHIỀU thiết bị thay vì 1). Dùng để mã hoá RIÊNG cho TỪNG thiết bị của người
   * nhận (fan-out đúng chuẩn Signal/Sesame, xem frontend's e2eEncryptOutgoing) -- trả mảng RỖNG nếu
   * {@code targetUserId} chưa từng bật E2E ở bất kỳ thiết bị nào. {@code oneTimePrekey} của 1 thiết
   * bị có thể null nếu riêng thiết bị đó hết prekey (vẫn tạo session được, chỉ kém 1 lớp
   * forward-secrecy ở tin đầu, xem {@code create_outbound} trong olm.js README).
   */
  public CompletionStage<JsonArray> claimKeyBundlesForUser(UUID targetUserId) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT device_id, identity_key FROM e2e_devices WHERE user_id = ?")
                    .execute(Tuple.of(targetUserId))
                    .toCompletionStage()
                    .thenApply(rows -> {
                      List<DeviceRow> devices = new ArrayList<>();
                      for (var row : rows) {
                        devices.add(new DeviceRow(row.getUUID("device_id"), row.getString("identity_key")));
                      }
                      return devices;
                    }))
            .thenCompose(devices -> {
              var futures = devices.stream().map(this::claimOneDeviceBundle).toList();
              return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                      .thenApply(unused -> {
                        var result = new JsonArray();
                        futures.forEach(f -> result.add(f.join()));
                        return result;
                      });
            });
  }

  private CompletableFuture<JsonObject> claimOneDeviceBundle(DeviceRow device) {
    return supplier.execute(conn -> conn.preparedQuery(
                    "DELETE FROM e2e_one_time_prekeys WHERE device_id = ? AND key_id = ("
                            + "  SELECT key_id FROM e2e_one_time_prekeys WHERE device_id = ? ORDER BY created_at ASC LIMIT 1"
                            + ") RETURNING key_id, public_key")
                    .execute(Tuple.of(device.deviceId(), device.deviceId()))
                    .toCompletionStage())
            .thenApply(rows -> {
              var result = new JsonObject().put("deviceId", device.deviceId().toString()).put("identityKey", device.identityKey());
              var it = rows.iterator();
              if (it.hasNext()) {
                var row = it.next();
                result.put("oneTimePrekey", new JsonObject().put("keyId", row.getString("key_id")).put("publicKey", row.getString("public_key")));
              } else {
                result.putNull("oneTimePrekey");
              }
              return result;
            })
            .toCompletableFuture();
  }

  /**
   * Xếp 1 tin to-device vào hàng đợi của {@code recipientUserId} — CHỈ persist, KHÔNG relay sống
   * (xem {@code HallApiHandlers#queueE2eToDevice} lo phần publish EventBus riêng ngay sau khi gọi
   * xong, để không phụ thuộc DB write có thành công mới quyết định relay hay không — relay sống chỉ
   * là tối ưu tốc độ, hàng đợi này mới là nguồn thật, đọc lại đủ khi {@link #drainToDeviceMessages}).
   * Địa chỉ theo USER (mọi thiết bị đang sống của họ đều được relay/drain tới) -- thiết bị nào mới
   * là đích thật thì tự lọc qua {@code targetDeviceId} nằm trong {@code bodyJson}.
   */
  public CompletionStage<Void> queueToDeviceMessage(UUID id, UUID recipientUserId, UUID senderUserId, UUID conversationId, String type, String bodyJson) {
    return supplier.execute(conn -> conn.preparedQuery(
                    "INSERT INTO e2e_to_device_messages (id, recipient_user_id, sender_user_id, conversation_id, type, body_json) VALUES (?, ?, ?, ?, ?, ?)")
            .execute(Tuple.of(id, recipientUserId, senderUserId, conversationId, type, bodyJson))
            .toCompletionStage()
            .thenApply(unused -> null));
  }

  /**
   * Lấy hết + XOÁ SẠCH hàng đợi to-device của {@code userId} — gọi lúc client connect lại (drain 1
   * lần, không phải poll định kỳ) để bù những tin gửi lúc mình offline (relay sống lúc đó không tới
   * đâu được, xem {@link #queueToDeviceMessage}). Sắp cũ->mới ({@code created_at}) để client áp dụng
   * đúng thứ tự (vd 2 lần rotate Megolm liên tiếp phải áp theo đúng trình tự) -- sort THỦ CÔNG bên
   * Java sau khi fetch, KHÔNG {@code ORDER BY} ngay trên câu {@code DELETE ... RETURNING} (Postgres
   * không cho phép {@code ORDER BY} trên DML, chỉ SELECT -- lỗi cú pháp thật đã gặp khi test:
   * {@code ERROR: syntax error at or near "ORDER"}).
   */
  public CompletionStage<JsonArray> drainToDeviceMessages(UUID userId) {
    return supplier.execute(conn -> conn.preparedQuery(
                    "DELETE FROM e2e_to_device_messages WHERE recipient_user_id = ? RETURNING id, sender_user_id, conversation_id, type, body_json, "
                            + "(extract(epoch from created_at) * 1000)::bigint AS ts")
            .execute(Tuple.of(userId))
            .toCompletionStage()
            .thenApply(rows -> {
              var items = new ArrayList<JsonObject>();
              for (var row : rows) {
                var conversationId = row.getUUID("conversation_id");
                items.add(new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("senderUserId", row.getUUID("sender_user_id").toString())
                        .put("conversationId", conversationId == null ? null : conversationId.toString())
                        .put("type", row.getString("type"))
                        .put("body", io.vertx.core.json.Json.decodeValue(row.getString("body_json")))
                        .put("ts", row.getLong("ts")));
              }
              items.sort(java.util.Comparator.comparingLong(item -> item.getLong("ts")));
              return new JsonArray(new ArrayList<Object>(items));
            }));
  }

  /** Postgres unique_violation — https://www.postgresql.org/docs/current/errcodes-appendix.html (cùng khuôn UserRegistry). */
  private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";
  // Bỏ nguyên âm + ký tự dễ nhầm khi gõ tay (0/O, 1/I/L) -- mã liên kết PHẢI gõ tay được, không quét QR.
  private static final String LINK_CODE_ALPHABET = "23456789BCDFGHJKMNPQRSTVWXYZ";
  private static final int LINK_CODE_LENGTH = 6;
  private static final SecureRandom LINK_CODE_RANDOM = new SecureRandom();

  private static String randomLinkCode() {
    var sb = new StringBuilder(LINK_CODE_LENGTH);
    for (var i = 0; i < LINK_CODE_LENGTH; i++) {
      sb.append(LINK_CODE_ALPHABET.charAt(LINK_CODE_RANDOM.nextInt(LINK_CODE_ALPHABET.length())));
    }
    return sb.toString();
  }

  private static boolean isUniqueViolation(Throwable ex) {
    var cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
    if (cause instanceof PgException pgEx) {
      return UNIQUE_VIOLATION_SQLSTATE.equals(pgEx.getSqlState());
    }
    if (cause instanceof SQLException sqlEx) {
      return UNIQUE_VIOLATION_SQLSTATE.equals(sqlEx.getSQLState());
    }
    return cause.getCause() instanceof SQLException sqlEx && UNIQUE_VIOLATION_SQLSTATE.equals(sqlEx.getSQLState());
  }

  /**
   * Sinh 1 mã liên kết thiết bị 6 ký tự (gõ tay, KHÔNG quét QR), hết hạn sau 5 phút -- gọi từ thiết
   * bị MỚI (đã tự có identity/leaf MLS riêng từ đầu, xem javadoc đầu file) muốn LẤY LẠI lịch sử đã
   * giải mã của 1 thiết bị khác cùng tài khoản. Thiết bị mới tự tạo 1 KeyPackage MLS TẠM (không
   * publish ở {@code /mls/key-packages} -- chỉ dùng 1 lần cho việc link này, xem
   * frontend's {@code e2eRequestDeviceLink}), gửi public phần của nó vào {@code identityKey}/
   * {@code oneTimeKey} (tên field còn giữ lại từ thời Olm, nay chỉ là chuỗi opaque) để nhận CHUYỂN
   * GIAO gói lịch sử qua 1 group MLS tạm 2 thành viên, xem {@link #claimDeviceLinkRequest}. Retry vài
   * lần nếu trùng mã (hiếm, không gian 28^6 ~ 481 triệu tổ hợp).
   */
  public CompletionStage<String> createDeviceLinkRequest(UUID userId, String identityKey, String oneTimeKeyId, String oneTimeKey) {
    return attemptCreateDeviceLinkRequest(userId, identityKey, oneTimeKeyId, oneTimeKey, 5);
  }

  private CompletionStage<String> attemptCreateDeviceLinkRequest(
      UUID userId, String identityKey, String oneTimeKeyId, String oneTimeKey, int attemptsLeft) {
    var code = randomLinkCode();
    return supplier.execute(conn -> conn.preparedQuery(
                    "INSERT INTO e2e_device_link_requests (code, user_id, identity_key, one_time_key_id, one_time_key) VALUES (?, ?, ?, ?, ?)")
                .execute(Tuple.of(code, userId, identityKey, oneTimeKeyId, oneTimeKey))
                .toCompletionStage())
        .thenApply(unused -> code)
        .exceptionally(ex -> {
          if (isUniqueViolation(ex) && attemptsLeft > 1) {
            return null;
          }
          throw ex instanceof RuntimeException re ? re : new RuntimeException(ex);
        })
        .thenCompose(result -> result != null
                ? CompletableFuture.completedFuture(result)
                : attemptCreateDeviceLinkRequest(userId, identityKey, oneTimeKeyId, oneTimeKey, attemptsLeft - 1));
  }

  /**
   * Lấy + XOÁ LUÔN (dùng 1 lần, atomic {@code DELETE ... RETURNING}) bundle
   * {@code (identityKey, oneTimeKey)} TẠM của mã liên kết -- gọi từ thiết bị ĐÃ có lịch sử, sau khi
   * user gõ đúng mã hiển thị trên thiết bị MỚI. {@code requesterUserId} PHẢI khớp {@code user_id} đã
   * lưu -- chặn 1 tài khoản KHÁC lỡ biết/đoán trúng mã của người khác rồi tự ý "duyệt" (sẽ khiến
   * thiết bị mới nhận nhầm gói lịch sử KHÔNG PHẢI của tài khoản mình từ kẻ tấn công). Trả
   * {@code null} nếu mã sai/hết hạn (quá 5 phút)/thuộc user khác -- 3 trường hợp trả về GIỐNG NHAU
   * để không lộ mã nào tồn tại cho ai.
   */
  public CompletionStage<JsonObject> claimDeviceLinkRequest(String code, UUID requesterUserId) {
    return supplier.execute(conn -> conn.preparedQuery(
                    "DELETE FROM e2e_device_link_requests WHERE code = ? AND user_id = ? AND created_at > now() - interval '5 minutes' "
                            + "RETURNING identity_key, one_time_key_id, one_time_key")
            .execute(Tuple.of(code, requesterUserId))
            .toCompletionStage()
            .thenApply(rows -> {
              var it = rows.iterator();
              if (!it.hasNext()) {
                return null;
              }
              var row = it.next();
              return new JsonObject()
                      .put("identityKey", row.getString("identity_key"))
                      .put("oneTimeKeyId", row.getString("one_time_key_id"))
                      .put("oneTimeKey", row.getString("one_time_key"));
            }));
  }
}
