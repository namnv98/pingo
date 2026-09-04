package com.pingo.core.common.jdbcpool.host.url;

import com.pingo.core.common.jdbcpool.host.DatabaseType;
import com.pingo.core.common.jdbcpool.host.PgHostBuilder;

import java.util.ServiceLoader;

public interface JdbcUrlProvider {
    static JdbcUrlProvider getJdbcUrl(DatabaseType type) {
        ServiceLoader<JdbcUrlProvider> loader = ServiceLoader.load(JdbcUrlProvider.class);
        var providerList = loader.stream().filter(f -> {
            return f.get().supports(type);
        }).toList();
        if (providerList.size() != 1) {
            throw new RuntimeException("");
        }
        return providerList.get(0).get();
    }

    DatabaseType getDatabaseType();

    boolean supports(DatabaseType type);

    String createJdbcUrl(PgHostBuilder request);

}
