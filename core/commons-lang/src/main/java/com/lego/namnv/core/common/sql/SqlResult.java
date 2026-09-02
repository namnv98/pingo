package com.lego.namnv.core.common.sql;

import com.lego.namnv.core.common.support.Fulfilled;
import io.vertx.sqlclient.SqlConnection;
import lombok.NonNull;

import java.util.concurrent.CompletionStage;

public class SqlResult<T> extends Fulfilled<T> {

    private final @NonNull SqlConnection connection;

    protected SqlResult(SqlConnection connection, T result) {
        super(result);
        this.connection = connection;
    }

    protected SqlResult(SqlConnection connection, @NonNull Throwable exception) {
        super(exception);
        this.connection = connection;
    }

    public CompletionStage<T> closeThenGet() {
        return doCloseConnection()
                .thenCompose(this::chainedResultStage);
    }

    public CompletionStage<Void> close() {
        return doCloseConnection() //
                .thenCompose(this::chainedVoidStage);
    }

    private CompletionStage<Void> doCloseConnection() {
        return connection.close() //
                .toCompletionStage();
    }

    public T getAndClose() {
        connection.close();
        return getResult();
    }
}
