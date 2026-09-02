package com.lego.namnv.core.common.token;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;

public class TokenValidator {

    @Getter private final @NonNull Token token;
    private final List<Function<Token, Boolean>> checks = new LinkedList<>();

    @lombok.Builder
    private TokenValidator(Token token, @Singular List<Function<Token, Boolean>> checks) {
        this.token = token;
        if (checks != null) checks.forEach(this::addCheck);
    }

    public TokenValidator validateAll() {
        checks.forEach(
                checker -> {
                    var success = checker.apply(token);
                    if (!(success == null ? false : success.booleanValue()))
                        throw new NdlTokenException("Token validate failed");
                });
        return this;
    }

    public TokenValidator addCheck(Function<Token, Boolean> checker) {
        checks.add(checker);
        return this;
    }

    public TokenValidator addChecks(Collection<Function<Token, Boolean>> checkers) {
        checks.addAll(checkers);
        return this;
    }

    @SafeVarargs
    public final TokenValidator addChecks(Function<Token, Boolean>... checkers) {
        return addChecks(Arrays.asList(checkers));
    }

    public TokenValidator clearChecks() {
        checks.clear();
        return this;
    }

    public TokenValidator removeCheck(Function<Token, Boolean> checker) {
        checks.remove(checker);
        return this;
    }

    public CompletionStage<TokenValidator> toCompletionStage() {
        return CompletableFuture.completedStage(this);
    }
}
