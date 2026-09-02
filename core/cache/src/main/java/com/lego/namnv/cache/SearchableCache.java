package com.lego.namnv.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public interface SearchableCache<K, V> extends Clearable {

    /**
     * put/replace all entry
     * 
     * @param data new mapped value
     * @return old mapped values
     */
    Map<K, V> putAll(Map<K, V> data);

    /**
     * put/replace new entry
     * 
     * @param key
     * @param value
     * @return old value
     */
    default V put(K key, V value) {
        return putAll(Map.of(key, value)) //
                .entrySet().stream() //
                .map(Map.Entry::getValue) //
                .findFirst() //
                .orElse(null);
    }

    V putIfAbsent(K key, V value);

    V get(K key);

    Map<K, V> getAll(Collection<K> keys);

    V remove(K key);

    Map<K, V> remove(Collection<K> keys);

    Map<K, V> search(CacheQuery query);

    Set<K> keySet();

    Collection<V> values();

    Set<Entry<K, V>> entrySet();

    Map<K, V> asMap();

    <D> D getAggregatedData(String aggregatorName);
}
