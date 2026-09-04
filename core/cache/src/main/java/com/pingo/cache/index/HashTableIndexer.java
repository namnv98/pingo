package com.pingo.cache.index;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.pingo.cache.CacheQuery;
import com.pingo.cache.Clearable;

public abstract class HashTableIndexer<I, K, V> implements CacheIndexer<K, V>, Clearable {

    private final Map<I, Set<K>> invertedTable = new ConcurrentHashMap<>();

    protected abstract I genIndexKey(K key, V value);

    protected abstract I genIndexKey(CacheQuery query);

    @Override
    public void index(K key, V value) {
        var invertedIndex = genIndexKey(key, value);
        if (invertedIndex == null)
            return;
        invertedTable.compute(invertedIndex, (k, v) -> {
            if (v == null)
                return Set.of(key);
            var set = new HashSet<>(v);
            set.add(key);
            return Collections.unmodifiableSet(set);
        });
    }

    @Override
    public void removeIndex(K key, V value) {
        var invertedIndex = genIndexKey(key, value);
        if (invertedIndex == null)
            return;
        invertedTable.computeIfPresent(invertedIndex, (k, v) -> {
            if (!v.contains(key))
                return v;

            var set = new HashSet<>(v);
            set.remove(key);
            return set.isEmpty() ? null : Collections.unmodifiableSet(set);
        });
    }

    @Override
    public Set<K> scan(CacheQuery query) {
        var indexKey = genIndexKey(query);
        var result = invertedTable.get(indexKey);
        return result == null ? Collections.emptySet() : result;
    }

    @Override
    public void clear() {
        invertedTable.clear();
    }

    protected boolean containsKey(I key) {
        return invertedTable.containsKey(key);
    }
}
