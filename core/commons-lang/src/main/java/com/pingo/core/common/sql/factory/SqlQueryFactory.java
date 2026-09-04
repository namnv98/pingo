package com.pingo.core.common.sql.factory;

import com.pingo.core.common.sql.SqlQuery;
import com.pingo.core.common.sql.exception.LegoSqlException;

import java.io.InputStream;
import java.util.Properties;

public interface SqlQueryFactory {

	SqlQuery build(String sqlName, Object... args);

	static <T extends SqlQueryFactory> T newInstance(Class<T> cls, String resourceName) {
		try (InputStream input = SqlQueryFactory.class.getResourceAsStream(resourceName)) {
			Properties props = new Properties();
			props.load(input);
			return (T) cls.getConstructor(Properties.class).newInstance(props);
		} catch (Exception e) {
			throw new LegoSqlException("Cannot init SqlQueryFactory instance for type: " + cls.getName() + " and resource "
					+ resourceName);
		}
	}
}
