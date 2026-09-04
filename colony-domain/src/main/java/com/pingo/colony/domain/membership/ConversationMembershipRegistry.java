package com.pingo.colony.domain.membership;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import java.util.Arrays;
import java.util.Collection;
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
 */
@RequiredArgsConstructor
public class ConversationMembershipRegistry {

    private final JdbcConnectionSupplier supplier;

    /**
     * Union userIds vao membership, tra ve dung tap userId THUC SU MOI -- caller dung de quyet dinh
     * co can "danh thuc" ai khong. 1 cau INSERT...SELECT unnest()...ON CONFLICT DO NOTHING RETURNING
     * duy nhat de chen nhieu dong trong 1 round-trip; RETURNING chi tra dong THAT SU duoc chen.
     */
    public CompletionStage<Set<UUID>> addMembers(UUID conversationId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        var userIdArray = userIds.toArray(new UUID[0]);
        return supplier.execute(conn -> conn.preparedQuery(
                "INSERT INTO conversation_members (conversation_id, user_id) "
                    + "SELECT $1, unnest($2::uuid[]) "
                    + "ON CONFLICT DO NOTHING "
                    + "RETURNING user_id")
            // Ep kieu (Object) de chon overload Tuple.of(Object, Object) co dinh 2 tham so, khong
            // phai Tuple.of(Object, Object...) varargs -- UUID[] array-covariant voi Object[] nen
            // bien dich se "trai" mang thanh nhieu tham so rieng le neu khong ep kieu, gay loi "so
            // tham so khong khop".
            .execute(Tuple.of(conversationId, (Object) userIdArray))
            .toCompletionStage()
            .thenApply(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.getUUID("user_id"))
                .collect(Collectors.toUnmodifiableSet())));
    }

    public CompletionStage<Boolean> isMember(UUID conversationId, UUID userId) {
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2 LIMIT 1")
            .execute(Tuple.of(conversationId, userId))
            .toCompletionStage()
            .thenApply(rows -> rows.rowCount() > 0));
    }

    public CompletionStage<Set<UUID>> getMembers(UUID conversationId) {
        return supplier.executeReadOnly(conn -> conn.preparedQuery(
                "SELECT user_id FROM conversation_members WHERE conversation_id = $1")
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
                    + "WHERE cm.conversation_id IN (SELECT conversation_id FROM conversation_members WHERE user_id = $1) "
                    + "GROUP BY cm.conversation_id "
                    + "ORDER BY last_message_at DESC NULLS LAST")
            .execute(Tuple.of(userId))
            .toCompletionStage()
            .thenApply(rows -> {
                var result = new JsonArray();
                for (var row : rows) {
                    var memberIds = new JsonArray(Arrays.stream(row.getArrayOfUUIDs("member_ids")).map(UUID::toString).toList());
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
