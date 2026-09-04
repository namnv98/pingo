package com.pingo.core.common.token;
import com.auth0.jwt.interfaces.Claim;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

public interface OptionalToken {

    static OptionalToken of(Token token) {
        return new OptionalTokenImpl(token);
    }

    Token getToken();

    default Optional<Claim> getClaim(String name) {
        return Optional.ofNullable(getToken().getClaim(name));
    }

    default Optional<String> getString(String name) {
        return Optional.ofNullable(getToken().getString(name));
    }

    default Optional<Boolean> getBoolean(String name) {
        return Optional.ofNullable(getToken().getBoolean(name));
    }

    default Optional<Integer> getInteger(String name) {
        return Optional.ofNullable(getToken().getInteger(name));
    }

    default Optional<Long> getLong(String name) {
        return Optional.ofNullable(getToken().getLong(name));
    }

    default Optional<Double> getDouble(String name) {
        return Optional.ofNullable(getToken().getDouble(name));
    }

    default Optional<UUID> getUUID(String name) {
        return Optional.ofNullable(getToken().getUUID(name));
    }

    default Optional<Instant> getEpochMilliAsInstant(String name) {
        return Optional.ofNullable(getToken().getEpochMilliAsInstant(name));
    }

    default Optional<Instant> getEpochSecondAsInstant(String name) {
        return Optional.ofNullable(getToken().getEpochSecondAsInstant(name));
    }
}

@Getter
@AllArgsConstructor
class OptionalTokenImpl implements OptionalToken {
    private final @NonNull Token token;
}
