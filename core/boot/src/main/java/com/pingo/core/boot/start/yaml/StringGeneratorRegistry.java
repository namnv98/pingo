package com.pingo.core.boot.start.yaml;

import com.pingo.core.boot.start.yaml.ex.DuplicateStringGeneratorException;
import com.pingo.core.common.support.Classpath;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
public class StringGeneratorRegistry {

    @Getter private static final StringGeneratorRegistry instance = new StringGeneratorRegistry();

    private final Map<String, StringGenerator> generators = new ConcurrentHashMap<>();

    private StringGeneratorRegistry() {
        Classpath.scanFor().annotatedMethods(RegisterStringGenerator.class, this::acceptMethod);
        Classpath.scanFor().annotatedTypes(RegisterStringGenerator.class, this::acceptType);
        log.info("Registered {} string generator(s)", generators.size());
    }

    public StringGenerator lookup(String name) {
        return generators.get(name);
    }

    private void acceptMethod(Method method, RegisterStringGenerator annotation) {
        var generator = new ReflectiveMethodStringGenerator(method);
        register(generator, annotation.value());
    }

    @SneakyThrows
    private StringGenerator newGeneratorFromClass(Class<?> type) {
        return (StringGenerator) type.getConstructor().newInstance();
    }

    private void acceptType(Class<?> type, RegisterStringGenerator annotation) {
        var generator = newGeneratorFromClass(type);
        register(generator, annotation.value());
    }

    private void register(StringGenerator generator, String... names) {
        for (var name : names) {
            var old = generators.putIfAbsent(name, generator);
            if (old != null) throw new DuplicateStringGeneratorException(name, old);
            log.info("registered string generator: {} -> {}", name, generator);
        }
    }
}

@AllArgsConstructor
class ReflectiveMethodStringGenerator implements StringGenerator {

    private final @NonNull Method method;

    @Override
    @SneakyThrows
    public String generate() {
        return (String) method.invoke(null);
    }

    @Override
    public String toString() {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }
}
