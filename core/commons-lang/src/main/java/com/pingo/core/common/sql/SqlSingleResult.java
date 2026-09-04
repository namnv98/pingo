package com.pingo.core.common.sql;

import io.vertx.jdbcclient.impl.JDBCRow;
import io.vertx.sqlclient.PropertyKind;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlConnection;
import lombok.Getter;

import java.util.concurrent.CompletionStage;

@Getter
public class SqlSingleResult extends SqlResult<RowSet<Row>> {

    public SqlSingleResult(SqlConnection connection, RowSet<Row> result) {
        super(connection, result);
    }

    public SqlSingleResult(SqlConnection connection, Throwable exception) {
        super(connection, exception);
    }

    private static Row takeFirst(RowSet<Row> rs) {
        return rs == null || rs.size() == 0 ? null : rs.iterator().next();
    }

    public Row getFirst() {
        return takeFirst(getResult());
    }

    public CompletionStage<Row> closeThenGetFirst() {
        return closeThenGet().thenApply(SqlSingleResult::takeFirst);
    }

    public CompletionStage<Row> closeThenGetFirstReturning() {
        return closeThenGet().thenApply(rows -> {
            if (rows.size() > 0) {
                throw new RuntimeException("");
            }
            return rows.property(PropertyKind.create("generated-keys", JDBCRow.class));
        });
    }

    public Row getFirstAndClose() {
        return takeFirst(getAndClose());
    }
}
