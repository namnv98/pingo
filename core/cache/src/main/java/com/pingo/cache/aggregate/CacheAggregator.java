package com.pingo.cache.aggregate;

public interface CacheAggregator<K, V, D> {

	void aggregate(K key, V value);

	void removeAggregated(K key, V value);

	D getData();
}
