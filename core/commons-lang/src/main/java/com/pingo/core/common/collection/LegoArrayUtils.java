package com.pingo.core.common.collection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class LegoArrayUtils {

    public static <I, K, V> Map<K, V> transformToMap(I[] elements, Function<I, Map.Entry<K, V>> transformEntry) {
        if (isEmpty(elements)) {
            return Map.of();
        }
        var result = new HashMap<K, V>(elements.length);
        for (var element : elements) {
            var entry = transformEntry.apply(element);
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public static <I, O> List<O> transformToList(I[] objects, Function<I, O> transform) {
        if (isEmpty(objects)) {
            return List.of();
        }
        var result = new ArrayList<O>(objects.length);
        for (var object : objects) {
            result.add(transform.apply(object));
        }
        return result;
    }

    public static <I> boolean isEmpty(I[] arr) {
        return arr == null || arr.length == 0;
    }

}
