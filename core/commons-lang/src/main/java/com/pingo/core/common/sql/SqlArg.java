package com.pingo.core.common.sql;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SqlArg {

	private final String name;

	@Setter
	private Object value;

	public static SqlArg of(Object value) {
		return of(value, null);
	}

	public static SqlArg of(Object value, String name) {
		return new SqlArg(name, value);
	}
}