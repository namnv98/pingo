package com.pingo.chat.domain.membership;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
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
     * Toan bo conversation ma userId dang la thanh vien -- dung cho UI "danh sach hoi thoai cua
     * ban" (GET /conversations). Kem full member list moi conversation (client tu suy label: DM
     * hien ten nguoi con lai, group hien so thanh vien) va thoi diem tin nhan gan nhat, sap xep
     * theo hoat dong gan day nhat truoc.
     */
    public CompletionStage<JsonArray> listConversationsForUser(UUID userId) {
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT cm.conversation_id, array_agg(cm.user_id) AS member_ids, "
                    + "(SELECT max(m.created_at) FROM messages m WHERE m.conversation_id = cm.conversation_id) AS last_message_at "
                    + "FROM conversation_members cm "
                    + "WHERE cm.conversation_id IN (SELECT conversation_id FROM conversation_members WHERE user_id = ?) "
                    + "GROUP BY cm.conversation_id "
                    + "ORDER BY last_message_at DESC NULLS LAST")
            .execute(Tuple.of(userId))
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
                    result.add(
                        new JsonObject()
                            .put("conversationId", row.getUUID("conversation_id").toString())
                            .put("memberUserIds", memberIds)
                            .put("lastMessageAt", lastMessageAt == null ? null : lastMessageAt.toInstant().toEpochMilli()));
                }
                return result;
            }));
    }
}
