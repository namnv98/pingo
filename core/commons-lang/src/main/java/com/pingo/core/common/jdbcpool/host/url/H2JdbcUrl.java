package com.pingo.core.common.jdbcpool.host.url;

import com.google.auto.service.AutoService;
import com.pingo.core.common.jdbcpool.host.DatabaseType;
import com.pingo.core.common.jdbcpool.host.PgHostBuilder;

import java.util.ArrayList;
import java.util.List;

@AutoService(JdbcUrlProvider.class)
public class H2JdbcUrl implements JdbcUrlProvider {
    public H2JdbcUrl() {
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.H2;
    }

    @Override
    public boolean supports(DatabaseType type) {
        return getDatabaseType().equals(type);
    }

    @Override
    public String createJdbcUrl(PgHostBuilder request) {
        String jdbc = "jdbc:{driver}:tcp://{host}:{port}/{db_name};{options}";
        var properties = request.properties();
        List<String> propertiedList = new ArrayList<>();
        properties.forEach((s, s2) -> {
            propertiedList.add(String.format("%s=%s", s, s2));
        });
        var options = String.join(";", propertiedList);
        jdbc = jdbc.replace("{driver}", "h2");
        jdbc = jdbc.replace("{host}", request.host());
        jdbc = jdbc.replace("{db_name}", request.database());
        jdbc = jdbc.replace("{options}", options);
        jdbc = jdbc.replace("{port}", String.valueOf(request.port()));

        return jdbc;
    }
}
