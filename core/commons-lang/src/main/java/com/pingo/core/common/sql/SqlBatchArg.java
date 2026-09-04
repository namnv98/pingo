package com.pingo.core.common.sql;

import lombok.*;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class SqlBatchArg {

	@Singular
	private @NonNull List<SqlArg> args;

	public SqlArg getNamedArg(@NonNull String name) {
		for (var arg : args) {
			if (name.equals(arg.getName()))
				return arg;
		}
		return null;
	}
}
