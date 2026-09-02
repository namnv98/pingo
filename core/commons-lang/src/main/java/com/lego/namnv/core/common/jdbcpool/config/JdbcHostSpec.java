package com.lego.namnv.core.common.jdbcpool.config;

import com.lego.namnv.core.common.support.L4Address;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class JdbcHostSpec {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5432;

    private @NonNull String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;

    public JdbcHostSpec(L4Address address) {
        this.host = address.getHost();
        this.port = address.getPort();
    }

    public JdbcHostSpec(String host) {
        this(host, DEFAULT_PORT);
    }

    public JdbcHostSpec(int port) {
        this(DEFAULT_HOST, port);
    }

}
