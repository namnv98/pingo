package com.lego.namnv.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import com.lego.namnv.core.common.collection.CollectionUtils;
import com.lego.namnv.core.common.support.Cast;
import lombok.NonNull;

public interface CacheQuery extends Cast {

    default CacheQuery and(@NonNull CacheQuery query, CacheQuery... moreQueries) {
        if (moreQueries == null || moreQueries.length == 0)
            return new AndQuery(List.of(this, query));
        var queries = CollectionUtils.<CacheQuery>listBuilder() //
                .add(this) //
                .add(query) //
                .addAll(moreQueries) //
                .build();
        return new AndQuery(queries);
    }

    default CacheQuery or(@NonNull CacheQuery query, CacheQuery... moreQueries) {
        if (moreQueries == null || moreQueries.length == 0)
            return new OrQuery(List.of(this, query));
        var queries = CollectionUtils.<CacheQuery>listBuilder() //
                .add(this) //
                .add(query) //
                .addAll(moreQueries) //
                .build();
        return new OrQuery(queries);
    }

    default CacheQuery not() {
        return not(this);
    }

    static CacheQuery not(CacheQuery query) {
        return new NotQuery(query);
    }
}

class CollectionSupport {
    static final List<CacheQuery> buildList(CacheQuery first, CacheQuery... anothers) {
        var elements = new ArrayList<CacheQuery>();
        elements.add(first);
        elements.addAll(Arrays.asList(anothers));
        return elements;
    }
}
