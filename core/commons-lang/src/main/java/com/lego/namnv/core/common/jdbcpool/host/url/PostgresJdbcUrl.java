package com.lego.namnv.core.common.jdbcpool.host.url;

import com.google.auto.service.AutoService;
import com.lego.namnv.core.common.jdbcpool.host.DatabaseType;
import com.lego.namnv.core.common.jdbcpool.host.PgHostBuilder;

import java.util.ArrayList;
import java.util.List;

@AutoService(JdbcUrlProvider.class)
public class PostgresJdbcUrl implements JdbcUrlProvider {
    public PostgresJdbcUrl() {
    }

    @Override
    public DatabaseType getDatabaseType() {
        return DatabaseType.POSTGRES;
    }

    @Override
    public boolean supports(DatabaseType type) {
        return getDatabaseType().equals(type);
    }

    @Override
    public String createJdbcUrl(PgHostBuilder request) {
        String jdbc = "jdbc:{driver}://{host}:{port}/{db_name}?{options}";
        var properties = request.properties();
        List<String> propertiedList = new ArrayList<>();
        properties.forEach((s, s2) -> {
            propertiedList.add(String.format("%s=%s", s, s2));
        });
        var options = String.join("&", propertiedList);
        jdbc = jdbc.replace("{driver}", "postgresql");
        jdbc = jdbc.replace("{host}", request.host());
        jdbc = jdbc.replace("{port}", String.valueOf(request.port()));
        jdbc = jdbc.replace("{db_name}", request.database());
        jdbc = jdbc.replace("{options}", options);
        return jdbc;
    }
}
