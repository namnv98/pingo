package com.lego.namnv.core.api.registry;//package com.lego.namnv.core.registry;
//
//import com.google.inject.Injector;
//import com.lego.namnv.common.support.Classpath;
//import com.lego.namnv.core.api.IApiKey;
//import com.lego.namnv.core.api.IBatchApi;
//import com.lego.namnv.core.api.OrgHandler;
//import com.lego.namnv.core.api.OrgHandlerProvider;
//import com.lego.namnv.core.api.annotaion.RegisterIApi;
//import com.lego.namnv.core.api.annotaion.RegisterIBatchApi;
//import com.lego.namnv.core.api.annotaion.Type;
//import com.lego.namnv.core.message.request.IRequest;
//import lombok.NonNull;
//
//import java.lang.reflect.Method;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.ConcurrentHashMap;
//
//public interface IApiRegistry1 {
//
//    OrgHandlerProvider lookup(IRequest request);
//
//    Map<IApiKey, OrgHandlerProvider> lookup(Type request);
//
//    Set<IApiKey> getAllKey();
//
//    OrgHandlerProvider lookup(IApiKey key);
//
//    static IApiRegistry1 scanClasspath(String packageName, Injector injector) {
//        return new ClasspathIApiRegistry(packageName, injector);
//    }
//
//    class AbstractIApiRegistry implements IApiRegistry1 {
//
//        private final @NonNull Map<IApiKey, OrgHandlerProvider> registry = new ConcurrentHashMap<>();
//
//        @Override
//        public OrgHandlerProvider lookup(IRequest request) {
//            return registry.get(request.getApiKey());
//        }
//
//        @Override
//        public Map<IApiKey, OrgHandlerProvider> lookup(Type type) {
//            Map<IApiKey, OrgHandlerProvider> result = new HashMap<>();
//            registry.forEach((iApiKey, orgHandlerProvider) -> {
//                if (iApiKey.getType().equals(type)) {
//                    result.put(iApiKey, orgHandlerProvider);
//                }
//            });
//            return result;
//        }
//
//        @Override
//        public OrgHandlerProvider lookup(IApiKey key) {
//            return registry.get(key);
//        }
//
//        @Override
//        public Set<IApiKey> getAllKey() {
//            return registry.keySet();
//        }
//
//        protected final void register(IApiKey key, OrgHandlerProvider provider) {
//            var old = registry.putIfAbsent(key, provider);
////            if (old != null)
////                throw new DuplicatedOrgHandlerException(key, old);
//        }
//    }
//
//    class ClasspathIApiRegistry extends AbstractIApiRegistry {
//        final Injector injector;
//
//        private ClasspathIApiRegistry(String packageName, Injector injector) {
//            this.injector = injector;
//            Classpath.scanFor(packageName).annotatedMethods(RegisterIApi.class, this::registerByMethod);
//            Classpath.scanFor(packageName).annotatedTypes(RegisterIBatchApi.class, (t, a) -> registerBatchApi(this.injector, t));
//        }
//
//        private void registerBatchApi(Injector injector, Class<?> type) {
//            if (!IBatchApi.class.isAssignableFrom(type))
//                throw new RuntimeException("IllegalBatchApiException(type)");
//            var a = OrgHandlerProvider.ofType(type, null);
//            var api = (OrgHandler) injector.getInstance(type);
//            var keys = ((IBatchApi) api).getRegisterApiKeys();
//            for (var key : keys) {
//                register(key, a);
//            }
//        }
//
//        private void registerByMethod(Method method, RegisterIApi annotation) {
//            var provider = OrgHandlerProvider.ofMethod(method, null);
//            var key = IApiKey.of(annotation.version(), annotation.method(), annotation.endpoint(), annotation.rpc(), annotation.type());
//            register(key, provider);
//        }
//    }
//}
