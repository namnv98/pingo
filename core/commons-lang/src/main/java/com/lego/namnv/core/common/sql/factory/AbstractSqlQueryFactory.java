package com.lego.namnv.core.common.sql.factory;

import com.lego.namnv.core.common.sql.SqlQuery;
import com.lego.namnv.core.common.sql.exception.LegoSqlException;
import lombok.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class AbstractSqlQueryFactory implements SqlQueryFactory {

	private @NonNull Properties sqlDeclaration;

	public void loadResource(@NonNull String resourcePath) {
		try (var input = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
			if (input == null)
				return;
			load(input);
		} catch (IOException e) {
			throw new LegoSqlException("Cannot load pre-defined sql queries at resource: " + resourcePath, e);
		}
	}

	public void loadFile(@NonNull String filePath) {
		this.load(new File(filePath));
	}

	public void load(@NonNull File resourceFile) {
		if (!resourceFile.exists() || !resourceFile.isFile())
			return;

		try (var input = new FileInputStream(resourceFile)) {
			load(input);
		} catch (IOException e) {
			throw new LegoSqlException("Cannot load pre-defined sql queries at file: " + resourceFile.getAbsolutePath(),
					e);
		}
	}

	public void load(@NonNull InputStream inputStream) {
		var props = new Properties();
		try {
			props.load(inputStream);
		} catch (IOException e) {
			throw new LegoSqlException("Cannot load pre-defined sql queries from input stream", e);
		}
		load(props);
	}

	public void load(Properties sqlDeclaration) {
		this.sqlDeclaration = sqlDeclaration;
		for (var field : getClass().getFields()) {
			if (!field.isAnnotationPresent(QueryKey.class))
				continue;

			if (!field.trySetAccessible())
				continue;

			String key = field.getAnnotation(QueryKey.class).value();
			try {
				field.set(this, sqlDeclaration.get(key));
			} catch (IllegalArgumentException | IllegalAccessException e) {
				throw new LegoSqlException("Cannot inject value to sql field", e);
			}
		}
	}

	@Override
	public SqlQuery build(String sqlName, Object... args) {
		String sql = this.sqlDeclaration.getProperty(sqlName);
		if (sql == null)
			throw new LegoSqlException("SQL not found for name: " + sqlName);
		return SqlQuery.of(sql).withArgs(args);
	}
}
