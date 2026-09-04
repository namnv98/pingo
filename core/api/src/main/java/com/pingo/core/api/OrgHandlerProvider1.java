package com.pingo.core.api;//package com.lego.namnv.core.api;
//
//import com.google.inject.Injector;
//import com.lego.namnv.core.api.IContext;
//import com.lego.namnv.core.message.request.IRequest;
//import lombok.NonNull;
//import lombok.SneakyThrows;
//
//import java.lang.reflect.Method;
//import java.lang.reflect.Modifier;
//import java.util.concurrent.CompletionStage;
//
//import static java.util.concurrent.CompletableFuture.completedStage;
//
//public interface OrgHandlerProvider {
//
//    <T extends IRequest> OrgHandler<T> get(Injector injector);
//
//    static OrgHandlerProvider ofType(Class<?> type, IContext context) {
//        return new TypeReflectiveOrgHandlerProvider(type, context);
//    }
//
//    static OrgHandlerProvider ofMethod(Method method, IContext context) {
//        var modifiers = method.getModifiers();
//        if (Modifier.isStatic(modifiers))
//            return new StaticMethodOrgHandlerProvider(method, context);
//        return new InstanceMethodOrgHandlerProvider(method, context);
//    }
//}
//
//@SuppressWarnings({"unchecked", "rawtypes"})
//abstract class AbstractOrgHandlerProvider implements OrgHandlerProvider {
//
//    protected AbstractOrgHandlerProvider() {
//    }
//
//    protected abstract OrgHandler<?> createTargetHandler(Injector injector);
//
//    @Override
//    public OrgHandler<?> get(Injector injector) {
//        return createTargetHandler(injector);
//    }
//}
//
//final class TypeReflectiveOrgHandlerProvider extends AbstractOrgHandlerProvider {
//
//    private final @NonNull Class<?> type;
//
//    TypeReflectiveOrgHandlerProvider(Class<?> type, IContext context) {
//        super();
//        this.type = type;
//    }
//
//    @Override
//    protected OrgHandler<?> createTargetHandler(Injector injector) {
//        return (OrgHandler<?>) injector.getInstance(type);
//    }
//}
//
//final class InstanceMethodOrgHandlerProvider extends AbstractOrgHandlerProvider {
//
//    private final @NonNull Method method;
//
//    InstanceMethodOrgHandlerProvider(Method method, IContext context) {
//        super();
//        if (!method.trySetAccessible())
//            throw new IllegalArgumentException(
//                "Method " + method.getDeclaringClass() + method.getName() + " cannot be accessible");
//        this.method = method;
//    }
//
//    @Override
//    @SneakyThrows
//    protected OrgHandler<?> createTargetHandler(Injector injector) {
//        var target = injector.getInstance(method.getDeclaringClass());
//        return request -> invoke(target, request);
//    }
//
//    @SneakyThrows
//    private <T extends IRequest> CompletionStage<?> invoke(Object obj, T request) {
//        var result = method.invoke(obj, request);
//        if (result instanceof CompletionStage<?> cs)
//            return cs;
//        return completedStage(result);
//    }
//}
//
//final class StaticMethodOrgHandlerProvider extends AbstractOrgHandlerProvider {
//
//    private final @NonNull Method method;
//
//    StaticMethodOrgHandlerProvider(Method method, IContext context) {
//        super();
//        if (!method.trySetAccessible())
//            throw new IllegalArgumentException(
//                "Method " + method.getDeclaringClass() + method.getName() + " cannot be accessible");
//
//        this.method = method;
//    }
//
//    @Override
//    protected OrgHandler<?> createTargetHandler(Injector injector) {
//        return this::invoke;
//    }
//
//    @SneakyThrows
//    private <T extends IRequest> CompletionStage<?> invoke(T request) {
//        var result = method.invoke(null, request);
//        if (result instanceof CompletionStage<?> cs)
//            return cs;
//        return completedStage(result);
//    }
//}
