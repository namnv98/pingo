package com.lego.namnv.cache;

import java.util.Collection;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

public interface BoolQuery extends CacheQuery {

}

@AllArgsConstructor
class NotQuery implements BoolQuery {
    @Getter
    private final @NonNull CacheQuery inner;
}

abstract class BinaryBoolQuery implements BoolQuery {

    @Getter
    private final @NonNull List<CacheQuery> elements;

    protected BinaryBoolQuery(Collection<CacheQuery> elements) {
        if (elements == null || elements.size() < 2) {
            var size = elements == null ? 0 : elements.size();
            throw new IllegalArgumentException(
                    "BinaryBoolQuery require atleast 2 elements, got: " + elements + " (size = " + size + ")");
        }
        this.elements = List.copyOf(elements);
    }
}

class AndQuery extends BinaryBoolQuery {
    AndQuery(Collection<CacheQuery> elements) {
        super(elements);
    }
}

class OrQuery extends BinaryBoolQuery {
    OrQuery(Collection<CacheQuery> elements) {
        super(elements);
    }
}