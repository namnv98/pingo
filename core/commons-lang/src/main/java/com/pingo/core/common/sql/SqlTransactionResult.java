package com.pingo.core.common.sql;

import io.vertx.sqlclient.SqlConnection;

import java.util.List;

public class SqlTransactionResult extends SqlResult<List<?>> {

	public SqlTransactionResult(SqlConnection connection, List<?> result) {
		super(connection, result);
	}

	public SqlTransactionResult(SqlConnection connection, Throwable exception) {
		super(connection, exception);
	}
}
