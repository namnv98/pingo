package com.lego.namnv.core.common.jdbcpool.supplier;

import com.lego.namnv.core.common.sql.SqlQueryMeta;
import com.lego.namnv.core.common.support.ParsedUri;
import com.lego.namnv.core.common.comp.LifeCycle;
import com.lego.namnv.core.common.jdbcpool.config.JdbcConfig;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlConnection;
//import io.vertx.pgclient.PgConnection;

import java.util.concurrent.CompletionStage;

public interface JdbcConnectionSupplier extends LifeCycle {

    static JdbcConnectionSupplier createAutoRefresh(JdbcConfig config, Vertx vertx) {
        return new SensingConnectionSupplier(config, vertx);
    }

    static JdbcConnectionSupplier createAutoRefresh(JdbcConfig config) {
        return new SensingConnectionSupplier(config, null);
    }

    static JdbcConnectionSupplier createSteady(JdbcConfig config, Vertx vertx) {
        return new SteadyConnectionSupplier(config, vertx);
    }

    static JdbcConnectionSupplier createSteady(JdbcConfig config) {
        return new SteadyConnectionSupplier(config, null);
    }

    static JdbcConnectionSupplier from(String uri, Vertx vertx) {
        var parsedUri = ParsedUri.parse(uri);
        return from(parsedUri, vertx);
    }

    static JdbcConnectionSupplier from(ParsedUri uri, Vertx vertx) {
        var config = JdbcConfig.from(uri);
        switch (config.getSupplierType()) {
        case STEADY:
            return new SteadyConnectionSupplier(config, vertx);
        case SENSING:
            return new SensingConnectionSupplier(config, vertx);
        }
        return null;
    }

    CompletionStage<SqlConnection> getConnection(SqlQueryMeta queryMeta);

}
