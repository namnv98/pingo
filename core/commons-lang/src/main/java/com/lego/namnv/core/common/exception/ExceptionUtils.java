package com.lego.namnv.core.common.exception;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ExceptionUtils {

    public static Throwable getRootCause(Throwable ex) {
        var rootCause = ex;
        while (rootCause != null) {
            if (rootCause.getCause() == null)
                return rootCause;
            rootCause = rootCause.getCause();
        }
        return rootCause;
    }

    public static Throwable extractMeaningfulCause(Throwable cause) {
        if (cause instanceof ExecutionException //
            || cause instanceof CompletionException //
            || cause instanceof InvocationTargetException) {
            var parent = cause.getCause();
            if (parent != null)
                return extractMeaningfulCause(parent);
        }
        return cause;
    }

    @SneakyThrows
    public static <T> T rethrows(Throwable cause) {
        throw cause;
    }
}
