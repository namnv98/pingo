package com.pingo.core.common.sql;

import io.vertx.sqlclient.SqlConnection;

import java.util.concurrent.CompletionStage;

public interface SqlExecutor<T extends SqlResult<?>> {

    SqlQueryMeta getQueryMeta();

    CompletionStage<T> execute(SqlConnection connection);
}
