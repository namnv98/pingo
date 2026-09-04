package com.pingo.core.common.sql;

import io.vertx.core.Future;
import io.vertx.sqlclient.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@RequiredArgsConstructor(staticName = "of")
public class SqlQuery extends AbstractSqlExecutor<SqlSingleResult, RowSet<Row>> {

    private final @NonNull String sql;

    private List<SqlArg> args;
    
    @Getter
    @Setter
    @Accessors(chain = true)
    private SqlQueryMeta queryMeta = null;

    public List<SqlArg> getArgs() {
        if (this.args == null)
            return Collections.emptyList();
        return Collections.unmodifiableList(this.args);
    }

    private List<SqlArg> _getOrCreateArgs() {
        if (this.args == null)
            this.args = new LinkedList<>();
        return this.args;
    }

    public SqlQuery withArgs(List<Object> args) {
        if (args != null && !args.isEmpty())
            _getOrCreateArgs().addAll(args.stream().map(SqlArg::of).collect(Collectors.toList()));
        return this;
    }

    public SqlQuery withArgs(Object... args) {
        if (args == null || args.length == 0)
            return this;
        return this.withArgs(Arrays.asList(args));
    }

    public SqlQuery clearArgs() {
        this.args = null;
        return this;
    }

    public SqlQuery withArg(String name, Object argValue) {
        _getOrCreateArgs().add(SqlArg.of(argValue, name));
        return this;
    }

    public SqlQuery withArg(Object argValue) {
        _getOrCreateArgs().add(SqlArg.of(argValue));
        return this;
    }

    public SqlQuery withArg(int index, Object argValue) {
        _getOrCreateArgs().add(index, SqlArg.of(argValue));
        return this;
    }

    public SqlQuery removeArg(int index) {
        if (this.args != null)
            this.args.remove(index);
        return this;
    }

    public SqlQuery removeArg(Object argsValue) {
        var args = this.args;
        if (args != null) {
            var it = args.iterator();
            while (it.hasNext())
                if (it.next().getValue().equals(argsValue))
                    it.remove();
        }
        return this;
    }

    public SqlQuery setArg(String name, Object value) {
        for (var arg : args)
            if (name.equals(arg.getName()))
                arg.setValue(value);
        return this;
    }

    public SqlQuery setArgs(Map<String, Object> argValues) {
        if (argValues.isEmpty())
            return this;

        argValues.forEach((key, value) -> args.stream().filter(arg -> arg.getName().equals(key))
                .forEach(arg -> arg.setValue(value)));

        return this;
    }

    @Override
    public CompletionStage<SqlSingleResult> execute(SqlConnection connection) {
        var f = new CompletableFuture<SqlSingleResult>();
        _execute(connection).onComplete(ar -> {
            if (ar.succeeded())
                f.complete(new SqlSingleResult(connection, ar.result()));
            else
                f.complete(new SqlSingleResult(connection, ar.cause()));
        });
        return f;
    }

    @Override
    Future<RowSet<Row>> _execute(SqlConnection connection) {
        PreparedQuery<RowSet<Row>> query = connection.preparedQuery(sql);
        if (args == null || args.isEmpty())
            return query.execute();
        return query.execute(Tuple.wrap( //
                args.stream() //
                        .map(SqlArg::getValue) //
                        .collect(Collectors.toList())));
    }

}
