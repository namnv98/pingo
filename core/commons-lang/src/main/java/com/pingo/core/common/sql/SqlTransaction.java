package com.pingo.core.common.sql;

import com.pingo.core.common.exception.ExceptionUtils;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Transaction;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.log4j.Log4j2;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.completedStage;

@Log4j2
public class SqlTransaction implements SqlExecutor<SqlTransactionResult> {

    private final List<SqlTransactionElement<?>> elements = new CopyOnWriteArrayList<>();

    private AtomicInteger executedCount = new AtomicInteger(0);

    @Getter
    @Setter
    @Accessors(chain = true)
    private SqlQueryMeta queryMeta = null;

    private AtomicBoolean isDone = new AtomicBoolean(false);

    public SqlTransactionElement<?> getNamedElement(String name) {
        for (var ele : elements)
            if (name.equals(ele.getName()))
                return ele;
        return null;
    }

    public SqlTransactionElement<?> getLastExecuted() {
        var lastExecuted = executedCount.get() - 1;
        if (lastExecuted < 0 || elements.size() <= lastExecuted) {
            return null;
        }
        return elements.get(lastExecuted);
    }

    public SqlTransaction withArgsByNamedElement(String name, Consumer<SqlTransactionElement<?>> consumer) {
        SqlTransactionElement<?> element = getNamedElement(name);
        if (element == null)
            return this;

        consumer.accept(element);
        return this;
    }

    public SqlTransaction clearQueries() {
        checkStateNotDone();
        elements.clear();
        return this;
    }

