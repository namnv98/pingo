package com.lego.namnv.core.common.sql.dsl;


import com.lego.namnv.core.common.sql.SqlQuery;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;


public class Delete {

    private String tableName;
    private List<String> conditions;
    private List<String> returnColumns;

    Delete(String tableName) {
        this.tableName = tableName;
        conditions = new LinkedList<>();
        returnColumns = new LinkedList<>();
    }

    public static Delete from(String tableName) {
        return new Delete(tableName);
    }

    public SqlQuery toSqlQuery() {
        return SqlQuery.of(toRawSql());
    }

    public Delete where(String clause) {
        conditions.add(clause);
        return this;
    }

    public Delete returnValue(String column) {
        this.returnColumns.add(column);
        return this;
    }

    public Delete returnValues(String... columns) {
        this.returnColumns.addAll(Arrays.asList(columns));
        return this;
    }

    public String toRawSql() {
        var query = new StringBuilder("DELETE FROM");
        query.append(" ").append(tableName).append(" ");
        query.append(" WHERE ");
        TextHelper.joinWith(query, conditions, " AND ");
        if (!returnColumns.isEmpty()) {
            query.append(" RETURNING ");
            TextHelper.joinWith(query, returnColumns, ",");
        }
        return query.toString();
    }


}
