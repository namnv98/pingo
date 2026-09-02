package com.lego.namnv.cache.index;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.lego.namnv.cache.CacheQuery;
import com.lego.namnv.cache.Clearable;

public abstract class InvertedIndexer<I, K, V> implements CacheIndexer<K, V>, Clearable {

    private final Map<I, Set<K>> invertedTable = new ConcurrentHashMap<>();

    protected abstract Set<I> genIndexKey(K key, V value);

    protected abstract Set<I> genIndexKey(CacheQuery query);

    @Override
    public void index(K key, V value) {
        var invertedIndexes = genIndexKey(key, value);
        if (invertedIndexes == null)
            return;
        for (I i : invertedIndexes)
            invertedTable.compute(i, (__, curr) -> {
                if (curr == null)
                    return Set.of(key);
                var set = new HashSet<>(curr);
                set.add(key);
                return Collections.unmodifiableSet(set);
            });
    }

    @Override
    public void removeIndex(K key, V value) {
        var keys = genIndexKey(key, value);
        if (keys == null)
            return;
        for (var indexKey : keys) {
            invertedTable.computeIfPresent(indexKey, (__, curr) -> {
                if (!curr.contains(key))
                    return curr;
                var set = new HashSet<>(curr);
                set.remove(key);
                return set.isEmpty() ? null : Collections.unmodifiableSet(set);
            });
        }
    }

    @Override
    public Set<K> scan(CacheQuery query) {
        var indexKeys = genIndexKey(query);
        if (indexKeys == null || indexKeys.isEmpty())
            return Collections.emptySet();

        var result = new LinkedHashSet<K>();
        for (var k : indexKeys) {
            var set = invertedTable.get(k);
            if (set != null && !set.isEmpty())
                result.addAll(set);
        }

        return Collections.unmodifiableSet(result);
    }

    @Override
    public void clear() {
        invertedTable.clear();
    }
}
