package com.pingo.core.common.jdbcpool.supplier;

import com.pingo.core.common.sql.SqlQueryMeta;
import com.pingo.core.common.support.ParsedUri;
import com.pingo.core.common.comp.LifeCycle;
import com.pingo.core.common.jdbcpool.config.JdbcConfig;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.SqlConnection;

import java.util.function.Function;
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

    /**
     * Mượn 1 connection, chạy {@code work}, rồi LUÔN đóng lại (trả về pool) dù {@code work} thành
     * công hay lỗi — pattern lặp lại ở mọi nơi dùng connection thô (khác {@code Pool.preparedQuery}
     * vốn tự làm việc này ẩn bên trong), gom về đây 1 lần cho cả framework dùng chung thay vì mỗi
     * caller tự viết try/close tay.
     */
    default <T> CompletionStage<T> execute(SqlQueryMeta queryMeta, Function<SqlConnection, CompletionStage<T>> work) {
        return getConnection(queryMeta) //
            .thenCompose(conn -> work.apply(conn).whenComplete((r, ex) -> conn.close()));
    }

    default <T> CompletionStage<T> execute(Function<SqlConnection, CompletionStage<T>> work) {
        return execute(SqlQueryMeta.DEFAULT, work);
    }

    default <T> CompletionStage<T> executeReadOnly(Function<SqlConnection, CompletionStage<T>> work) {
        return execute(SqlQueryMeta.READ_ONLY, work);
    }

}
