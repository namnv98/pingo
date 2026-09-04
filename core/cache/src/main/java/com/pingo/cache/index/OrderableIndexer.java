package com.pingo.cache.index;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

import com.pingo.cache.CacheQuery;
import com.pingo.cache.index.OrderableIndexer.RangeQuery.BetweenQuery;
import com.pingo.cache.index.OrderableIndexer.RangeQuery.EqualsQuery;
import com.pingo.cache.index.OrderableIndexer.RangeQuery.HigherQuery;
import com.pingo.cache.index.OrderableIndexer.RangeQuery.LowerQuery;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

public abstract class OrderableIndexer<I, K, V> implements CacheIndexer<K, V> {

    public static interface RangeQuery<T> extends CacheQuery {

        @Getter
        @AllArgsConstructor(access = AccessLevel.PROTECTED)
        static class LowerQuery<T> implements RangeQuery<T> {
            private final T upper;
            private final boolean inclusive;
        }

        @Getter
        @AllArgsConstructor(access = AccessLevel.PROTECTED)
        static class HigherQuery<T> implements RangeQuery<T> {
            private final T lower;
            private final boolean inclusive;
        }

        @Getter
        @AllArgsConstructor(access = AccessLevel.PROTECTED)
        static class BetweenQuery<T> implements RangeQuery<T> {
            private final T lower;
            private final boolean includeLower;
            private final T upper;
            private final boolean includeUpper;
        }

        @Getter
        @AllArgsConstructor(access = AccessLevel.PROTECTED)
        static class EqualsQuery<T> implements RangeQuery<T> {
            private final T value;
        }

    }

    private final NavigableMap<I, Set<K>> orderedMap = new ConcurrentSkipListMap<>(this::compare);

    protected abstract I genIndexKey(K key, V value);

    protected abstract int compare(I o1, I o2);

    @Override
    public void index(K key, V value) {
        I indexKey = genIndexKey(key, value);
        if (indexKey == null)
            return;
        orderedMap.compute(indexKey, (__, curr) -> {
            if (curr == null)
                return Set.of(key);
            var set = new HashSet<>(curr);
            set.add(key);
            return Collections.unmodifiableSet(set);
        });
    }

    @Override
    public void removeIndex(K key, V value) {
        I indexKey = genIndexKey(key, value);
        if (indexKey == null)
            return;
        orderedMap.computeIfPresent(indexKey, (__, curr) -> {
            if (!curr.contains(key))
                return curr;
            var set = new HashSet<>(curr);
            set.remove(key);
            return set.isEmpty() ? null : Collections.unmodifiableSet(set);
        });
    }

    protected NavigableMap<I, Set<K>> headMap(I indexKey, boolean inclusive) {
        return orderedMap.headMap(indexKey, inclusive);
    }

    protected NavigableMap<I, Set<K>> tailMap(I indexKey, boolean inclusive) {
        return orderedMap.tailMap(indexKey, inclusive);
    }

    protected NavigableMap<I, Set<K>> subMap(I lower, boolean includeLower, I upper, boolean includeUpper) {
        return orderedMap.subMap(lower, includeLower, upper, includeUpper);
    }

    protected Set<K> get(I indexKey) {
        if (!orderedMap.containsKey(indexKey))
            return Collections.emptySet();
        return orderedMap.get(indexKey);
    }

    protected Set<K> getLower(I upper, boolean inclusive) {
        return headMap(upper, inclusive) //
                .values() //
                .stream() //
                .flatMap(Collection::stream) //
                .collect(Collectors.toUnmodifiableSet());
    }

    protected Set<K> getHigher(I lower, boolean inclusive) {
        return tailMap(lower, inclusive) //
                .values() //
                .stream() //
                .flatMap(Collection::stream) //
                .collect(Collectors.toUnmodifiableSet());
    }

    protected Set<K> getBetween(I lower, boolean includeLower, I upper, boolean includeUpper) {
        return subMap(lower, includeLower, upper, includeUpper) //
                .values() //
                .stream() //
                .flatMap(Collection::stream) //
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean acceptQuery(CacheQuery query) {
        return query instanceof RangeQuery<?>;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<K> scan(CacheQuery query) {
        if (query instanceof EqualsQuery)
            return get(((EqualsQuery<I>) query).getValue());

        if (query instanceof HigherQuery) {
            var bound = (HigherQuery<I>) query;
            return getHigher(bound.getLower(), bound.isInclusive());
        }

        if (query instanceof LowerQuery) {
            var bound = (LowerQuery<I>) query;
            return getLower(bound.getUpper(), bound.isInclusive());
        }

        if (query instanceof BetweenQuery) {
            var between = (BetweenQuery<I>) query;
            return getBetween(between.getLower(), between.isIncludeLower(), between.getUpper(),
                    between.isIncludeUpper());
        }

        return null;
    }
}
