package com.lego.namnv.core.common.collection;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.*;
import java.util.function.*;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LegoCollectionUtil {

    public static <I, O> List<O> transform(Collection<I> objectCollections, Function<I, O> transformer) {
        if (LegoCollectionUtil.isEmpty(objectCollections)) {
            return List.of();
        }
        var result = new ArrayList<O>(objectCollections.size());
        for (var object : objectCollections) {
            result.add(transformer.apply(object));
        }
        return result;
    }

    public static <I, O> List<O> transform(Collection<I> objectCollections, Function<I, O> transformer, Predicate<O> predicate) {
        if (LegoCollectionUtil.isEmpty(objectCollections)) {
            return List.of();
        }
        var result = new ArrayList<O>(objectCollections.size());
        for (var element : objectCollections) {
            var object = transformer.apply(element);
            if (Objects.isNull(predicate) || predicate.test(object)) {
                result.add(object);
            }
        }
        return result;
    }

    public static <I, O> List<O> transform(Collection<I> objectCollections, @NonNull Predicate<I> predicateInput, @NonNull Function<I, O> transformer, @NonNull Predicate<O> predicateOutput) {
        if (LegoCollectionUtil.isEmpty(objectCollections)) {
            return List.of();
        }
        var result = new ArrayList<O>(objectCollections.size());
        for (var element : objectCollections) {
            if (!predicateInput.test(element)) {
                continue;
            }
            var object = transformer.apply(element);
            if (predicateOutput.test(object)) {
                result.add(object);
            }
        }
        return result;
    }

    public static <I, O> Set<O> transformToSet(Collection<I> objectCollections, Function<I, O> transform) {
        if (LegoCollectionUtil.isEmpty(objectCollections)) {
            return Set.of();
        }
        var result = new HashSet<O>(objectCollections.size());
        for (var object : objectCollections) {
            result.add(transform.apply(object));
        }
        return result;
    }

    public static <I, K, V> Map<K, V> transformToMap(Collection<I> collection, Supplier<Map<K, V>> constructor, Function<I, K> transformKey, Function<I, V> transformValue) {
        if (LegoCollectionUtil.isEmpty(collection)) {
            return Map.of();
        }
        var result = constructor.get();
        collection.forEach(element -> result.put(transformKey.apply(element), transformValue.apply(element)));
        return result;
    }

    public static <I, K, V> Map<K, V> transformToMap(Collection<I> collection, Function<I, K> transformKey, Function<I, V> transformValue) {
        return transformToMap(collection, () -> new HashMap<>(collection.size()), transformKey, transformValue);
    }

    public static <I, K, V> Map<K, V> transformToMap(Collection<I> collection, Function<I, Map.Entry<K, V>> transformEntry) {
        if (LegoCollectionUtil.isEmpty(collection)) {
            return Map.of();
        }
        var result = new HashMap<K, V>(collection.size());
        collection.forEach(element -> {
            var entry = transformEntry.apply(element);
            result.put(entry.getKey(), entry.getValue());
        });
        return result;
    }

    public static <I, O> O[] transformToArray(Collection<I> objects, Function<I, O> transform, IntFunction<O[]> constructor) {
        if (LegoCollectionUtil.isEmpty(objects)) {
            return constructor.apply(0);
        }
        var result = constructor.apply(objects.size());
        var index = 0;
        for (var object : objects) {
            result[index] = transform.apply(object);
            index++;
        }
        return result;
    }

    public static <T> List<T> addAll(Collection<T> list, T element) {
        var result = new ArrayList<T>(list.size() + 1);
        result.add(element);
        result.addAll(list);
        return result;
    }

    public static <I, O> O reduce(Collection<I> collection, O initValue, BiFunction<O, I, O> accumulator) {
        for (var element : collection) {
            initValue = accumulator.apply(initValue, element);
        }
        return initValue;
    }

    public static <I> I findFirstOrNull(Collection<I> collection, Predicate<I> predicate) {
        if (Objects.isNull(predicate)) {
            return null;
        }
        if (Objects.isNull(collection)) {
            return null;
        }
        for (var element : collection) {
            if (predicate.test(element)) {
                return element;
            }
        }
        return null;
    }

    public static <I> boolean anyMatch(Collection<I> collection, Predicate<I> predicate) {
        return Objects.nonNull(findFirstOrNull(collection, predicate));
    }

    public static <I> boolean isEmpty(Collection<I> objectCollections) {
        return objectCollections == null || objectCollections.isEmpty();
    }

}
