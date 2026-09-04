package com.pingo.cache;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public interface CacheDataProvider<K, V> {

	V load(K key);

	default Map<K, V> loadAll(Iterable<? extends K> keys) {
		if (keys == null)
			return null;
		return StreamSupport.stream(keys.spliterator(), false) //
				.collect(Collectors.toMap(k -> k, this::load));
	}
}
