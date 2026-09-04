package com.pingo.core.common.support;

import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@AllArgsConstructor
public enum Splitter {

    COMMA(Pattern.compile("\\s*,\\s*")),
    DOT(Pattern.compile("\\s*\\.\\s*")),
    PIPE(Pattern.compile("\\s*\\|\\s*")),
    SEMICOLON(Pattern.compile("\\s*;\\s*"));

    private final @NonNull Pattern pattern;

    public String[] split(@NonNull String str) {
        return pattern.split(str);
    }

    public List<String> splitToList(String str) {
        return List.of(split(str));
    }

    public Set<String> splitToSet(String str) {
        return Set.of(split(str));
    }

    public <T> List<T> splitToList(String str, Function<String, T> converter) {
        return splitToList(str).stream() //
                .map(converter) //
                .collect(Collectors.toUnmodifiableList());
    }

    public <T> List<T> splitToList(String str, Function<String, T> converter, Predicate<T> predicate) {
        return splitToList(str).stream() //
                .map(converter) //
                .filter(predicate) //
                .collect(Collectors.toUnmodifiableList());
    }

    public <T> Set<T> splitToSet(String str, Function<String, T> converter) {
        return splitToSet(str).stream() //
                .map(converter) //
                .collect(Collectors.toUnmodifiableSet());
    }

    public <T> Set<T> splitToSet(String str, Function<String, T> converter, Predicate<T> predicate) {
        return splitToSet(str).stream() //
                .map(converter) //
                .filter(predicate) //
                .collect(Collectors.toUnmodifiableSet());
    }
}
