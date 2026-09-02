package com.lego.namnv.core.common.sql.dsl;

import lombok.Getter;

public enum ConditionOperator {

    LIKE("like"),
    EQUAL("=");

    @Getter
    private String value;

    ConditionOperator(String value) {
        this.value = value;
    }
}
