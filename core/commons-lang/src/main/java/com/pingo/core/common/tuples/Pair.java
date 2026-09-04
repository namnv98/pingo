package com.pingo.core.common.tuples;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

@RequiredArgsConstructor(staticName = "of")
@Getter
public class Pair<X, Y> {

    private final X first;
    private final Y second;

    public <U> U apply(BiFunction<? super X, ? super Y, ? extends U> f) {
        Objects.requireNonNull(f, "f is null");
        return f.apply(first, second);
    }

    public void accept(BiConsumer<? super X, ? super Y> f) {
        Objects.requireNonNull(f, "f is null");
        f.accept(first, second);
    }

    public Pair<X, Y> peek(BiConsumer<? super X, ? super Y> f) {
        Objects.requireNonNull(f, "f is null");
        f.accept(first, second);
        return this;
    }

    public <U, V> Pair<U, V> map(BiFunction<? super X, ? super Y, Pair<U, V>> mapper) {
        Objects.requireNonNull(mapper, "mapper is null");
        return mapper.apply(first, second);
    }

    public List<?> toList() {
        return List.of(first, second);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof Pair)) {
            return false;
        } else {
            final Pair<?, ?> that = (Pair<?, ?>) o;
            return Objects.equals(this.first, that.first)
                && Objects.equals(this.second, that.second);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ")";
    }

}
