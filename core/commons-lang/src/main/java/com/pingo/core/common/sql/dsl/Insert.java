package com.pingo.core.common.sql.dsl;

import com.pingo.core.common.sql.SqlBatch;
import com.pingo.core.common.sql.SqlQuery;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static com.pingo.core.common.sql.dsl.TextHelper.joinWith;

/**
 * Insert single row
 * <blockquote><pre>
 *      Insert.into("ndl")
 *          .value("name", "cuong")
 *          .value("length", 18)
 * </pre></blockquote>
 * <p>
 * Insert multi rows
 * <blockquote><pre>
 *      Insert.into("ndl")
 *          .columns("name", "length")
 *          .row("linh", 1)
 *          .row("khanh", 2)
 * </pre></blockquote>
 */
public class Insert {

    private String tableName;
    private List<List<Object>> rows;
    private List<String> columns;
    private List<String> returnColumns;

    Insert(String tableName) {
        this.tableName = tableName;
        columns = new LinkedList<>();
        rows = new LinkedList<>();
        returnColumns = new LinkedList<>();
    }

    public static Insert into(String tableName) {
        return new Insert(tableName);
    }

    public SqlBatch toSqlBatch() {
        return SqlBatch.of(toRawSql())
                .withArgs(rows);
    }

    public SqlQuery toSqlQuery() {
        return SqlQuery.of(toRawSql())
                .withArgs(rows.get(0));
    }

    public Insert value(String column, Object value) {
        this.columns.add(column);
        if (this.rows.isEmpty()) {
            this.rows.add(new LinkedList<>());
        }
        this.rows.get(rows.size() - 1).add(value);
        return this;
    }

    public Insert columns(String... names) {
        this.columns.addAll(Arrays.asList(names));
        return this;
    }

    public Insert row(Object... values) {
        this.rows.add(Arrays.asList(values));
        return this;
    }

    public Insert returnValue(String column) {
        this.returnColumns.add(column);
        return this;
    }

    public Insert returnValues(String... columns) {
        this.returnColumns.addAll(Arrays.asList(columns));
        return this;
    }

    public String toRawSql() {
        var query = new StringBuilder("INSERT INTO");
        query.append(" ").append(tableName).append(" (");
        joinWith(query, columns, ",");
        query.append(") VALUES (");
        var size = columns.size();
        for (var i = 1; i <= size; ++i) {
            query.append("$").append(i);
            if (i != size) {
                query.append(",");
            }
        }
        query.append(")");
        if (!returnColumns.isEmpty()) {
            query.append(" RETURNING ");
            joinWith(query, returnColumns, ",");
        }
        return query.toString();
    }

}
