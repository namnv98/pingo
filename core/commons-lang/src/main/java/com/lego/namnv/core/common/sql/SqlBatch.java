package com.lego.namnv.core.common.sql;

import com.lego.namnv.core.common.sql.exception.LegoSqlException;
import io.vertx.core.Future;
import io.vertx.sqlclient.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@RequiredArgsConstructor(staticName = "of")
public class SqlBatch extends AbstractSqlExecutor<SqlBatchResult, List<RowSet<Row>>> {

    private final @NonNull String sql;

    private List<SqlBatchArg> args;

    @Getter
    @Setter
    @Accessors(chain = true)
    private SqlQueryMeta queryMeta = null;

    public List<SqlBatchArg> getArgs() {
        if (args == null)
            return Collections.emptyList();
        return Collections.unmodifiableList(this.args);
    }

    private List<SqlBatchArg> _getOrCreateArgs() {
        if (this.args == null)
            this.args = new LinkedList<>();
        return this.args;
    }

    private SqlBatchArg toBatchArg(List<Object> list) {
        return new SqlBatchArg(list.stream().map(SqlArg::of).collect(Collectors.toList()));
    }

    public SqlBatch withArgs(List<List<Object>> args) {
        if (args != null && !args.isEmpty()) {
            var batchArgList = args.stream() //
                    .map(this::toBatchArg) //
                    .collect(Collectors.toList());
            _getOrCreateArgs().addAll(batchArgList);
        }
        return this;
    }

    public SqlBatch withBatchArgs(List<SqlBatchArg> batchArgs) {
        if (batchArgs != null && !batchArgs.isEmpty())
            _getOrCreateArgs().addAll(batchArgs);
        return this;
    }

    public SqlBatch clearArgs() {
        this.args = null;
        return this;
    }

    public SqlBatch withArg(int index, List<Object> argValues) {
        _getOrCreateArgs().add(index, toBatchArg(argValues));
        return this;
    }

    public SqlBatch withBatchArg(int index, SqlBatchArg batchArgs) {
        _getOrCreateArgs().add(index, batchArgs);
        return this;
    }

    public SqlBatch removeArg(int index) {
        if (this.args != null)
            this.args.remove(index);
        return this;
    }

    @Override
    public CompletionStage<SqlBatchResult> execute(SqlConnection connection) {
        return _execute(connection) //
                .map(listRowSet -> new SqlBatchResult(connection, listRowSet)) //
                .toCompletionStage();
    }

    @Override
    Future<List<RowSet<Row>>> _execute(SqlConnection connection) {
        PreparedQuery<RowSet<Row>> query = connection.preparedQuery(sql);
        if (args == null || args.isEmpty()) {
            return Future.failedFuture(new LegoSqlException("Batch query expect arguments"));
        }
        var tuples = new ArrayList<Tuple>();
        args.forEach(arg -> tuples
                .add(Tuple.tuple(arg.getArgs().stream().map(SqlArg::getValue).collect(Collectors.toList()))));
        return query.executeBatch(tuples).map(rowSets -> {
            var temp = rowSets;
            var listRowSet = new LinkedList<RowSet<Row>>();
            while (temp != null) {
                listRowSet.add(temp);
                temp = temp.next();
            }
            return listRowSet;
        });
    }

}
