package com.lego.namnv.core.common.jdbcpool.host;

import com.lego.namnv.core.common.jdbcpool.config.JdbcHostRequirement;
import com.lego.namnv.core.common.jdbcpool.config.JdbcHostSpec;
import com.lego.namnv.core.common.comp.LifeCycle;
//import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.SqlConnection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface JdbcHost extends LifeCycle {

    static PgHostBuilder builder() {
        return new PgHostBuilder();
    }

    JdbcHostSpec getHostSpec();

    CompletableFuture<PgHostInfo> getHostInfo(JdbcHostRequirement hostRequirement);

    CompletionStage<SqlConnection> getConnection();

}
