package com.lego.namnv.core.api.isolated;

import com.lego.namnv.core.common.support.Classpath;
import com.lego.namnv.core.api.IApiKey;
import com.lego.namnv.core.api.IRequest;
import com.lego.namnv.core.api.OrgHandlerProvider;
import com.lego.namnv.core.api.annotaion.RegisterIsolatedHandler;
import com.lego.namnv.core.api.error.DuplicateOrgIsolatedHandlerException;
import lombok.NonNull;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public interface HandlerProviderIsolatedRegistry {

    static HandlerProviderIsolatedRegistry scanClasspath(String packageName) {
        return new ClasspathIsolatedHandlerIsolatedRegistry(packageName);
    }

    Set<IApiKey> getAllKeys();

    OrgHandlerProvider getProvider(IRequest request);
}

class BaseIsolatedHandlerIsolatedRegistry implements HandlerProviderIsolatedRegistry {

    private final @NonNull Map<IApiKey, OrgHandlerProvider> registry = new ConcurrentHashMap<>();

    protected void register(IApiKey key, OrgHandlerProvider handler) {
        var old = registry.putIfAbsent(key, handler);
        if (old != null) {
            throw new DuplicateOrgIsolatedHandlerException(key, old);
        }
    }

    @Override
    public Set<IApiKey> getAllKeys() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    @Override
    public OrgHandlerProvider getProvider(IRequest request) {
        return registry.get(request.getApiKey());
    }
}

class ClasspathIsolatedHandlerIsolatedRegistry extends BaseIsolatedHandlerIsolatedRegistry {

    ClasspathIsolatedHandlerIsolatedRegistry(String packageName) {
        Classpath.scanFor(packageName).annotatedMethods(RegisterIsolatedHandler.class, this::registerByType);
    }

    private void registerByType(Method method, RegisterIsolatedHandler annotation) {
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