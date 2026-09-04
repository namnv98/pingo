package com.pingo.core.common.token;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator.Builder;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;

@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class TokenBuilder {

    private final Builder builder = JWT.create();

    private final Algorithm jwtAlgorithm;

    public TokenBuilder withArrayClaim(String name, String... array) {
        builder.withArrayClaim(name, array);
        return this;
    }

    public TokenBuilder withArrayClaim(String name, Integer... array) {
        builder.withArrayClaim(name, array);
        return this;
    }

    public TokenBuilder withArrayClaim(String name, Long... array) {
        builder.withArrayClaim(name, array);
        return this;
    }

    public TokenBuilder withArrayClaim(String name, UUID... uuids) {
        var strArr = new String[uuids.length];
        for (int i = 0; i < uuids.length; i++) {
            var uuid = uuids[i];
            strArr[i] = uuid == null ? null : uuid.toString();
        }
        return withArrayClaim(name, strArr);
    }

    public TokenBuilder withArrayClaim(String name, Instant... instants) {
        var arr = new Long[instants.length];
        for (int i = 0; i < instants.length; i++) {
            var instant = instants[i];
            arr[i] = instant == null ? null : instant.getEpochSecond();
        }
        return withArrayClaim(name, arr);
    }

    public TokenBuilder withClaim(String name, @NonNull UUID uuid) {
        builder.withClaim(name, uuid.toString());
        return this;
    }

    public TokenBuilder withClaim(String name, @NonNull String value) {
        builder.withClaim(name, value);
        return this;
    }

    public TokenBuilder withClaim(String name, int value) {
        builder.withClaim(name, value);
        return this;
    }

    public TokenBuilder withClaim(String name, long value) {
        builder.withClaim(name, value);
        return this;
    }

    public TokenBuilder withClaim(String name, double value) {
        builder.withClaim(name, value);
        return this;
    }

    public TokenBuilder withClaim(String name, boolean value) {
        builder.withClaim(name, value);
        return this;
    }

    public TokenBuilder withClaim(String name, Instant instant) {
        builder.withClaim(name, instant.getEpochSecond());
        return this;
    }

    public String build() {
        return builder.sign(jwtAlgorithm);
    }
}
