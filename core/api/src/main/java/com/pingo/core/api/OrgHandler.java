package com.pingo.core.api;

import java.util.concurrent.CompletionStage;

public interface OrgHandler<T extends IRequest> {

    CompletionStage<?> handle(T request);
}
