package com.pingo.core.common.sql;

import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import lombok.Getter;

import java.util.List;

@Getter
public class SqlBatchResult extends SqlResult<List<RowSet<Row>>> {

	public SqlBatchResult(SqlConnection connection, List<RowSet<Row>> result) {
		super(connection, result);
	}

	public SqlBatchResult(SqlConnection connection, Throwable failedCause) {
		super(connection, failedCause);
	}
}
