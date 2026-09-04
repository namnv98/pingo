package com.pingo.cache;

public interface PrimaryQuery<K> extends CacheQuery {

	K getKey();
}
