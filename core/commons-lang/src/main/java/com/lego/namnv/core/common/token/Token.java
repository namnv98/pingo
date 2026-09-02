package com.lego.namnv.core.common.token;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Instant;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class Token {

    private final @NonNull DecodedJWT decodedJWT;

    private OptionalToken optional;

    public OptionalToken getOptional() {
        if (this.optional == null) {
            synchronized (this) {
                if (this.optional == null) this.optional = OptionalToken.of(this);
            }
        }
        return this.optional;
    }

    public TokenValidator toValidator() {
        return TokenValidator.builder().token(this).build();
    }

    public boolean contains(String name) {
        return decodedJWT.getClaim(name) != null;
    }

    public Claim getClaim(String name) {
        return decodedJWT.getClaim(name);
    }

    public UUID getUUID(String name) {
        var claim = getClaim(name).asString();
        if (claim == null) return null;
        return UUID.fromString(claim);
    }

    public Instant getEpochMilliAsInstant(String name) {
        var claim = getClaim(name).asLong();
        if (claim == null) return null;
        return Instant.ofEpochMilli(claim.longValue());
    }

    public Instant getEpochSecondAsInstant(String name) {
        var claim = getClaim(name).asLong();
        if (claim == null) return null;
        return Instant.ofEpochSecond(claim.longValue());
    }

    public String getString(String name) {
        return getClaim(name).asString();
    }

    public Boolean getBoolean(String name) {
        return getClaim(name).asBoolean();
    }

    public Integer getInteger(String name) {
        return getClaim(name).asInt();
    }

    public Long getLong(String name) {
        return getClaim(name).asLong();
    }

    public Double getDouble(String name) {
        return getClaim(name).asDouble();
    }

    public String[] getStringArray(String name) {
        return getClaim(name).asArray(String.class);
    }

    public Integer[] getInteterArray(String name) {
        return getClaim(name).asArray(Integer.class);
    }

    public Long[] getLongArray(String name) {
        return getClaim(name).asArray(Long.class);
    }

    public UUID[] getUUIDArray(String name) {
        var arr = getStringArray(name);
        UUID[] uuids = new UUID[arr.length];
        for (int i = 0; i < arr.length; i++) uuids[i] = UUID.fromString(arr[i]);
        return uuids;
    }

    public Instant[] getInstantArray(String name) {
        var arr = getLongArray(name);
        Instant[] instants = new Instant[arr.length];
        for (int i = 0; i < arr.length; i++) {
            var epochMilli = arr[i];
            instants[i] = epochMilli == null ? null : Instant.ofEpochMilli(epochMilli);
        }
        return instants;
    }
}