    public SqlTransaction addQuery(SqlBatch query) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(query, null, null));
        return this;
    }

    public SqlTransaction addQuery(SqlBatch query, String name) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(query, null, name));
        return this;
    }

    public SqlTransaction addQuery(SqlBatch batch, BiConsumer<SqlTransaction, Object> postHandler) {
        return addQuery(batch, postHandler, null);
    }

    public SqlTransaction addQuery(SqlBatch batch,
            BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler) {
        return addQuery(batch, asyncPostHandler, null);
    }

    public SqlTransaction addQuery(SqlBatch batch,
            BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler, String name) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.ofAsync(batch, asyncPostHandler, name));
        return this;
    }

    public SqlTransaction addQuery(SqlBatch batch, BiConsumer<SqlTransaction, Object> postHandler, String name) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(batch, postHandler, name));
        return this;
    }

    public SqlTransaction addQuery(SqlQuery query) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(query, null, null));
        return this;
    }

    public SqlTransaction addQuery(SqlQuery query, String name) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(query, null, name));
        return this;
    }

    public SqlTransaction addQuery(SqlQuery query, BiConsumer<SqlTransaction, Object> postHandler) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(query, postHandler, null));
        return this;
    }

    public SqlTransaction addQuery(SqlQuery query, BiConsumer<SqlTransaction, Object> postHandler, String name) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.of(query, postHandler, name));
        return this;
    }

    public SqlTransaction addQuery(SqlQuery query,
            BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler) {
        return addQuery(query, asyncPostHandler, null);
    }

    public SqlTransaction addQuery(SqlQuery query,
            BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler, String name) {
        checkStateNotDone();
        elements.add(SqlTransactionElement.ofAsync(query, asyncPostHandler, name));
        return this;
    }

    public SqlTransaction addQuery(int index, SqlQuery query) {
        checkStateNotDone(index);
        elements.add(index, SqlTransactionElement.of(query, null, null));
        return this;
    }

    public SqlTransaction addQuery(int index, SqlQuery query, String name) {
        checkStateNotDone(index);
        elements.add(index, SqlTransactionElement.of(query, null, name));
        return this;
    }

    public SqlTransaction addQuery(int index, SqlQuery query, BiConsumer<SqlTransaction, Object> postHandler) {
        return addQuery(index, query, postHandler, null);
    }

    public SqlTransaction addQuery(int index, SqlBatch query, BiConsumer<SqlTransaction, Object> postHandler) {
        checkStateNotDone(index);
        elements.add(index, SqlTransactionElement.of(query, postHandler, null));
        return this;
    }

    public SqlTransaction addQuery(int index, SqlQuery query, BiConsumer<SqlTransaction, Object> postHandler,
            String name) {
        checkStateNotDone(index);
        elements.add(index, SqlTransactionElement.of(query, postHandler, name));
        return this;
    }

    public SqlTransaction addAllQuery(Collection<SqlQuery> queries) {
        checkStateNotDone();
        this.elements.addAll(queries.stream() //
                .map(q -> SqlTransactionElement.of(q, null, null)) //
                .collect(Collectors.toList()));

        return this;
    }

    public SqlTransaction addAllQuery(SqlQuery... queries) {
        checkStateNotDone();
        return addAllQuery(Arrays.asList(queries));
    }

    public SqlTransaction removeQuery(int index) {
        checkStateNotDone(index);
        elements.remove(index);
        return this;
    }

    public SqlTransaction removeQuery(SqlQuery query) {
        checkStateNotExecute();
        var it = elements.iterator();
        while (it.hasNext())
            if (it.next().getQuery().equals(query))
                it.remove();
        return this;
    }

    @Override
    public CompletionStage<SqlTransactionResult> execute(SqlConnection connection) {
        return connection.begin().toCompletionStage() //
                .thenCompose(tx -> continueQuery(connection, tx, null)) //
                .thenApply(v -> {
                    var results = new ArrayList<>(elements.size());
                    for (var result : elements) {
                        var error = result.getError();
                        if (Objects.nonNull(error))
                            return new SqlTransactionResult(connection, error);
                        results.add(result.getResult());
                    }
                    return new SqlTransactionResult(connection, results);
                }) //
                .exceptionally(ex -> {
                    var error = ExceptionUtils.extractMeaningfulCause(ex);
                    return new SqlTransactionResult(connection, error);
                });
    }

    private CompletionStage<Void> continueQuery(SqlConnection connection, Transaction tx,
            SqlTransactionElement<?> lastResult) {
        var executed = executedCount.get();
        if (Objects.nonNull(lastResult)) {
            var lastError = lastResult.getError();
            if (Objects.nonNull(lastError) && lastResult.isPostHandlerError()) {
                isDone.set(true);
                return tx.rollback()
                        .onFailure(rollbackEx -> log.error(
                                "Transaction rolling back unsuccessful (target future completed by previous throwable)",
                                rollbackEx))
                        .toCompletionStage();
            }
        }

        if (executed >= elements.size()) {
            isDone.set(true);
            return tx.commit().toCompletionStage();
        }

        var queryHolder = elements.get(executed);
        return queryHolder.getQuery() //
                ._execute(connection) //
                .toCompletionStage() //
                .thenCompose(result -> {
                    executedCount.incrementAndGet();
                    queryHolder.setResult(result);
                    if (!queryHolder.hasPostHandler())
                        return completedStage(queryHolder);

                    if (queryHolder instanceof SyncTransactionElement)
                        return executePostHandler((SyncTransactionElement<?>) queryHolder, result);

                    return executePostHandler((AsyncTransactionElement<?>) queryHolder, result);
                }) //
                .exceptionally(ex -> {
                    var cause = ExceptionUtils.extractMeaningfulCause(ex);
                    queryHolder.setError(cause);
                    return ExceptionUtils.rethrows(cause);
                }) //
                .thenCompose(res -> continueQuery(connection, tx, res));
    }

    private CompletionStage<SqlTransactionElement<?>> executePostHandler(AsyncTransactionElement<?> queryHolder,
            Object result) {
        return queryHolder.getAsyncPostHandler() //
                .apply(this, result) //
                .handle((ar, ex) -> {
                    if (Objects.nonNull(ex))
                        queryHolder.setPostHandlerError(ex);
                    return queryHolder;
                });
    }

    private CompletionStage<SqlTransactionElement<?>> executePostHandler(SyncTransactionElement<?> queryHolder,
            Object result) {
        try {
            queryHolder.getPostHandler().accept(this, result);
            return completedStage(queryHolder);
        } catch (Exception ex) {
            queryHolder.setPostHandlerError(ex);
            return completedStage(queryHolder);
        }
    }

    private void checkStateNotDone() {
        if (isDone.get())
            throw new IllegalStateException("Transaction is done, cannot update!");
    }

    private void checkStateNotExecute() {
        if (executedCount.get() == 0 && !isDone.get())
            throw new IllegalStateException("Transaction is done, cannot update!");
    }

    private void checkStateNotDone(int index) {
        checkStateNotDone();
        if (index < executedCount.get()) {
            throw new IllegalStateException("Can not add or remove query to executed index!");
        }
    }

}
