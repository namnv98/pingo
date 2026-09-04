package com.pingo.core.common.support;


import lombok.Getter;
import lombok.NonNull;
import org.reflections8.Reflections;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class Classpath {
    String packageName;

    private Classpath(String packageName) {
        this.packageName = packageName;
    }

    private Classpath() {
    }

    public static Classpath scanFor(String packageName) {
        return new Classpath(packageName);
    }

    public static Classpath scanFor() {
        return new Classpath();
    }

    @Getter(lazy = true)
    private final @NonNull Reflections reflections = ClasspathUtils.reflections(packageName);

    public <A extends Annotation> void annotatedMethods(Class<A> annotationType, BiConsumer<Method, A> consumer) {
        ClasspathUtils.scanForAnnotatedMethods(getReflections(), annotationType, consumer);
    }

    @SuppressWarnings("rawtypes")
    public <A extends Annotation> void annotatedConstructors(Class<A> annotationType,
                                                             BiConsumer<Constructor, A> consumer) {
        ClasspathUtils.scanForAnnotatedConstructors(getReflections(), annotationType, consumer);
    }

    public <A extends Annotation> void annotatedTypes(Class<A> annotationType, BiConsumer<Class<?>, A> consumer) {
        ClasspathUtils.scanForAnnotatedTypes(getReflections(), annotationType, consumer);
    }

    public <A extends Annotation> void annotatedFields(Class<A> annotationType, BiConsumer<Field, A> consumer) {
        ClasspathUtils.scanForAnnotatedFields(getReflections(), annotationType, consumer);
    }

    public <T> void subTypes(Class<T> type, Consumer<Class<? extends T>> consumer) {
        ClasspathUtils.scanForSubTypes(type, consumer);
    }

    public <A extends Annotation> void annotatedFields(Class a, Class<A> annotationType, BiConsumer<Field, A> consumer) {
        Reflections reflections = ClasspathUtils.reflections(a.getName());
        ClasspathUtils.scanForAnnotatedFields(reflections, annotationType, consumer);
    }

    public <A extends Annotation> void annotatedMethods(Class a, Class<A> annotationType, BiConsumer<Method, A> consumer) {
        Reflections reflections = ClasspathUtils.reflections(a.getName());
        ClasspathUtils.scanForAnnotatedMethods(reflections, annotationType, consumer);
    }
}
