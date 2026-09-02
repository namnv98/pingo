package com.lego.namnv.core.api.decoration.request;

import com.lego.namnv.core.api.IRequest;
import com.lego.namnv.core.api.decoration.Decorator;

import java.util.concurrent.CompletionStage;

public interface RequestDecorator<T extends IRequest> extends Decorator<T> {

    CompletionStage<? extends IRequest> decorateRequest(T request);
}
