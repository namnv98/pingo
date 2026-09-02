package com.lego.namnv.core.common.jdbcpool.exception;


import com.lego.namnv.core.common.exception.LegoException;

public class JdbcConnectionException extends LegoException {

    private static final long serialVersionUID = -5647622071993900510L;

    public JdbcConnectionException(String message) {
        super(message);
    }
}
