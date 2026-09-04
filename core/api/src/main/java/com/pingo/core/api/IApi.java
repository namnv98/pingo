package com.pingo.core.api;


import java.util.concurrent.CompletionStage;

public interface IApi {

    CompletionStage<?> handle(IRequest request) throws Exception;

//    static IApi ofMethod(Method method, IContext context) {
//        return MethodReflectiveIApi.of(method, context);
//    }
//
//    static IApi ofMethod(Method method) {
//        return MethodReflectiveIApi.of(method, null);
//    }
}
