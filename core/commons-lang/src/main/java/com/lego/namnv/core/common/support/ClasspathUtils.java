package com.lego.namnv.core.common.support;


import lombok.NonNull;
import org.reflections8.Reflections;
import org.reflections8.scanners.FieldAnnotationsScanner;
import org.reflections8.scanners.MethodAnnotationsScanner;
import org.reflections8.scanners.SubTypesScanner;
import org.reflections8.scanners.TypeAnnotationsScanner;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ClasspathUtils {
    private static final FieldAnnotationsScanner FIELD_ANNOTATIONS_SCANNER = new FieldAnnotationsScanner();
    private static final MethodAnnotationsScanner METHOD_ANNOTATIONS_SCANNER = new MethodAnnotationsScanner();
    private static final TypeAnnotationsScanner TYPE_ANNOTATIONS_SCANNER = new TypeAnnotationsScanner();
    private static final SubTypesScanner SUBTYPE_ANNOTATIONS_SCANNER = new SubTypesScanner();

    public ClasspathUtils() {
    }

    public static final synchronized Reflections reflections(Object... params) {
        ArrayList<Object> list = new ArrayList(Arrays.asList(params));
        list.add(FIELD_ANNOTATIONS_SCANNER);
        list.add(METHOD_ANNOTATIONS_SCANNER);
        list.add(TYPE_ANNOTATIONS_SCANNER);
        list.add(SUBTYPE_ANNOTATIONS_SCANNER);
        return new Reflections(list.toArray());
    }

    public static final <A extends Annotation> void scanForAnnotatedTypes(@NonNull String packageName, @NonNull Class<A> annotationType, boolean honorInherited, @NonNull BiConsumer<Class<?>, A> typeConsumer, ClassLoader... classLoaders) {
        if (packageName == null) {
            throw new NullPointerException("packageName is marked non-null but is null");
        } else if (annotationType == null) {
            throw new NullPointerException("annotationType is marked non-null but is null");
        } else if (typeConsumer == null) {
            throw new NullPointerException("typeConsumer is marked non-null but is null");
        } else {
            Reflections reflections = reflections(packageName, classLoaders);
            scanForAnnotatedTypes(reflections, annotationType, honorInherited, typeConsumer);
        }
    }

    public static final <A extends Annotation> void scanForAnnotatedTypes(@NonNull String packageName, @NonNull Class<A> annotationType, @NonNull BiConsumer<Class<?>, A> typeConsumer, ClassLoader... classLoaders) {
        if (packageName == null) {
            throw new NullPointerException("packageName is marked non-null but is null");
        } else if (annotationType == null) {
            throw new NullPointerException("annotationType is marked non-null but is null");
        } else if (typeConsumer == null) {
            throw new NullPointerException("typeConsumer is marked non-null but is null");
        } else {
            scanForAnnotatedTypes(packageName, annotationType, false, typeConsumer, classLoaders);
        }
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedTypes(Reflections reflections, Class<C> containerAnnotation, Class<A> elementAnnotation, boolean honorInherited, BiConsumer<Class<?>, A[]> typeConsumer) {
        Set<Class<?>> types = reflections.getTypesAnnotatedWith(containerAnnotation, honorInherited);
        Iterator var6 = types.iterator();

        while(var6.hasNext()) {
            Class<?> type = (Class)var6.next();
            A[] annotations = type.getAnnotationsByType(elementAnnotation);
            typeConsumer.accept(type, annotations);
        }

    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedTypes(Reflections reflections, Class<C> containerAnnotation, Class<A> elementAnnotation, BiConsumer<Class<?>, A[]> typeConsumer) {
        scanForRepeatableAnnotatedTypes(reflections, containerAnnotation, elementAnnotation, false, typeConsumer);
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedTypes(Class<C> containerAnnotation, Class<A> elementAnnotation, BiConsumer<Class<?>, A[]> typeConsumer) {
        scanForRepeatableAnnotatedTypes(Full.scanned.reflections, containerAnnotation, elementAnnotation, false, typeConsumer);
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedTypes(Class<C> containerAnnotation, Class<A> elementAnnotation, boolean honorInherited, BiConsumer<Class<?>, A[]> typeConsumer) {
        scanForRepeatableAnnotatedTypes(Full.scanned.reflections, containerAnnotation, elementAnnotation, honorInherited, typeConsumer);
    }

    public static <A extends Annotation> void scanForAnnotatedTypes(Reflections reflections, Class<A> annotationType, boolean honorInherited, BiConsumer<Class<?>, A> typeConsumer) {
        Set<Class<?>> types = reflections.getTypesAnnotatedWith(annotationType, honorInherited);
        types.forEach((clz) -> {
            typeConsumer.accept(clz, clz.getAnnotation(annotationType));
        });
    }

    public static <A extends Annotation> void scanForAnnotatedTypes(Reflections reflections, Class<A> annotationType, BiConsumer<Class<?>, A> typeConsumer) {
        scanForAnnotatedTypes(reflections, annotationType, false, typeConsumer);
    }

    public static <A extends Annotation> void scanForAnnotatedTypes(Class<A> annotationType, boolean honorInherited, BiConsumer<Class<?>, A> typeConsumer) {
        scanForAnnotatedTypes(Full.scanned.reflections, annotationType, honorInherited, typeConsumer);
    }

    public static <A extends Annotation> void scanForAnnotatedTypes(Class<A> annotationType, BiConsumer<Class<?>, A> typeConsumer) {
        scanForAnnotatedTypes(annotationType, false, typeConsumer);
    }

    public static final <A extends Annotation> void scanForAnnotatedMethods(@NonNull String packageName, @NonNull Class<A> annotationType, @NonNull BiConsumer<Method, A> acceptor, ClassLoader... classLoaders) {
        if (packageName == null) {
            throw new NullPointerException("packageName is marked non-null but is null");
        } else if (annotationType == null) {
            throw new NullPointerException("annotationType is marked non-null but is null");
        } else if (acceptor == null) {
            throw new NullPointerException("acceptor is marked non-null but is null");
        } else {
            Reflections reflections = reflections(packageName, classLoaders);
            scanForAnnotatedMethods(reflections, annotationType, acceptor);
        }
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedMethods(Reflections reflections, Class<C> containerAnnotationType, Class<A> elementAnnotationType, BiConsumer<Method, A[]> acceptor) {
        Set<Method> methods = reflections.getMethodsAnnotatedWith(containerAnnotationType);
        methods.forEach((method) -> {
            acceptor.accept(method, method.getAnnotationsByType(elementAnnotationType));
        });
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedMethods(Class<C> containerAnnotationType, Class<A> elementAnnotationType, BiConsumer<Method, A[]> acceptor) {
        scanForRepeatableAnnotatedMethods(Full.scanned.reflections, containerAnnotationType, elementAnnotationType, acceptor);
    }

    public static <A extends Annotation> void scanForAnnotatedMethods(Reflections reflections, Class<A> annotationType, BiConsumer<Method, A> acceptor) {
        Set<Method> methods = reflections.getMethodsAnnotatedWith(annotationType);
        methods.forEach((method) -> {
            acceptor.accept(method, method.getAnnotation(annotationType));
        });
    }

    public static <A extends Annotation> void scanForAnnotatedClass(Reflections reflections, Class<A> annotationType, BiConsumer<Method, A> acceptor) {
        Set<Method> methods = reflections.getMethodsAnnotatedWith(annotationType);
        methods.forEach((method) -> {
            acceptor.accept(method, method.getAnnotation(annotationType));
        });
    }

    public static <A extends Annotation> void scanForAnnotatedMethods(Class<A> annotationType, BiConsumer<Method, A> acceptor) {
        scanForAnnotatedMethods(Full.scanned.reflections, annotationType, acceptor);
    }

    public static final <A extends Annotation> void scanForAnnotatedConstructors(@NonNull Reflections reflections, @NonNull Class<A> annotationType, @NonNull BiConsumer<Constructor, A> acceptor) {
        if (reflections == null) {
            throw new NullPointerException("reflections is marked non-null but is null");
        } else if (annotationType == null) {
            throw new NullPointerException("annotationType is marked non-null but is null");
        } else if (acceptor == null) {
            throw new NullPointerException("acceptor is marked non-null but is null");
        } else {
            Set<Constructor> ctors = reflections.getConstructorsAnnotatedWith(annotationType);
            Iterator var4 = ctors.iterator();

            while(var4.hasNext()) {
                Constructor ctor = (Constructor)var4.next();
                A annotation = (A) ctor.getAnnotation(annotationType);
                acceptor.accept(ctor, annotation);
            }

        }
    }

    public static final <A extends Annotation> void scanForAnnotatedFields(@NonNull String packageName, @NonNull Class<A> annotationType, @NonNull BiConsumer<Field, A> acceptor, ClassLoader... classLoaders) {
        if (packageName == null) {
            throw new NullPointerException("packageName is marked non-null but is null");
        } else if (annotationType == null) {
            throw new NullPointerException("annotationType is marked non-null but is null");
        } else if (acceptor == null) {
            throw new NullPointerException("acceptor is marked non-null but is null");
        } else {
            Reflections reflections = reflections(packageName, classLoaders);
            scanForAnnotatedFields(reflections, annotationType, acceptor);
        }
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedFields(Reflections reflections, Class<C> containerAnnotationType, Class<A> annotationType, BiConsumer<Field, A[]> acceptor) {
        Set<Field> fields = reflections.getFieldsAnnotatedWith(containerAnnotationType);
        fields.forEach((field) -> {
            acceptor.accept(field, field.getAnnotationsByType(annotationType));
        });
    }

    public static <C extends Annotation, A extends Annotation> void scanForRepeatableAnnotatedFields(Class<C> containerAnnotationType, Class<A> annotationType, BiConsumer<Field, A[]> acceptor) {
        scanForRepeatableAnnotatedFields(Full.scanned.reflections, containerAnnotationType, annotationType, acceptor);
    }

    public static <A extends Annotation> void scanForAnnotatedFields(Reflections reflections, Class<A> annotationType, BiConsumer<Field, A> acceptor) {
        Set<Field> fields = reflections.getFieldsAnnotatedWith(annotationType);
        fields.forEach((field) -> {
            acceptor.accept(field, field.getAnnotation(annotationType));
        });
    }

    public static <A extends Annotation> void scanForAnnotatedFields(Class<A> annotationType, BiConsumer<Field, A> acceptor) {
        scanForAnnotatedFields(Full.scanned.reflections, annotationType, acceptor);
    }

    public static final <Type> void scanForSubTypes(@NonNull String packageName, @NonNull Class<Type> superType, @NonNull Consumer<Class<? extends Type>> acceptor, ClassLoader... classLoaders) {
        if (packageName == null) {
            throw new NullPointerException("packageName is marked non-null but is null");
        } else if (superType == null) {
            throw new NullPointerException("superType is marked non-null but is null");
        } else if (acceptor == null) {
            throw new NullPointerException("acceptor is marked non-null but is null");
        } else {
            Reflections reflections = reflections(packageName, classLoaders);
            scanForSubTypes(reflections, superType, acceptor);
        }
    }

    public static <Type> void scanForSubTypes(Reflections reflections, Class<Type> superType, Consumer<Class<? extends Type>> acceptor) {
        Set<Class<? extends Type>> types = reflections.getSubTypesOf(superType);
        types.forEach(acceptor);
    }

    public static <Type> void scanForSubTypes(Class<Type> superType, Consumer<Class<? extends Type>> acceptor) {
        scanForSubTypes(Full.scanned.reflections, superType, acceptor);
    }

    private static enum Full {
        scanned;

        private final Reflections reflections = ClasspathUtils.reflections(System.getProperty("reflections.full.scan.root", "com.lego"));

        private Full() {
        }
    }
}

