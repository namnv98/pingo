package com.pingo.cache.index;

import java.util.Set;

import com.pingo.cache.CacheQuery;

public interface CacheIndexer<K, V> {

	void index(K key, V value);

	void removeIndex(K key, V value);

	boolean acceptQuery(CacheQuery query);

	Set<K> scan(CacheQuery query);
}
