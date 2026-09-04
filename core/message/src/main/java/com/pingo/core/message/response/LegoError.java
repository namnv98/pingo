package com.pingo.core.message.response;


import com.pingo.core.message.TransformingMessage;
import lombok.*;

import java.util.Map;
import java.util.function.Function;

public interface LegoError<BodyType> extends LegoResponse<BodyType> {

    @Builder
    static <T> LegoError<T> of(String error, T body) {
        return new DefaultLegoError<>(error, body);
    }

    @Builder
    static <T> LegoError<T> of(String error, T body, Map<String, String> header) {
        return new DefaultLegoError<>(error, body, header);
    }


    static <T> LegoError<T> of(String error) {
        return new DefaultLegoError<>(error, null);
    }

    @Builder
    static LegoError<Map<String, Object>> of(String error, @Singular Map<String, Object> items) {
        return new DefaultLegoError<>(error, items);
    }

    static LegoError<Map<String, Object>> of(String error, String key, Object value) {
        return new DefaultLegoError<>(error, Map.of(key, value));
    }

    static LegoError<Map<String, Object>> of(String error, String key1, Object value1, String key2, Object value2) {
        return new DefaultLegoError<>(error, Map.of(key1, value1, key2, value2));
    }

    String getError();

    default <T> LegoError<T> transform(Function<BodyType, T> translator) {
        return new TransformingError<>(this, translator);
    }
}

class TransformingError<BodyType, OutputType> extends TransformingMessage<BodyType, OutputType> implements LegoError<OutputType> {

    @Getter(value = AccessLevel.PROTECTED)
    private final @NonNull LegoError<BodyType> origin;

    protected TransformingError(LegoError<BodyType> origin, Function<BodyType, OutputType> transformer) {
        super(transformer);
        this.origin = origin;
    }

    @Override
    public String getError() {
        return origin.getError();
    }
}

@Getter
class DefaultLegoError<T> extends AbstractResponse<T> implements LegoError<T> {

    private final @NonNull String error;
    private final @NonNull T body;

    public DefaultLegoError(@NonNull String error, @NonNull T body, Map<String, String> headers) {
        super(headers);
        this.error = error;
        this.body = body;
    }

    public DefaultLegoError(@NonNull String error, @NonNull T body) {
        this.error = error;
        this.body = body;
    }
}