package com.pingo.core.common.sql.dsl;

@SuppressWarnings("unused")
public class Condition {
    private String field;
    private String operator;
    private String term;

    public Condition(String field, String operator, String term) {
        this.field = field;
        this.operator = operator;
        this.term = term;
    }

    public static Condition of(String field, ConditionOperator operator, String term) {
        return new Condition(field, operator.getValue(), term);
    }
}
