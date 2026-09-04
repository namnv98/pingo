package com.pingo.core.common.tuples;

import java.util.List;
import java.util.Objects;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(staticName = "of")
@Getter
public class Triple<X, Y, Z> {

    private final X first;
    private final Y second;
    private final Z third;

    public List<?> toList() {
        return List.of(first, second, third);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof Triple)) {
            return false;
        } else {
            final Triple<?, ?, ?> that = (Triple<?, ?, ?>) o;
            return Objects.equals(this.first, that.first) //
                && Objects.equals(this.second, that.second) //
                && Objects.equals(this.third, that.third);
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second, third);
    }

    @Override
    public String toString() {
        return "(" + first + ", " + second + ", " + third + ")";
    }

}
