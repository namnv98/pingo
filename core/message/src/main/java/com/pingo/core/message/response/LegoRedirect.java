package com.pingo.core.message.response;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.Map;

public interface LegoRedirect<T> extends LegoError<T> {

    @Builder
    static <T> LegoRedirect<T> of(String code, Map<String, String> headers) {
        return new DefaultLegoRedirect<>(code, headers);
    }
}


@Getter
class DefaultLegoRedirect<T> extends AbstractResponse<T> implements LegoRedirect<T> {

    private final @NonNull String error;

    public DefaultLegoRedirect(String error, Map<String, String> headers) {
        super(headers);
        this.error = error;
    }

    @Override
    public T getBody() {
        return null;
    }
}
