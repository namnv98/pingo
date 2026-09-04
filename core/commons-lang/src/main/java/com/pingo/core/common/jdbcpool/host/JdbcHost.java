package com.pingo.core.common.jdbcpool.host;

import com.pingo.core.common.jdbcpool.config.JdbcHostRequirement;
import com.pingo.core.common.jdbcpool.config.JdbcHostSpec;
import com.pingo.core.common.comp.LifeCycle;
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
