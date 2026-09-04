package com.pingo.core.common.token;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.NonNull;

public class JwtHelper {

    private final @NonNull Algorithm algorithm;
    private final @NonNull JWTVerifier verifier;

    public JwtHelper(Algorithm algorithm) {
        this.algorithm = algorithm;
        this.verifier = JWT.require(this.algorithm).build();
    }

    public TokenBuilder tokenBuilder() {
        return new TokenBuilder(algorithm);
    }

    public Token decode(String token) throws NdlTokenException {
        try {
            return new Token(verifier.verify(JWT.decode(token)));
        } catch (Throwable ex) {
            throw new NdlTokenException("Token failed to verify/decode", ex);
        }
    }

    public CompletionStage<Token> decodeAsync(String token) {
        try {
            return CompletableFuture.completedStage(decode(token));
        } catch (Throwable ex) {
            return CompletableFuture.failedStage(ex);
        }
    }
}
