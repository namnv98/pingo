package com.lego.namnv.core.common.collection;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LegoMapUtils {

    public static <K, V> Map<K, V> filterKeys(Map<K, V> objectMap, Predicate<K> tester) {
        if (isEmpty(objectMap)) {
            return Map.of();
        }
        var result = new HashMap<K, V>(objectMap.size());
        for (var e : objectMap.entrySet()) {
            if (tester.test(e.getKey())) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    public static <K, I, O> Map<K, O> transform(Map<K, I> objectMap, Function<I, O> transform) {
        if (isEmpty(objectMap)) {
            return Map.of();
        }
        var result = new HashMap<K, O>(objectMap.size());
        for (var e : objectMap.entrySet()) {
            result.put(e.getKey(), transform.apply(e.getValue()));
        }
        return result;
    }

    public static <K, V, I, O> Map<I, O> transform(Map<K, V> objectMap, BiFunction<K, V, Map.Entry<I, O>> transformer) {
        if (Objects.isNull(objectMap) || objectMap.isEmpty()) {
            return Map.of();
        }
        var result = new HashMap<I, O>(objectMap.size());
        for (var e : objectMap.entrySet()) {
            var entry = transformer.apply(e.getKey(), e.getValue());
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static <K, I, O> List<O> transformValuesToList(Map<K, I> objectMap, Function<I, O> transform) {
        if (Objects.isNull(objectMap) || objectMap.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<O>(objectMap.size());
        for (var e : objectMap.entrySet()) {
            result.add(transform.apply(e.getValue()));
        }
        return result;
    }

    public static <K, I, O> List<O> transformEntryToList(Map<K, I> objectMap, BiFunction<K, I, O> transform) {
        if (Objects.isNull(objectMap) || objectMap.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<O>(objectMap.size());
        for (var e : objectMap.entrySet()) {
            result.add(transform.apply(e.getKey(), e.getValue()));
        }
        return result;
    }

    public static <K, I, O> Collection<O> transformValuesToCollection(Map<K, I> objectMap, Function<I, O> transform, Supplier<Collection<O>> newCollection) {
        var result = newCollection.get();
        if (Objects.isNull(objectMap) || objectMap.isEmpty()) {
            return result;
        }
        for (var e : objectMap.entrySet()) {
            result.add(transform.apply(e.getValue()));
        }
        return result;
    }

    public static <K, I> boolean isEmpty(Map<K, I> objectMap) {
        return Objects.isNull(objectMap) || objectMap.isEmpty();
    }

}
