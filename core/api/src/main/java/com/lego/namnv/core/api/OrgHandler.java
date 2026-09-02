package com.lego.namnv.core.api;

import java.util.concurrent.CompletionStage;

public interface OrgHandler<T extends IRequest> {

    CompletionStage<?> handle(T request);
}
