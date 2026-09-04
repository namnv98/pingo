package com.pingo.core.common.sql.dsl;

import com.pingo.core.common.sql.SqlQuery;
import com.pingo.core.common.sql.SqlQueryMeta;
import com.pingo.core.common.support.SortPair;
import com.pingo.core.common.support.SortType;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static com.pingo.core.common.sql.dsl.TextHelper.joinWith;
import static java.util.Objects.nonNull;

public class Select {

    private String tableName;
    private List<Object> whereArguments = new LinkedList<>();
    private List<String> fields = new LinkedList<>();
    private List<SortPair> sortPairs = new LinkedList<>();
    private List<String> whereClauses = new LinkedList<>();
    private List<String> joinClauses = new LinkedList<>();
    private long offset;
    private int limit;


    public Select(String tableName, List<String> whereClauses, Object... whereArgs) {
        this.tableName = tableName;
        this.whereClauses = whereClauses;
        Collections.addAll(whereArguments, whereArgs);
    }

    public Select(String tableName) {
        this.tableName = tableName;
    }

    public static Select on(String tableName) {
        return new Select(tableName);
    }

    public Select where(String whereClause) {
        this.whereClauses.add(whereClause);
        return this;
    }

    public Select get(String... fieldNames) {
        fields.addAll(Arrays.asList(fieldNames));
        return this;
    }

    public Select get(List<String> fieldNames) {
        fields.addAll(fieldNames);
        return this;
    }

    public Select orderBy(SortPair... sortPairs) {
        this.sortPairs.addAll(Arrays.asList(sortPairs));
        return this;
    }

    public Select paging(long page, int perPage) {
        this.limit = perPage;
        this.offset = (page - 1) * perPage;
        return this;
    }

    public Select join(String join) {
        joinClauses.add(join);
        return this;
    }

    public SqlQuery toSQL() {
        return SqlQuery.of(toRawQuery())
            .withArgs(whereArguments)
            .setQueryMeta(SqlQueryMeta.READ_ONLY);
    }

    public String toRawQuery() {
        var queryBuilder = new StringBuilder("SELECT ");
        if (fields.isEmpty()) {
            queryBuilder.append("* ");
        } else {
            joinWith(queryBuilder, fields, ",");
        }
        queryBuilder.append(" FROM ");
        queryBuilder.append(tableName);
        if (nonNull(joinClauses)) {
            joinClauses.forEach(j -> queryBuilder.append(" ").append(j));
        }
        if (!whereClauses.isEmpty()) {
            queryBuilder.append(" WHERE ");
            joinWith(queryBuilder, whereClauses, " AND ");
        }
        if (sortPairs.size() > 0) {
            queryBuilder.append(" ORDER BY");
            sortPairs.forEach(sortPair -> {
                queryBuilder.append(" ").append(sortPair.getField()).append(" ").append(sort(sortPair.getSortType())).append(",");
            });
            queryBuilder.deleteCharAt(queryBuilder.length() - 1);
        }
        if (limit != 0) {
            queryBuilder.append(" LIMIT ").append(this.limit)
                .append(" OFFSET ").append(this.offset);
        }
        return queryBuilder.toString();
    }

    private String sort(SortType type) {
        return switch (type) {
            case ASC -> "asc";
            case ASC_NULLS_FIRST -> "asc nulls first";
            case DESC -> "desc";
            case DESC_NULLS_LAST -> "desc nulls last";
        };
    }

}
