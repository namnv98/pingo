package com.lego.namnv.core.api;

import com.lego.namnv.core.api.annotaion.ApiMethod;
import com.lego.namnv.core.api.annotaion.Type;
import lombok.*;

public interface IApiKey {

    @Builder
    static IApiKey of(int version, ApiMethod method, String endpoint, boolean rpc, Type type) {
        return new SimpleApiKey(version, method, endpoint, rpc, type);
    }

    int getVersion();

    ApiMethod getMethod();

    String getEndpoint();

    boolean isRpc();

    Type getType();

}

@Getter
@ToString
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
class SimpleApiKey implements IApiKey {

    @EqualsAndHashCode.Include
    private final int version;
    @EqualsAndHashCode.Include
    private final @NonNull ApiMethod method;
    @EqualsAndHashCode.Include
    private final @NonNull String endpoint;
    private final @NonNull boolean rpc;
    private final @NonNull Type type;

}

