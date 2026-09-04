package com.pingo.cache;

import com.pingo.cache.aggregate.CacheAggregator;
import com.pingo.cache.index.CacheIndexer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@RequiredArgsConstructor
public abstract class AbstractSearchableCache<K, V> implements SearchableCache<K, V> {

    private @NonNull List<CacheIndexer<K, V>> indexers;

    private @NonNull Map<String, CacheAggregator<K, V, ?>> aggregators;

    @Override
    public Map<K, V> search(CacheQuery query) {
        var indexedKeys = scan(query);
        if (indexedKeys != null)
            return getAll(indexedKeys);
        return Collections.emptyMap();
    }

    private Set<K> scan(CacheQuery query) {
        if (query instanceof AndQuery and)
            return scanAndQuery(and);
        if (query instanceof OrQuery or)
            return scanOrQuery(or);
        if (query instanceof NotQuery not)
            return not(scan(not.getInner()));
        return scanLeafQuery(query);
    }

    private Set<K> not(Set<K> keys) {
        if (isNull(keys) || keys.isEmpty()) {
            return new HashSet<>(keySet());
        }
        var all = keySet();
        var result = new HashSet<K>(all.size());
        for (K k : all) {
            if (!keys.contains(k)) {
                result.add(k);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Set<K> scanLeafQuery(CacheQuery query) {
        if (query instanceof PrimaryQuery)
            return Set.of(((PrimaryQuery<K>) query).getKey());

        for (var indexer : indexers)
            if (indexer.acceptQuery(query)) {
                var indexedKeys = indexer.scan(query);
                if (indexedKeys == null)
                    continue;
                return indexedKeys;
            }

        throw new SearchableCacheException("no index found for query: " + query);
    }

    private Set<K> scanOrQuery(OrQuery orQuery) {
        return orQuery.getElements().stream() //
                .map(this::scan) //
                .filter(Objects::nonNull) //
                .flatMap(Set::stream) //
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<K> scanAndQuery(AndQuery andQuery) {
        var set = new HashSet<K>();
        var it = andQuery.getElements().iterator();
        while (it.hasNext()) {
            var keys = scan(it.next());
            if (keys == null)
                continue;
            
            if (keys.isEmpty())
                return Set.of();
            
            if (set.isEmpty())
                set.addAll(keys);
            else
                set.retainAll(keys);
        }

        return Collections.unmodifiableSet(set);

//        var list = andQuery.getElements().stream() //
//                .map(this::scan) //
//                .filter(Objects::nonNull) //
//                .collect(Collectors.toList());
//        if (list.isEmpty())
//            return Collections.emptySet();
//        var first = list.get(0);
//        if (list.size() == 1)
//            return first;
//        return list.stream().skip(1)//
//                .collect(() -> new HashSet<>(first), Set::retainAll, Set::retainAll);
    }

    protected void reindex(K key, V value) {
        for (var indexer : indexers)
            indexer.index(key, value);
        for (var aggregator : aggregators.values())
            aggregator.aggregate(key, value);
    }

    protected void removeIndex(K key, V value) {
        for (var indexer : indexers)
            indexer.removeIndex(key, value);
        for (var aggregator : aggregators.values())
            aggregator.removeAggregated(key, value);
    }

    protected void clearIndexers() {
        for (var indexer : indexers)
            if (indexer instanceof Clearable)
                ((Clearable) indexer).clear();
        for (var aggregator : aggregators.values())
            if (aggregator instanceof Clearable)
                ((Clearable) aggregator).clear();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <D> D getAggregatedData(String aggregatorName) {
        var aggregator = aggregators.get(aggregatorName);
        if (aggregator == null)
            return null;
        return (D) aggregator.getData();
    }
}
