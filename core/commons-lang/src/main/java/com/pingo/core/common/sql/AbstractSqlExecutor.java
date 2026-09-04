package com.pingo.core.common.sql;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlConnection;

abstract class AbstractSqlExecutor<T extends SqlResult<?>, R> implements SqlExecutor<T> {

    abstract Future<R> _execute(SqlConnection connection);
}
