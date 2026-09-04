package com.pingo.core.http.config;


import com.pingo.core.common.support.ClasspathUtils;
import com.pingo.core.api.error.ErrorKeyMapper;
import com.pingo.core.api.error.RegisterErrorMapper;
import com.pingo.core.api.support.IConsts;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.Map.Entry;

import static com.pingo.core.common.support.ClasspathUtils.scanForAnnotatedFields;
import static com.pingo.core.common.support.ClasspathUtils.scanForAnnotatedTypes;

public interface HttpStatusErrorMapping extends Iterable<Entry<String, Integer>> {

    static HttpStatusErrorMapping scanAndCreate() {
        return scanAndCreate(IConsts.DEFAULT_SCANNED_PACKAGE);
    }

    static HttpStatusErrorMapping scanAndCreate(String packageName) {
        return ClasspathHttpStatusErrorMapping.scanAndCreate(packageName, IConsts.UNKNOWN_ERROR_CODE, IConsts.UNKNOWN_ERROR);
    }

    private static Map<String, Integer> invertMap(Map<Integer, Collection<String>> invertedMap) {
        Logger log = LoggerFactory.getLogger(HttpStatusErrorMapping.class);
        var map = new HashMap<String, Integer>();
        for (var e : invertedMap.entrySet()) {
            var statusCode = e.getKey();
            for (var errorKey : e.getValue()) {
                var oldValue = map.putIfAbsent(errorKey, statusCode);
                if (oldValue == null)
                    continue;

                if (oldValue >= statusCode) {
                    log.warn("Duplicated mapping on error key {}, using new (higher) value: {}", errorKey, statusCode);
                    map.put(errorKey, statusCode);
                    continue;
                }

                log.warn("Duplicated mapping on error key {}, skip new (lower) value {}", errorKey, statusCode);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    static HttpStatusErrorMapping ofInverted(Map<Integer, Collection<String>> invertedMap) {
        return of(invertMap(invertedMap));
    }

    static HttpStatusErrorMapping of(Map<String, Integer> map) {
        return new AbstractHttpStatusErrorMapping(map, 500, "com.lego.namnv.server.internal.error");
    }

    String getDefaultErrorKey();

    int lookup(String errorKey);

    boolean isMapped(String errorKey);

    default HttpStatusErrorMapping merge(Iterable<Entry<String, Integer>> iterable) {
        var map = new HashMap<String, Integer>();
        for (var e : iterable)
            map.put(e.getKey(), e.getValue());
        for (var e : this) {
            map.put(e.getKey(), e.getValue());
        }
        return of(map);
    }

}

class AbstractHttpStatusErrorMapping implements HttpStatusErrorMapping {

    private final @NonNull Map<String, Integer> map;
    private final int defaultStatusCode;

    @Getter
    private final String defaultErrorKey;

    public AbstractHttpStatusErrorMapping(Map<String, Integer> map, int defaultStatusCode, String defaultErrorKey) {
        this.map = Collections.unmodifiableMap(map);
        this.defaultStatusCode = defaultStatusCode;
        this.defaultErrorKey = defaultErrorKey;
    }

    @Override
    public int lookup(String errorKey) {
        return map.getOrDefault(errorKey, defaultStatusCode);
    }

    @Override
    public boolean isMapped(String errorKey) {
        return map.containsKey(errorKey);
    }

    @Override
    public Iterator<Entry<String, Integer>> iterator() {
        return map.entrySet().iterator();
    }

}

@Log4j2
class ClasspathHttpStatusErrorMapping extends AbstractHttpStatusErrorMapping {

    private ClasspathHttpStatusErrorMapping(Map<String, Integer> map, int defaultStatusCode, String defaultErrorKey) {
        super(map, defaultStatusCode, defaultErrorKey);
    }

    static ClasspathHttpStatusErrorMapping scanAndCreate(String packageName, int defCode, String defKey) {
        var map = new HashMap<String, Integer>();
        var aCls = RegisterErrorMapper.class;
        var reflections = ClasspathUtils.reflections(packageName);
        scanForAnnotatedFields(reflections, aCls, (field, a) -> addFieldErrorMeta(map, field, a));
        scanForAnnotatedTypes(reflections, aCls, true, (type, a) -> addTypeErrorMapper(map, type, a));
        log.debug("Error mapping size: {}", map.size());
        return new ClasspathHttpStatusErrorMapping(map, defCode, defKey);
    }

    private static void putMapper(Map<String, Integer> output, String key, int code) {
        var oldCode = output.putIfAbsent(key, code);
        if (oldCode == null)
            return;

        if (code > oldCode) {
            log.warn("Override mapped code for key {}, from {} -> {}", key, oldCode, code);
            output.put(key, code);
            return;
        }

        log.warn("Duplicate mapped code on key {}, keep old value {}, skip new value {}", key, oldCode, code);
    }

    @SneakyThrows
    private static void addFieldErrorMeta(Map<String, Integer> map, Field field, RegisterErrorMapper annotation) {
        if (!String.class.equals(field.getType()))
            log.warn("invalid @RegisterErrorMapper type {}, must String, skipped", field.getType().getName());

        if (!field.trySetAccessible())
            log.error("Cannot make accessible on field: {}.{}", field.getDeclaringClass().getName(), field.getName());

        var meta = (String) field.get(field);
        putMapper(map, meta, annotation.value());
    }

    @SneakyThrows
    private static void addTypeErrorMapper(Map<String, Integer> map, Class<?> type, RegisterErrorMapper annotation) {
        if (ErrorKeyMapper.class.isAssignableFrom(type)) {
            var mapper = (ErrorKeyMapper) type.getConstructor().newInstance();
            for (var e : mapper)
                putMapper(map, e.getKey(), e.getValue());
            return;
        }

        if (Throwable.class.isAssignableFrom(type)) {
            putMapper(map, type.getName(), annotation.value());
            return;
        }

        log.error("invalid Error key mapper registering on type: " + type);
    }

}
