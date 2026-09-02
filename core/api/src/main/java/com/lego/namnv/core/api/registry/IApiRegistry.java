package com.lego.namnv.core.api.registry;

import com.google.inject.Injector;
import com.lego.namnv.core.common.support.Classpath;
import com.lego.namnv.core.api.IApiKey;
import com.lego.namnv.core.api.IBatchApi;
import com.lego.namnv.core.api.IRequest;
import com.lego.namnv.core.api.OrgHandlerProvider;
import com.lego.namnv.core.api.annotaion.RegisterHandler;
import com.lego.namnv.core.api.annotaion.RegisterIBatchApi;
import com.lego.namnv.core.api.annotaion.Type;
import lombok.NonNull;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public interface IApiRegistry {

    OrgHandlerProvider lookup(IRequest request);

    Map<IApiKey, OrgHandlerProvider> lookup(Type request);

    Set<IApiKey> getAllKey();

    OrgHandlerProvider lookup(IApiKey key);

    static IApiRegistry scanClasspath(String packageName, Injector injector) {
        return new ClasspathIApiRegistry(packageName, injector);
    }

    class AbstractIApiRegistry implements IApiRegistry {

        private final @NonNull Map<IApiKey, OrgHandlerProvider> registry = new ConcurrentHashMap<>();

        @Override
        public OrgHandlerProvider lookup(IRequest request) {
            return registry.get(request.getApiKey());
        }

        @Override
        public Map<IApiKey, OrgHandlerProvider> lookup(Type type) {
            Map<IApiKey, OrgHandlerProvider> result = new HashMap<>();
            registry.forEach((iApiKey, orgHandlerProvider) -> {
                if (iApiKey.getType().equals(type)) {
                    result.put(iApiKey, orgHandlerProvider);
                }
            });
            return result;
        }

        @Override
        public OrgHandlerProvider lookup(IApiKey key) {
            return registry.get(key);
        }

        @Override
        public Set<IApiKey> getAllKey() {
            return registry.keySet();
        }

        protected final void register(IApiKey key, OrgHandlerProvider provider) {
            var old = registry.putIfAbsent(key, provider);
//            if (old != null)
//                throw new DuplicatedOrgHandlerException(key, old);
        }
    }

    class ClasspathIApiRegistry extends AbstractIApiRegistry {
        final Injector injector;

        private ClasspathIApiRegistry(String packageName, Injector injector) {
            this.injector = injector;
            Classpath.scanFor(packageName).annotatedMethods(RegisterHandler.class, this::registerByMethod);
            Classpath.scanFor(packageName).annotatedTypes(RegisterIBatchApi.class, (t, a) -> registerBatchApi(this.injector, t));
        }

        private void registerBatchApi(Injector injector, Class<?> type) {
            if (!IBatchApi.class.isAssignableFrom(type))
                throw new RuntimeException("IllegalBatchApiException(type)");
            var a = OrgHandlerProvider.ofType(type);
            var api = injector.getInstance(type);
            var keys = ((IBatchApi) api).getRegisterApiKeys();
            for (var key : keys) {
                register(key, a);
            }
        }

        private void registerByMethod(Method method, RegisterHandler annotation) {
            var requestDecorators = annotation.onRequest();
            var responseDecorators = annotation.onResponse();
            var errorDecorator = annotation.onError();

            var apis = annotation.apis();
            for (var api : apis) {
                var provider = OrgHandlerProvider.ofMethod(method, requestDecorators, responseDecorators, errorDecorator);
                var key = IApiKey.of(api.version(), api.method(), api.endpoint(), api.rpc(), api.type());
                register(key, provider);
            }

        }
    }
}
