package com.lego.namnv.core.common.support;

import com.lego.namnv.core.common.exception.ExceptionUtils;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Collections.*;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.completedStage;

@Log4j2
@SuppressWarnings("unchecked")
public class FulfilledUtils {

    private static final CompletableFuture<Boolean> TRUE_FUTURE = completedFuture(true);
    private static final CompletionStage<Boolean> TRUE_STAGE = completedStage(true);

    private static final CompletableFuture<Boolean> FALSE_FUTURE = completedFuture(false);
    private static final CompletionStage<Boolean> FALSE_STAGE = completedStage(false);

    private static final CompletableFuture<?> NULL_FUTURE = completedFuture(null);
    private static final CompletionStage<?> NULL_STAGE = completedStage(null);

    private static final CompletableFuture<?> EMPTY_MAP_FUTURE = completedFuture(emptyMap());
    private static final CompletionStage<?> EMPTY_MAP_STAGE = completedStage(emptyMap());

    private static final CompletableFuture<?> EMPTY_LIST_FUTURE = completedFuture(emptyList());
    private static final CompletionStage<?> EMPTY_LIST_STAGE = completedStage(emptyList());

    private static final CompletableFuture<?> EMPTY_SET_FUTURE = completedFuture(emptySet());
    private static final CompletionStage<?> EMPTY_SET_STAGE = completedStage(emptySet());

    public static <T> CompletableFuture<T> emptyFuture() {
        return (CompletableFuture<T>) NULL_FUTURE;
    }

    public static <T> CompletionStage<T> emptyStage() {
        return (CompletionStage<T>) NULL_STAGE;
    }

    public static CompletableFuture<Boolean> trueFuture() {
        return TRUE_FUTURE;
    }

    public static CompletionStage<Boolean> trueStage() {
        return TRUE_STAGE;
    }

    public static CompletableFuture<Boolean> falseFuture() {
        return FALSE_FUTURE;
    }

    public static CompletionStage<Boolean> falseStage() {
        return FALSE_STAGE;
    }

    public static <K, V> CompletableFuture<Map<K, V>> emptyMapFuture() {
        return (CompletableFuture<Map<K, V>>) EMPTY_MAP_FUTURE;
    }

    public static <K, V> CompletionStage<Map<K, V>> emptyMapStage() {
        return (CompletionStage<Map<K, V>>) EMPTY_MAP_STAGE;
    }

    public static <T> CompletableFuture<List<T>> emptyListFuture() {
        return (CompletableFuture<List<T>>) EMPTY_LIST_FUTURE;
    }

    public static <T> CompletionStage<List<T>> emptyListStage() {
        return (CompletionStage<List<T>>) EMPTY_LIST_STAGE;
    }

    public static <T> CompletableFuture<Set<T>> emptySetFuture() {
        return (CompletableFuture<Set<T>>) EMPTY_SET_FUTURE;
    }

    public static <T> CompletionStage<Set<T>> emptySetStage() {
        return (CompletionStage<Set<T>>) EMPTY_SET_STAGE;
    }

    public static <T> Consumer<T> forwardEmpty(CompletableFuture<Void> target) {
        return t -> target.complete(null);
    }

    public static <T, K, V> Consumer<T> forwardEmptyMap(CompletableFuture<? super Map<K, V>> target) {
        return t -> target.complete(Collections.emptyMap());
    }

    public static <T, E> Consumer<T> forwardEmptyList(CompletableFuture<? super List<E>> target) {
        return t -> target.complete(Collections.emptyList());
    }

    public static <T, E> Consumer<T> forwardEmptySet(CompletableFuture<? super Set<E>> target) {
        return t -> target.complete(Collections.emptySet());
    }

    public static <T> Function<Throwable, T> forwardException(CompletableFuture<T> target) {
        return th -> {
            target.completeExceptionally(th);
            return null;
        };
    }

    public static <T> Function<Throwable, T> forwardException(Consumer<Throwable> consumer) {
        return th -> {
            consumer.accept(th);
            return null;
        };
    }

    public static <T> T empty(Object... any) {
        return null;
    }

    public static <T> T ignore(Throwable ex) {
        return null;
    }

    public static <T> T logAndIgnore(Throwable ex) {
        log.error("error", ex);
        return null;
    }

    @SneakyThrows
    public static <T> T logAndRethrows(Throwable ex) {
        log.error("error", ex);
        throw ex;
    }

    @SneakyThrows
    public static <T> T logAndRethrows(Throwable ex, Logger logger) {
        logger.error("error", ex);
        throw ex;
    }

    /**
     * use {@code ExceptionUtils.extractMeaningfulCause} instead
     *
     * @param cause
     * @return
     */
    @Deprecated
    public static Throwable extractMeaningfulCause(Throwable cause) {
        return ExceptionUtils.extractMeaningfulCause(cause);
    }

    public static CompletionStage<Void> delay(ScheduledExecutorService schedExecutor, Duration duration) {
        var f = new CompletableFuture<Void>();
        schedExecutor.schedule(() -> f.complete(null), duration.toNanos(), TimeUnit.NANOSECONDS);
        return f;
    }

    public static CompletionStage<Void> delay(Duration duration) {
        return delay(ScheduledExecutor.Service.getDefault(), duration);
    }
}
