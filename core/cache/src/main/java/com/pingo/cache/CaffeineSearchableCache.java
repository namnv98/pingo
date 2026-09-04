package com.pingo.cache;

import static java.util.stream.Collectors.toMap;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.pingo.cache.aggregate.CacheAggregator;
import com.pingo.cache.index.CacheIndexer;

import lombok.Builder;
import lombok.Singular;

public class CaffeineSearchableCache<K, V> extends AbstractSearchableCache<K, V> {

    private final Cache<K, V> source;
    private final CacheDataProvider<K, V> dataProvider;

    @Builder
    protected CaffeineSearchableCache(//
            @Singular List<CacheIndexer<K, V>> indexers, //
            @Singular Map<String, CacheAggregator<K, V, ?>> aggregators, //
            CacheDataProvider<K, V> dataProvider, //
            Expiry<K, V> expiry) {
        super(indexers == null ? Collections.emptyList() : indexers,
                aggregators == null ? Collections.emptyMap() : aggregators);
        this.dataProvider = dataProvider;
        var sourceBuilder = Caffeine.newBuilder() //
                .executor(new SameThreadExecutor()) //
                .evictionListener(this::onEntryRemoved) //
                .removalListener(this::onEntryRemoved);
        if (expiry != null)
            sourceBuilder.expireAfter(expiry);
        this.source = sourceBuilder.build();
    }

    @Override
    public Set<K> keySet() {
        return Collections.unmodifiableSet(source.asMap().keySet());
    }

    @Override
    public Collection<V> values() {
        return Collections.unmodifiableCollection(source.asMap().values());
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return Collections.unmodifiableSet(source.asMap().entrySet());
    }

    @Override
    public Map<K, V> asMap() {
        return Collections.unmodifiableMap(source.asMap());
    }

    @Override
    public V get(K key) {
        return source.get(key, k -> {
            V v = null;
            if (dataProvider != null)
                v = dataProvider.load(k);
            if (v != null)
                reindex(k, v);
            return v;
        });
    }

    @Override
    public Map<K, V> getAll(Collection<K> keys) {
        return source.getAll(keys, ks -> {
            if (dataProvider != null) {
                var map = dataProvider.loadAll(ks);
                if (map != null) {
                    map.forEach(this::reindex);
                    return map;
                }
            }
            return Collections.emptyMap();
        });
    }

    @Override
    public Map<K, V> remove(Collection<K> keys) {
        if (keys == null)
            return null;
        if (keys.isEmpty())
            return Collections.emptyMap();
        return keys.stream() //
                .collect(toMap(k -> k, k -> remove(k)));
    }

    @Override
    public V remove(K key) {
        return source.asMap().remove(key);
    }

    private void onEntryRemoved(K key, V value, RemovalCause cause) {
        removeIndex(key, value);
    }

    @Override
    public Map<K, V> putAll(Map<K, V> data) {
        if (data == null || data.isEmpty())
            return Collections.emptyMap();

        var map = source.asMap();
        var old = data.keySet().stream() //
                .map(k -> {
                    var currValue = map.get(k);
                    if (currValue == null)
                        return null;
                    return Map.entry(k, currValue);
                }) //
                .filter(Objects::nonNull) //
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        map.putAll(data);
        data.forEach(this::reindex);
        return old;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return source.asMap().putIfAbsent(key, value);
    }

    @Override
    public void clear() {
        clearIndexers();
        source.asMap().clear();
    }

}
