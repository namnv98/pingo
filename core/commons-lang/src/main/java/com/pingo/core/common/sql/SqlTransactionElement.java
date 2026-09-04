package com.pingo.core.common.sql;

import lombok.*;

import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public interface SqlTransactionElement<R> {

    static <R> SqlTransactionElement<R> of(AbstractSqlExecutor<?, R> query,
            BiConsumer<SqlTransaction, Object> postHandler, String name) {
        return new SyncTransactionElement<>(query, name, postHandler);
    }

    static <R> SqlTransactionElement<R> ofAsync(AbstractSqlExecutor<?, R> query,
            BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler, String name) {
        return new AsyncTransactionElement<>(query, name, asyncPostHandler);
    }

    AbstractSqlExecutor<?, R> getQuery();

    String getName();

    Throwable getError();

    void setError(Throwable error);

    boolean isPostHandlerError();

    void setPostHandlerError(Throwable error);

    R getResult();

    void setResult(Object obj);

    boolean hasPostHandler();

}

@Getter
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
abstract class AbstractTransactionElement<R> implements SqlTransactionElement<R> {

    private final @NonNull AbstractSqlExecutor<?, R> query;
    private final String name;

    @Setter
    private Throwable error;
    private boolean isPostHandlerError = false;

    private R result;

    @Override
    public void setPostHandlerError(Throwable error) {
        this.error = error;
        this.isPostHandlerError = true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setResult(Object obj) {
        this.result = (R) obj;
    }

}

final class SyncTransactionElement<R> extends AbstractTransactionElement<R> {

    @Getter
    private final BiConsumer<SqlTransaction, Object> postHandler;

    SyncTransactionElement(@NonNull AbstractSqlExecutor<?, R> query, String name,
            BiConsumer<SqlTransaction, Object> postHandler) {
        super(query, name);
        this.postHandler = postHandler;
    }

    @Override
    public boolean hasPostHandler() {
        return postHandler != null;
    }
}

final class AsyncTransactionElement<R> extends AbstractTransactionElement<R> {

    @Getter
    private final BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler;

    public AsyncTransactionElement(@NonNull AbstractSqlExecutor<?, R> query, String name,
            BiFunction<SqlTransaction, Object, CompletionStage<Void>> asyncPostHandler) {
        super(query, name);
        this.asyncPostHandler = asyncPostHandler;
    }

    @Override
    public boolean hasPostHandler() {
        return asyncPostHandler != null;
    }
}
