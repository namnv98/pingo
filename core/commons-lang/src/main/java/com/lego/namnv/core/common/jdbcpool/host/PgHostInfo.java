package com.lego.namnv.core.common.jdbcpool.host;

import com.lego.namnv.core.common.jdbcpool.config.JdbcHostSpec;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PgHostInfo {

    private final JdbcHostSpec spec;
    private long updatedAt; // system nano
    private JdbcHostStatus status;

    public void reportConnectFail() {
        this.status = JdbcHostStatus.CONNECT_FAIL;
        this.updatedAt = System.nanoTime();
    }

}
