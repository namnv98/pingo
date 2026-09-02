package com.lego.colony.ws.membership;

import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;

/**
 * Membership (ai thuoc conversation nao) -- luu BEN trong Postgres (bang conversation_members, xem
 * postgres/helm/templates/configmap.yaml), khong con chi song in-memory nhu ban dau (quyet dinh #2
 * luc doi sang conversation-sharding chi la tam hoan, khong phai vinh vien -- xem ARCHITECTURE.md
 * muc 11). Vi la DB dung chung (khong phai in-memory theo pod), MOI pod colony deu doc/ghi truc
 * tiep duoc, khong con rang buoc "chi pod owner cua conversationId moi biet membership" nhu truoc.
 */
@RequiredArgsConstructor
public class ChannelMembershipRegistry {

    private final Pool pool;

    /**
     * Union userIds vao membership, tra ve dung tap userId THUC SU MOI (chua tung co truoc do) --
     * caller dung tap nay de quyet dinh co can "danh thuc" (wake) ai khong. Dung 1 cau lenh
     * INSERT...SELECT unnest(...)...ON CONFLICT DO NOTHING RETURNING duy nhat (khong phai batch)
     * de chen nhieu dong trong 1 vong round-trip -- RETURNING chi tra ve dung nhung dong THAT SU
     * duoc chen, dong bi bo qua vi da ton tai (ON CONFLICT) khong xuat hien trong ket qua.
     */
    public CompletionStage<Set<UUID>> addMembers(UUID conversationId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return CompletableFuture.completedFuture(Set.of());
        }
        var userIdArray = userIds.toArray(new UUID[0]);
        return pool.preparedQuery(
                "INSERT INTO conversation_members (conversation_id, user_id) "
                    + "SELECT $1, unnest($2::uuid[]) "
                    + "ON CONFLICT DO NOTHING "
                    + "RETURNING user_id")
            // Ep kieu (Object) de trinh bien dich chon overload Tuple.of(Object, Object) co dinh
            // 2 tham so -- KHONG phai Tuple.of(Object, Object...) bien the (varargs), vi mang
            // UUID[] la array-covariant voi Object[], neu de bien dich tu chon no se "trai" mang
            // ra thanh nhieu phan tu rieng le (moi UUID 1 tham so) thay vi coi ca mang la 1 tham
            // so duy nhat cho unnest($2::uuid[]) -- gay dung loi "so tham so khong khop".
            .execute(Tuple.of(conversationId, (Object) userIdArray))
            .toCompletionStage()
            .thenApply(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.getUUID("user_id"))
                .collect(Collectors.toUnmodifiableSet()));
    }

    public CompletionStage<Boolean> isMember(UUID conversationId, UUID userId) {
        return pool.preparedQuery("SELECT 1 FROM conversation_members WHERE conversation_id = $1 AND user_id = $2 LIMIT 1")
            .execute(Tuple.of(conversationId, userId))
            .toCompletionStage()
            .thenApply(rows -> rows.rowCount() > 0);
    }

    public CompletionStage<Set<UUID>> getMembers(UUID conversationId) {
        return pool.preparedQuery("SELECT user_id FROM conversation_members WHERE conversation_id = $1")
            .execute(Tuple.of(conversationId))
            .toCompletionStage()
            .thenApply(rows -> StreamSupport.stream(rows.spliterator(), false)
                .map(row -> row.getUUID("user_id"))
                .collect(Collectors.toUnmodifiableSet()));
    }
}
