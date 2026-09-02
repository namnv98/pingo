package com.lego.namnv.cache;

public interface PrimaryQuery<K> extends CacheQuery {

	K getKey();
}
