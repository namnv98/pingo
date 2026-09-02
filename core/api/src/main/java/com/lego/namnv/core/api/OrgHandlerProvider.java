package com.lego.namnv.core.api;

import com.google.inject.Injector;

import com.lego.namnv.core.api.decoration.dummy.DummyErrorDecorator;
import com.lego.namnv.core.api.decoration.dummy.DummyRequestDecorator;
import com.lego.namnv.core.api.decoration.dummy.DummyResponseDecorator;
import com.lego.namnv.core.api.decoration.error.ErrorDecorator;
import com.lego.namnv.core.api.decoration.request.RequestDecorator;
import com.lego.namnv.core.api.decoration.response.ResponseDecorator;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.concurrent.CompletableFuture.completedStage;

public interface OrgHandlerProvider {

    <T extends IRequest> OrgHandler<T> getHandler(Injector injector);

    static OrgHandlerProvider ofType(Class<?> type) {
        return new TypeReflectiveOrgHandlerProvider(type);
    }

    static OrgHandlerProvider ofType(Class<?> type, Class<? extends RequestDecorator<?>>[] requestDecorators,
                                     Class<? extends ResponseDecorator<?>>[] responseDecorators,
                                     Class<? extends ErrorDecorator> errorDecorator) {
        return new TypeReflectiveOrgHandlerProvider(type, requestDecorators, responseDecorators, errorDecorator);
    }

    static OrgHandlerProvider ofMethod(Method method, Class<? extends RequestDecorator<?>>[] requestDecorators,
                                       Class<? extends ResponseDecorator<?>>[] responseDecorators,
                                       Class<? extends ErrorDecorator> errorDecorator) {
        var modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers))
            return new StaticMethodOrgHandlerProvider(method, requestDecorators, responseDecorators, errorDecorator);
        return new InstanceMethodOrgHandlerProvider(method, requestDecorators, responseDecorators, errorDecorator);
    }
}

@SuppressWarnings({"unchecked", "rawtypes"})
abstract class AbstractOrgHandlerProvider implements OrgHandlerProvider {

    private final Class<? extends RequestDecorator<?>>[] requestDecorators;
    private final Class<? extends ResponseDecorator<?>>[] responseDecorators;
    private final Class<? extends ErrorDecorator> errorDecorator;

    protected AbstractOrgHandlerProvider() {
        this.requestDecorators = null;
        this.responseDecorators = null;
        this.errorDecorator = null;
    }

    protected AbstractOrgHandlerProvider(//
                                         Class<? extends RequestDecorator<?>>[] requestDecorators, //
                                         Class<? extends ResponseDecorator<?>>[] responseDecorators, //
                                         Class<? extends ErrorDecorator> errorDecorator) {

        this.requestDecorators = rearrangeRequestDecorators(requestDecorators);
        this.responseDecorators = rearrangeResponseDecorators(responseDecorators);
        this.errorDecorator = (DummyErrorDecorator.class.isAssignableFrom(errorDecorator)) ? null : errorDecorator;
    }

    private final Class<? extends RequestDecorator<?>>[] rearrangeRequestDecorators(Class<? extends RequestDecorator<?>>[] input) {
        if (input == null || input.length == 0)
            return null;

        var list = new LinkedList<Class<? extends RequestDecorator<?>>>();
        for (var i = input.length - 1; i >= 0; i--) {
            var cls = input[i];
            if (cls == null || DummyRequestDecorator.class.isAssignableFrom(cls))
                continue;
            list.add(cls);
        }

        if (list.isEmpty())
            return null;

        return list.toArray(Class[]::new);
    }

    private final Class<? extends ResponseDecorator<?>>[] rearrangeResponseDecorators(
        Class<? extends ResponseDecorator<?>>[] input) {
        if (input == null || input.length == 0)
            return null;

        var list = new LinkedList<Class<? extends ResponseDecorator<?>>>();
        for (var i = 0; i < input.length; i++) {
            var cls = input[i];
            if (cls == null || DummyResponseDecorator.class.isAssignableFrom(cls))
                continue;
            list.add(cls);
        }

        if (list.isEmpty())
            return null;

        return list.toArray(Class[]::new);
    }

    protected abstract OrgHandler<?> createTargetHandler(Injector injector);

    @Override
    public OrgHandler<?> getHandler(Injector injector) {
        var handler = createTargetHandler(injector);
        handler = decorateRequest(injector, handler);
        handler = decorateResponse(injector, handler);
        handler = decorateError(injector, handler);
        return handler;
    }

    private OrgHandler<?> decorateRequest(Injector injector, OrgHandler<?> handler) {
        if (requestDecorators == null)
            return handler;

        for (var decoratorCls : requestDecorators) {
            var decorator = injector.getInstance(decoratorCls);
            handler = new DecoratedRequestOrgHandler(handler, decorator);
        }
        return handler;
    }

