package com.pingo.core.common.sql;

import io.vertx.sqlclient.SqlConnection;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.concurrent.CompletionStage;

@AllArgsConstructor
public abstract class AbstractSqlClient {

    protected abstract CompletionStage<? extends SqlConnection> getConnection(SqlQueryMeta queryMeta);

    protected CompletionStage<? extends SqlConnection> getConnection() {
        return getConnection(SqlQueryMeta.DEFAULT);
    }

    public <T extends SqlResult<?>> CompletionStage<T> execute(SqlExecutor<T> query) {
        var queryMeta = query.getQueryMeta();
        if (queryMeta == null)
            queryMeta = SqlQueryMeta.DEFAULT;
        return getConnection(queryMeta).thenCompose(query::execute);
    }

    public CompletionStage<SqlTransactionResult> executeTransaction(SqlQuery... queries) {
        return execute(new SqlTransaction().addAllQuery(queries));
    }

    public CompletionStage<SqlSingleResult> execute(String sql) {
        return execute(SqlQuery.of(sql));
    }

    public CompletionStage<SqlSingleResult> execute(String sql, Object... args) {
        return execute(SqlQuery.of(sql).withArgs(args));
    }

    public CompletionStage<SqlSingleResult> execute(String sql, List<Object> args) {
        return execute(SqlQuery.of(sql).withArgs(args));
    }

    public CompletionStage<Void> executeThenClose(SqlExecutor<?> executor) {
        return execute(executor) //
                .thenCompose(SqlResult::close);
    }
}
