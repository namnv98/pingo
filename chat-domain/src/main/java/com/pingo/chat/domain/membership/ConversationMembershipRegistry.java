package com.pingo.chat.domain.membership;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;

/**
 * Membership (ai thuoc conversation nao) -- luu ben trong Postgres (bang conversation_members),
 * khong con chi song in-memory nhu ban dau (xem ARCHITECTURE.md muc 11). Vi la DB dung chung, MOI
 * pod colony deu doc/ghi truc tiep duoc, khong con rang buoc "chi pod owner moi biet membership".
 * Doc/ghi qua {@link JdbcConnectionSupplier} (core/commons-lang) thay vi tu mo {@code PgPool} rieng
 * -- framework co san, ho tro luon master/slave routing + health-sensing (xem ARCHITECTURE.md muc 14).
 *
 * <p><b>Placeholder la {@code ?} (chuan JDBC), KHONG phai {@code $1/$2} (native Postgres)</b> --
 * {@code vertx-jdbc-client} chay qua {@code java.sql.PreparedStatement} that, tu dem so dau {@code ?}
 * trong text SQL de biet co bao nhieu tham so; dung {@code $1/$2} se khien driver dem ra 0 tham so,
 * roi {@code getParameterMetaData()} nem {@code ArrayIndexOutOfBoundsException} khi co gang set kieu
 * cho tham so 1 tren 1 mang rong. Khac voi {@code vertx-pg-client} (wire-protocol Postgres thuan,
 * BAT BUOC {@code $1/$2}) -- 2 backend doi hoi 2 cu phap khac nhau, khong dung chung 1 chuoi SQL duoc.
 *
 * <p><b>2 pitfall JDBC-specific khac da gap that su khi migrate tu vertx-pg-client:</b>
 * <ul>
 *   <li>Mang Java ({@code UUID[]}, ...) bind qua {@code Tuple} KHONG tu dong thanh {@code
 *   java.sql.Array} -- {@code unnest(?::uuid[])} luon ra tap rong (0 dong, khong loi). Tranh dung SQL
 *   array qua duong nay; INSERT nhieu dong bang {@code VALUES (?,?),(?,?),...} thay vi {@code
 *   unnest()} (xem {@link #addMembers}).</li>
 *   <li>{@code rows.rowCount()} phan anh {@code Statement.getUpdateCount()} chuan JDBC -- LUON la -1
 *   cho SELECT (khac vertx-pg-client, noi rowCount() la so dong that qua wire-protocol). Dung {@code
 *   rows.iterator().hasNext()} de kiem tra "co dong nao khong", khong dung {@code rowCount() > 0}.</li>
 *   <li>{@code row.getArrayOfUUIDs()}/{@code getArrayOfXxx()} lam {@code (UUID[]) getValue(pos)} khong
 *   kiem tra kieu -- dung voi vertx-pg-client (wire decoder tra thang mang dung kieu), nhung qua
 *   vertx-jdbc-client, pgjdbc tra {@code array_agg(uuid)} ve {@code Object[]} (dung, khong phai
 *   {@code UUID[]}), nem {@code ClassCastException}. Doc qua {@code (Object[]) row.getValue(...)} roi
 *   tu convert tung phan tu, khong dung {@code getArrayOfXxx()} qua vertx-jdbc-client (xem
 *   {@link #listConversationsForUser}).</li>
 * </ul>
 */
@RequiredArgsConstructor
public class ConversationMembershipRegistry {

    private final JdbcConnectionSupplier supplier;