    private OrgHandler<?> decorateResponse(Injector injector, OrgHandler<?> handler) {
        if (responseDecorators == null)
            return handler;

        for (var decoratorCls : responseDecorators) {
            var decorator = injector.getInstance(decoratorCls);
            handler = new DecoratedResponseOrgHandler(handler, decorator);
        }
        return handler;
    }

    private OrgHandler<?> decorateError(Injector injector, OrgHandler<?> handler) {
        if (errorDecorator == null)
            return handler;
        var decorator = injector.getInstance(errorDecorator);
        return new DecoratedErrorOrgHandler(handler, decorator);
    }

    @AllArgsConstructor
    private static class DecoratedRequestOrgHandler implements OrgHandler<IRequest> {

        private final @NonNull OrgHandler target;
        private final @NonNull RequestDecorator decorator;

        @Override
        public CompletionStage<?> handle(IRequest request) {
            return decorator.decorateRequest(request) //
                .thenCompose(iedReq -> {
                    return target.handle((IRequest) iedReq);
                });
        }
    }

    @AllArgsConstructor
    private static class DecoratedResponseOrgHandler implements OrgHandler<IRequest> {

        private final @NonNull OrgHandler target;
        private final @NonNull ResponseDecorator decorator;

        @Override
        public CompletionStage<?> handle(IRequest request) {
            return target.handle(request) //
                .thenCompose(decorator::decorateResponse);
        }
    }

    @AllArgsConstructor
    private static class DecoratedErrorOrgHandler implements OrgHandler<IRequest> {

        private final @NonNull OrgHandler target;
        private final @NonNull ErrorDecorator decorator;

        private CompletionStage<Object> execute(IRequest request) {
            try {
                return target.handle(request);
            } catch (Throwable e) {
                return CompletableFuture.failedStage(e);
            }
        }

        @Override
        public CompletionStage<?> handle(IRequest request) {
            return execute(request) //
                .exceptionally(ex -> decorator.decorateError((Throwable) ex)) //
                .thenCompose(any -> (any instanceof CompletionStage cs) ? cs : completedStage(any));
        }
    }
}

final class TypeReflectiveOrgHandlerProvider extends AbstractOrgHandlerProvider {

    private final @NonNull Class<?> type;

    TypeReflectiveOrgHandlerProvider(Class<?> type) {
        super();
        this.type = type;
    }

    TypeReflectiveOrgHandlerProvider(Class<?> type, //
                                     Class<? extends RequestDecorator<?>>[] requestDecorators, //
                                     Class<? extends ResponseDecorator<?>>[] responseDecorators, //
                                     Class<? extends ErrorDecorator> errorDecorator) {

        super(requestDecorators, responseDecorators, errorDecorator);
        this.type = type;
    }

    @Override
    protected OrgHandler<?> createTargetHandler(Injector injector) {
        return (OrgHandler<?>) injector.getInstance(type);
    }
}

final class InstanceMethodOrgHandlerProvider extends AbstractOrgHandlerProvider {

    private final @NonNull Method method;

    InstanceMethodOrgHandlerProvider(Method method, //
                                     Class<? extends RequestDecorator<?>>[] requestDecorators, //
                                     Class<? extends ResponseDecorator<?>>[] responseDecorators, //
                                     Class<? extends ErrorDecorator> errorDecorator) {

        super(requestDecorators, responseDecorators, errorDecorator);
        if (!method.trySetAccessible())
            throw new IllegalArgumentException(
                "Method " + method.getDeclaringClass() + method.getName() + " cannot be accessible");
        this.method = method;
    }

    @Override
    @SneakyThrows
    protected OrgHandler<?> createTargetHandler(Injector injector) {
        var target = injector.getInstance(method.getDeclaringClass());
        return request -> invoke(target, request);
    }

    @SneakyThrows
    private <T extends IRequest> CompletionStage<?> invoke(Object obj, T request) {
        var result = method.invoke(obj, request);
        if (result instanceof CompletionStage<?> cs)
            return cs;
        return completedStage(result);
    }
}

final class StaticMethodOrgHandlerProvider extends AbstractOrgHandlerProvider {

    private final @NonNull Method method;

    StaticMethodOrgHandlerProvider(Method method, //
                                   Class<? extends RequestDecorator<?>>[] requestDecorators, //
                                   Class<? extends ResponseDecorator<?>>[] responseDecorators, //
                                   Class<? extends ErrorDecorator> errorDecorator) {

        super(requestDecorators, responseDecorators, errorDecorator);
        if (!method.trySetAccessible())
            throw new IllegalArgumentException(
                "Method " + method.getDeclaringClass() + method.getName() + " cannot be accessible");

        this.method = method;
    }

    @Override
    protected OrgHandler<?> createTargetHandler(Injector injector) {
        return this::invoke;
    }

    @SneakyThrows
    private <T extends IRequest> CompletionStage<?> invoke(T request) {
        var result = method.invoke(null, request);
        if (result instanceof CompletionStage<?> cs)
            return cs;
        return completedStage(result);
    }
}