package com.pingo.cache.index;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.pingo.cache.CacheQuery;
import com.pingo.cache.Clearable;

import com.pingo.core.common.tuples.Pair;
import lombok.NonNull;

public abstract class RegexIndexer<K, V> implements CacheIndexer<K, V>, Clearable {

    private final Map<String, Pair<Set<K>, Predicate<String>>> invertedTable = new ConcurrentHashMap<>();

    protected String genRegex(@NonNull K key, V value) {
        return key.toString();
    }

    protected abstract String genQueryString(@NonNull CacheQuery query);

    @Override
    public void index(K key, V value) {
        var regex = genRegex(key, value);
        if (regex == null)
            return;
        invertedTable.compute(regex, (k, v) -> {
            if (v == null)
                return Pair.of(Set.of(key), Pattern.compile(regex).asMatchPredicate());

            var predicate = v.getSecond();
            var set = new HashSet<>(v.getFirst());
            set.add(key);
            return Pair.of(Collections.unmodifiableSet(set), predicate);
        });
    }

    @Override
    public void removeIndex(K key, V value) {
        var regex = genRegex(key, value);
        if (regex == null)
            return;
        invertedTable.computeIfPresent(regex, (k, v) -> {
            var keySet = v.getFirst();
            if (!keySet.contains(key))
                return v;

            var set = new HashSet<>(keySet);
            set.remove(key);
            return set.isEmpty() ? null : Pair.of(Collections.unmodifiableSet(set), v.getSecond());
        });
    }

    @Override
    public Set<K> scan(CacheQuery query) {
        var queryKey = genQueryString(query);
        Set<K> results = null;
        for (var e : invertedTable.values()) {
            if (e.getSecond().test(queryKey)) {
                if (results == null)
                    results = new HashSet<>();
                results.addAll(e.getFirst());
            }
        }
        return results == null ? Collections.emptySet() : results;
    }

    @Override
    public void clear() {
        invertedTable.clear();
    }

}
