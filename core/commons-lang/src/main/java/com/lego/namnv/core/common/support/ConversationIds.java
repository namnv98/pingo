package com.lego.namnv.core.common.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ConversationIds {

    /**
     * Id tat dinh (deterministic) cho 1 cuoc tro chuyen 1-1 giua 2 user, tinh bang XOR tung nua
     * UUID cua 2 ben -- doi xung (dmId(a,b) == dmId(b,a)), khong can sort truoc, va de port sang
     * JS (BigInt) cho demo.html. Ca harbor va colony phai dung CHUNG ham nay (khong duplicate nhu
     * cac DTO khac) vi day la 1 thuat toan 2 phia bat buoc phai khop bit-for-bit.
     */
    public static UUID dmId(UUID userIdA, UUID userIdB) {
        return new UUID(
            userIdA.getMostSignificantBits() ^ userIdB.getMostSignificantBits(),
            userIdA.getLeastSignificantBits() ^ userIdB.getLeastSignificantBits());
    }
}
