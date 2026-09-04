package com.pingo.cache.aggregate;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import com.pingo.cache.Clearable;

import lombok.NonNull;

public abstract class SingleMappingSetAggregator<K, V, D> implements SetAggregator<K, V, D>, Clearable {

	private final @NonNull Map<D, Set<K>> dataCount = new ConcurrentHashMap<>();

	protected abstract D extract(K key, V value);

	@Override
	public void aggregate(K key, V value) {
		var e = extract(key, value);
		if (e != null)
			dataCount.computeIfAbsent(e, k -> new CopyOnWriteArraySet<K>()).add(key);
	}

	@Override
	public void removeAggregated(K key, V value) {
		var e = extract(key, value);
		if (e != null) {
			var keys = dataCount.get(e);
			if (keys != null)
				keys.remove(key);
		}
	}

	@Override
	public Set<D> getData() {
		return dataCount.entrySet().stream() //
				.filter(e -> !e.getValue().isEmpty()) //
				.map(Map.Entry::getKey) //
				.collect(Collectors.toSet());
	}

	@Override
	public void clear() {
		dataCount.clear();
	}
}