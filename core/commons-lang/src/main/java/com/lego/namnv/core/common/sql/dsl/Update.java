package com.lego.namnv.core.common.sql.dsl;

import com.lego.namnv.core.common.sql.SqlQuery;
import io.vertx.core.json.JsonObject;

import java.util.*;

public class Update {

    private String tableName;
    private List<Object> whereArguments;
    private List<Object> arguments;
    private List<String> fields;
    private String where;

    public Update(String tableName, String whereClause, Object... whereArgs){
        this.tableName = tableName;
        this.where = whereClause;
        whereArguments = new ArrayList<Object>();
        arguments = new ArrayList<Object>();
        fields = new ArrayList<String>();
        Collections.addAll(whereArguments, whereArgs);
    }

    public static SqlQuery updateById(String tableName, UUID id, Map<String, Object> fields){
        var queryBuilder = new Update(tableName, "id = $1", id);
        if(isMapNotEmpty(fields)){
            fields.forEach(queryBuilder::set);
        }
        return queryBuilder.toSQL();
    }

    public static SqlQuery updateById(String tableName, UUID id, Map<String, Object> fields, Map<String, Object> pojoFields){
        var queryBuilder = new Update(tableName, "id = $1", id);
        if(isMapNotEmpty(fields)){
            fields.forEach(queryBuilder::set);
        }
        if (isMapNotEmpty(pojoFields)){
            pojoFields.forEach((k, v) -> queryBuilder.set(k, JsonObject.mapFrom(v)));
        }
        return queryBuilder.toSQL();
    }

    public static SqlQuery updateByKey(String tableName, UUID orgId, String key, Map<String, Object> fields){
        var queryBuilder = new Update(tableName, "key = $2 AND organization_id = $1", orgId, key);
        if(isMapNotEmpty(fields)){
            fields.forEach(queryBuilder::set);
        }
        return queryBuilder.toSQL();
    }

    public static SqlQuery updateByKey(String tableName, UUID orgId, String key, Map<String, Object> fields, Map<String, Object> pojoFields){
        var queryBuilder = new Update(tableName, "key = $2 AND organization_id = $1", orgId, key);
        if(isMapNotEmpty(fields)){
            fields.forEach(queryBuilder::set);
        }
        if (isMapNotEmpty(pojoFields)){
            pojoFields.forEach((k, v) -> queryBuilder.set(k, JsonObject.mapFrom(v)));
        }
        return queryBuilder.toSQL();
    }

    public Update set(String fieldName, Object arg){
        fields.add(fieldName);
        arguments.add(arg);
        return this;
    }

    public SqlQuery toSQL(){
        var queryBuilder = new StringBuilder("UPDATE " + tableName + " SET ");
        var index = whereArguments.size();
        var isFirst = true;
        for (String field : fields) {
            index++;
            if (isFirst){
                queryBuilder.append(field).append(" = $").append(index);
                isFirst = false;
                continue;
            }
            queryBuilder.append(", ").append(field).append(" = $").append(index);
        }
        queryBuilder.append(" WHERE ")
                .append(where);
        return SqlQuery.of(queryBuilder.toString()).withArgs(whereArguments)
                .withArgs(arguments);
    }

    private static boolean isMapNotEmpty(Map<?, ?> aMap){
        return aMap.size() != 0;
    }

}