    /**
     * Union userIds vao membership, tra ve dung tap userId THUC SU MOI -- caller dung de quyet dinh
     * co can "danh thuc" ai khong. INSERT nhieu dong bang 1 cau VALUES (?,?),(?,?),... ON CONFLICT DO
     * NOTHING RETURNING duy nhat (khong dung {@code unnest(?::uuid[])}) -- qua {@code
     * vertx-jdbc-client}, tham so duoc bind bang {@code PreparedStatement.setObject()} chuan JDBC,
     * KHONG tu dong chuyen 1 mang Java ({@code UUID[]}) thanh {@code java.sql.Array}, nen {@code
     * unnest()} tren gia tri do luon ra tap rong (0 dong duoc chen, khong loi) -- da gap that su khi
     * fix bug placeholder {@code $1/$2}, sua bang cach khong dung SQL array o duong nay nua.
     */
    public CompletionStage<Set<UUID>> addMembers(UUID conversationId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        var placeholders = String.join(",", Collections.nCopies(userIds.size(), "(?, ?)"));
        var params = new ArrayList<Object>(userIds.size() * 2);
        for (var userId : userIds) {
            params.add(conversationId);
            params.add(userId);
        }
        return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO conversation_members (conversation_id, user_id) VALUES "
                    + placeholders
                    + " ON CONFLICT DO NOTHING RETURNING user_id")
            .execute(Tuple.from(params))
            .toCompletionStage()
            .thenApply(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.getUUID("user_id"))
                .collect(Collectors.toUnmodifiableSet())));
    }

    public CompletionStage<Boolean> isMember(UUID conversationId, UUID userId) {
        // KHONG dung rows.rowCount() -- qua vertx-jdbc-client, rowCount() phan anh
        // Statement.getUpdateCount() chuan JDBC, LUON la -1 cho cau SELECT (khac voi vertx-pg-client,
        // noi rowCount() phan anh dung so dong tra ve qua wire-protocol ca INSERT lan SELECT). Dung
        // iterator de kiem tra co dong nao khong, dung nhu 2 method con lai (verifyLogin, ...) da lam.
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ? LIMIT 1")
            .execute(Tuple.of(conversationId, userId))
            .toCompletionStage()
            .thenApply(rows -> rows.iterator().hasNext()));
    }

    public CompletionStage<Set<UUID>> getMembers(UUID conversationId) {
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ?")
            .execute(Tuple.of(conversationId))
            .toCompletionStage()
            .thenApply(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.getUUID("user_id"))
                .collect(Collectors.toUnmodifiableSet())));
    }

    /**
     * Xoá 1 thành viên khỏi conversation — dùng chung cho CẢ "kick người khác" lẫn "tự rời nhóm"
     * (khác biệt chỉ nằm ở tầng {@code HallApiHandlers}, không phải ở đây: cùng 1 câu DELETE, chỉ
     * khác {@code userId} truyền vào là của ai). Xem {@code HallApiHandlers#removeConversationMember}.
     */
    public CompletionStage<Void> removeMember(UUID conversationId, UUID userId) {
        return supplier.execute(conn -> conn.preparedQuery(
                "DELETE FROM conversation_members WHERE conversation_id = ? AND user_id = ?")
            .execute(Tuple.of(conversationId, userId))
            .toCompletionStage()
            .thenApply(unused -> null));
    }

    /**
     * Bật/tắt thông báo riêng của {@code userId} cho 1 conversation — chỉ ảnh hưởng chính người
     * đó (khác {@link #upsertName}/{@link #upsertAvatar}, dùng chung cho mọi thành viên), nên
     * không cần broadcast EventBus nào khi đổi. Xem {@code HallApiHandlers#setConversationMuted},
     * {@code ChatSessionManager#createNotification}/{@code #publishNotificationCandidates} (nơi
     * đọc lại để chặn notification/push cho conversation đã mute).
     */
    public CompletionStage<Void> setMuted(UUID conversationId, UUID userId, boolean muted) {
        return supplier.execute(conn -> conn.preparedQuery(
                "UPDATE conversation_members SET muted = ? WHERE conversation_id = ? AND user_id = ?")
            .execute(Tuple.of(muted, conversationId, userId))
            .toCompletionStage()
            .thenApply(unused -> null));
    }

    /** Xem {@code ChatSessionManager#createNotification} — chặn tạo notification reply/mention/reaction cho conversation đã mute. */
    public CompletionStage<Boolean> isMuted(UUID conversationId, UUID userId) {
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT 1 FROM conversation_members WHERE conversation_id = ? AND user_id = ? AND muted = true LIMIT 1")
            .execute(Tuple.of(conversationId, userId))
            .toCompletionStage()
            .thenApply(rows -> rows.iterator().hasNext()));
    }

    /** Xem {@code ChatSessionManager#publishNotificationCandidates} — lọc bớt candidate trước khi báo herald, tránh tạo/push notification "message" cho ai đã mute conversation này. */
    public CompletionStage<Set<UUID>> getMutedUserIds(UUID conversationId) {
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND muted = true")
            .execute(Tuple.of(conversationId))
            .toCompletionStage()
            .thenApply(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.getUUID("user_id"))
                .collect(Collectors.toUnmodifiableSet())));
    }

    /** Xoá TOÀN BỘ membership của 1 conversationId (mọi thành viên) — xem {@code HallApiHandlers#deleteConversation}. */
    public CompletionStage<Void> deleteConversation(UUID conversationId) {
        return supplier.execute(conn -> conn.preparedQuery("DELETE FROM conversation_members WHERE conversation_id = ?")
            .execute(Tuple.of(conversationId))
            .toCompletionStage()
            .thenApply(unused -> null));
    }

    /**
     * Đặt/đổi tên riêng cho 1 conversationId, dùng chung cho MỌI thành viên (khác bản đầu chỉ lưu
     * localStorage riêng từng trình duyệt) — xem {@code HallApiHandlers} POST/PUT {@code /conversations}.
     * {@code name} rỗng/blank thì CHỈ xoá cột tên (quay lại tự suy label từ danh sách thành viên
     * phía client, xem {@link #listConversationsForUser}) — KHÔNG xoá cả dòng nữa (khác trước đây)
     * vì dòng này giờ còn giữ {@code avatar_file_id} (xem {@link #upsertAvatar}), xoá cả dòng ở đây sẽ
     * vô tình mất luôn ảnh nhóm đã đặt riêng dù user chỉ muốn xoá TÊN. Xoá sạch cả dòng (mọi field)
     * dùng {@link #clearConversationRow} riêng — chỉ dành cho xoá hẳn conversation.
     */
    public CompletionStage<Void> upsertName(UUID conversationId, String name) {
        if (name == null || name.isBlank()) {
            return supplier.execute(conn -> conn.preparedQuery("UPDATE conversations SET name = NULL WHERE id = ?")
                .execute(Tuple.of(conversationId))
                .toCompletionStage()
                .thenApply(unused -> null));
        }
        return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO conversations (id, name) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name")
            .execute(Tuple.of(conversationId, name))
            .toCompletionStage()
            .thenApply(unused -> null));
    }

    /**
     * Đặt/đổi/xoá ảnh đại diện riêng cho 1 GROUP — {@code avatarFileId} null = xoá (quay lại icon
     * "users" mặc định phía client, xem {@code conversationAvatar()}). KHÔNG dùng cho DM — DM hiện
     * avatar thật của người kia, không phải thứ chính conversation "sở hữu". Cùng khuôn UPSERT như
     * nhánh còn lại của {@link #upsertName}, độc lập hoàn toàn với cột {@code name} trên cùng dòng.
     */
    public CompletionStage<Void> upsertAvatar(UUID conversationId, UUID avatarFileId) {
        return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO conversations (id, avatar_file_id) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET avatar_file_id = EXCLUDED.avatar_file_id")
            .execute(Tuple.of(conversationId, avatarFileId))
            .toCompletionStage()
            .thenApply(unused -> null));
    }

    /**
     * Xoá SẠCH dòng {@code conversations} (mọi field: tên riêng lẫn ảnh đại diện) — dùng riêng khi
     * xoá hẳn 1 conversation (xem {@code HallApiHandlers#deleteConversation}), khác {@link #upsertName}
     * với tên rỗng (giờ chỉ xoá cột tên, giữ lại ảnh nếu có, xem javadoc ở đó).
     */
    public CompletionStage<Void> clearConversationRow(UUID conversationId) {
        return supplier.execute(conn -> conn.preparedQuery("DELETE FROM conversations WHERE id = ?")
            .execute(Tuple.of(conversationId))
            .toCompletionStage()
            .thenApply(unused -> null));
    }


    /**
     * Toan bo conversation ma userId dang la thanh vien -- dung cho UI "danh sach hoi thoai cua
     * ban" (GET /conversations). Kem full member list moi conversation (client tu suy label: DM
     * hien ten nguoi con lai, group hien so thanh vien) va thoi diem tin nhan gan nhat, sap xep
     * theo hoat dong gan day nhat truoc -- "hoat dong" = tin nhan gan nhat, nhung conversation VUA
     * TAO (chua ai gui tin gi, last_message_at NULL) dung MIN(created_at) cua conversation_members
     * lam moc thay the, khong thi se rot xuong cuoi danh sach (NULLS LAST) thay vi len dau nhu ky
     * vong "vua tao xong phai thay ngay" -- da gap thuc te khi test tay UI (xem demo.html).
     */
    public CompletionStage<JsonArray> listConversationsForUser(UUID userId) {
        // Bọc 1 lớp subquery: Postgres KHÔNG cho dùng alias của SELECT list (last_message_at,
        // conv_created_at) bên trong 1 expression khác (COALESCE) ở ORDER BY của CHÍNH câu GROUP BY
        // đó -- chỉ cho dùng bare alias trực tiếp. Coi cả subquery như 1 bảng thường thì alias trở
        // thành cột thật, COALESCE ở ORDER BY ngoài mới hợp lệ.
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT * FROM ("
                    + "  SELECT cm.conversation_id, array_agg(cm.user_id) AS member_ids, "
                    + "    (SELECT max(m.created_at) FROM messages m WHERE m.conversation_id = cm.conversation_id) AS last_message_at, "
                    // Tin GẦN NHẤT (dù đã xoá hay chưa -- xoá thì vẫn phải hiện đúng chuyện vừa xảy ra
                    // "Tin nhắn đã bị xoá" ở sidebar, không lặng lẽ nhảy về tin cũ hơn, xem demo.html
                    // conversationLastMessagePreview()) -- 3 subquery riêng thay vì 1 join/LATERAL để
                    // giữ đúng pattern scalar-subquery-trong-SELECT-list đã dùng cho last_message_at/
                    // conv_name ở trên (an toàn với GROUP BY, không cần thêm cột vào GROUP BY).
                    + "    (SELECT m.body FROM messages m WHERE m.conversation_id = cm.conversation_id ORDER BY m.created_at DESC LIMIT 1) AS last_message_body, "
                    + "    (SELECT m.from_user_id FROM messages m WHERE m.conversation_id = cm.conversation_id ORDER BY m.created_at DESC LIMIT 1) AS last_message_from_user_id, "
                    + "    (SELECT m.deleted_at IS NOT NULL FROM messages m WHERE m.conversation_id = cm.conversation_id ORDER BY m.created_at DESC LIMIT 1) AS last_message_deleted, "
                    // Số tin CHƯA đọc CHÍNH XÁC (COUNT SQL thật, xem cùng lý do ở
                    // MessageHistoryRegistry#getReadCursor) -- KHÔNG dùng cờ tạm phía client
                    // (unreadConversationIds trong demo.html trước đây) vì nó chỉ sống trong bộ nhớ
                    // trình duyệt, mất sạch mỗi lần reload trang dù tin thật sự vẫn chưa đọc gì cả.
                    // COALESCE(cr.last_read_ts, 0): chưa từng đọc gì (không có dòng conversation_reads)
                    // thì coi mốc là "từ đầu thời gian" -- MỌI tin của người khác đều tính là chưa đọc.
                    + "    (SELECT COUNT(*) FROM messages m WHERE m.conversation_id = cm.conversation_id "
                    + "       AND m.from_user_id != ? AND m.deleted_at IS NULL "
                    + "       AND m.created_at > to_timestamp(COALESCE("
                    + "         (SELECT cr.last_read_ts FROM conversation_reads cr WHERE cr.conversation_id = cm.conversation_id AND cr.user_id = ?), 0"
                    + "       ) / 1000.0)"
                    + "    ) AS unread_count, "
                    + "    min(cm.created_at) AS conv_created_at, "
                    + "    (SELECT c.name FROM conversations c WHERE c.id = cm.conversation_id) AS conv_name, "
                    + "    (SELECT c.avatar_file_id FROM conversations c WHERE c.id = cm.conversation_id) AS conv_avatar_file_id, "
                    // Trạng thái mute CỦA RIÊNG userId đang gọi -- không phải cột chung của cm
                    // (mỗi member có thể mute độc lập), nên phải tra lại theo đúng userId qua
                    // scalar subquery, giống hệt cách unread_count đã đọc conversation_reads ở trên.
                    + "    (SELECT muted FROM conversation_members WHERE conversation_id = cm.conversation_id AND user_id = ?) AS my_muted "
                    + "  FROM conversation_members cm "
                    + "  WHERE cm.conversation_id IN (SELECT conversation_id FROM conversation_members WHERE user_id = ?) "
                    + "  GROUP BY cm.conversation_id"
                    + ") t "
                    + "ORDER BY COALESCE(last_message_at, conv_created_at) DESC")
            .execute(Tuple.of(userId, userId, userId, userId))
            .toCompletionStage()
            .thenApply(rows -> {
                var result = new JsonArray();
                for (var row : rows) {
                    // row.getArrayOfUUIDs() lam (UUID[]) getValue(pos) khong kiem tra kieu (xem
                    // Tuple.getArrayOfUUIDs trong vertx-sql-client) -- dung voi vertx-pg-client (wire
                    // decoder tra thang UUID[]), nhung qua vertx-jdbc-client, pgjdbc tra array_agg(uuid)
                    // ve Object[] (chua phan tu UUID/String ben trong, khong phai UUID[] that), nem
                    // ClassCastException. Doc qua Object[] roi String::valueOf, khong quan tam kieu
                    // phan tu ben trong la gi.
                    var rawMemberIds = (Object[]) row.getValue("member_ids");
                    var memberIds = new JsonArray(Arrays.stream(rawMemberIds).map(String::valueOf).toList());
                    var lastMessageAt = row.getOffsetDateTime("last_message_at");
                    var lastMessageDeleted = Boolean.TRUE.equals(row.getBoolean("last_message_deleted"));
                    var lastMessageBodyText = row.getString("last_message_body");
                    var lastMessageFromUserId = row.getUUID("last_message_from_user_id");
                    var avatarFileId = row.getUUID("conv_avatar_file_id");
                    result.add(
                        new JsonObject()
                            .put("conversationId", row.getUUID("conversation_id").toString())
                            .put("memberUserIds", memberIds)
                            .put("name", row.getString("conv_name"))
                            .put("avatarFileId", avatarFileId == null ? null : avatarFileId.toString())
                            .put("lastMessageAt", lastMessageAt == null ? null : lastMessageAt.toInstant().toEpochMilli())
                            .put("lastMessageFromUserId", lastMessageFromUserId == null ? null : lastMessageFromUserId.toString())
                            .put("lastMessageDeleted", lastMessageDeleted)
                            .put("lastMessageBody", lastMessageDeleted || lastMessageBodyText == null ? null : Json.decodeValue(lastMessageBodyText))
                            .put("unreadCount", row.getLong("unread_count"))
                            .put("muted", Boolean.TRUE.equals(row.getBoolean("my_muted"))));
                }
                return result;
            }));
    }
}
