package com.pingo.core.common.sql;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

@Getter
@Setter
@ToString
@Accessors(chain = true)
public class SqlQueryMeta {

    public static final SqlQueryMeta DEFAULT = new SqlQueryMeta().setReadonly(false);
    public static final SqlQueryMeta READ_ONLY = new SqlQueryMeta().setReadonly(true);

    private boolean readonly;

}
