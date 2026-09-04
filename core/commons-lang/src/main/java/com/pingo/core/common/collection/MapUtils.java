package com.pingo.core.common.collection;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

public class MapUtils {

    public static <K, V> MapBuilder<K, V> newBuilder(Map<K, V> holder) {
        return new MapBuilder<K, V>(holder);
    }

    public static <K, V> MapBuilder<K, V> newBuilder() {
        return newBuilder(new HashMap<K, V>());
    }

    public static <K, V> MapBuilder<K, V> newBuilder(K firstKey, V firstValue) {
        return MapUtils.<K, V>newBuilder().put(firstKey, firstValue);
    }

    public static <K, V> Map<K, V> mapOf(K key, V value) {
        var map = new HashMap<K, V>();
        map.put(key, value);
        return map;
    }

    public static <K, V> Map<K, V> mapOf(K key1, V value1, K key2, V value2) {
        var map = mapOf(key1, value1);
        map.put(key2, value2);
        return map;
    }

    public static <K, V> Map<K, V> mapOf(K key1, V value1, K key2, V value2, K key3, V value3) {
        var map = mapOf(key1, value1, key2, value2);
        map.put(key3, value3);
        return map;
    }

    public static <K, V> Map<K, V> mapOf(
            K key1, V value1, K key2, V value2, K key3, V value3, K key4, V value4) {
        var map = mapOf(key1, value1, key2, value2, key3, value3);
        map.put(key4, value4);
        return map;
    }

    public static <K, V> Map<K, V> mapOf(
            K key1, V value1, K key2, V value2, K key3, V value3, K key4, V value4, K key5, V value5) {
        var map = mapOf(key1, value1, key2, value2, key3, value3, key4, value4);
        map.put(key5, value5);
        return map;
    }

    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class MapBuilder<K, V> {

        private final @NonNull Map<K, V> map;

        public Map<K, V> build() {
            return map;
        }

        public Map<K, V> buildImmutable() {
            return Collections.unmodifiableMap(map);
        }

        public MapBuilder<K, V> put(K key, V value) {
            map.put(key, value);
            return this;
        }

        public MapBuilder<K, V> putAll(Map<K, V> data) {
            map.putAll(data);
            return this;
        }

        public MapBuilder<K, V> putIfAbsent(K key, V value) {
            map.putIfAbsent(key, value);
            return this;
        }

        @SuppressWarnings("unchecked")
        public MapBuilder<K, V> putSequence(Object... sequence) {
            if (sequence != null && sequence.length > 1)
                for (int i = 0; i < sequence.length - 1; i += 2)
                    map.put((K) sequence[i], (V) sequence[i + 1]);
            return this;
        }

        public MapBuilder<K, V> clear() {
            map.clear();
            return this;
        }

        public MapBuilder<K, V> remove(K key) {
            map.remove(key);
            return this;
        }

        public MapBuilder<K, V> remove(K key, V value) {
            map.remove(key, value);
            return this;
        }
    }
}
