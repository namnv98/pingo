package com.pingo.cache;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.pingo.cache.index.RegexIndexer;
import com.pingo.cache.index.WildcardIndexer;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

public class TestTextIndexer extends Assertions {

    @Getter
    @AllArgsConstructor
    private static class TextQuery implements CacheQuery {
        private final @NonNull String text;
    }

    private static class SimpleRegexIndexer extends RegexIndexer<String, Integer> {

        @Override
        public boolean acceptQuery(CacheQuery query) {
            return query instanceof TextQuery;
        }

        @Override
        protected String genQueryString(@NonNull CacheQuery query) {
            return query.<TextQuery>cast().getText();
        }

    }

    private static class SimpleWildcardIndexer extends WildcardIndexer<String, Integer> {

        @Override
        public boolean acceptQuery(CacheQuery query) {
            return query instanceof TextQuery;
        }

        @Override
        protected String genQueryString(@NonNull CacheQuery query) {
            return query.<TextQuery>cast().getText();
        }

        @Override
        protected String genIndexKey(String key, Integer value) {
            return key;
        }

    }

    @Test
    void testRegexIndexer() {
        var cache = CaffeineSearchableCache.<String, Integer>builder() //
                .indexer(new SimpleRegexIndexer()) //
                .build();

        cache.put("\\w+\\s+\\w+", 1);
        var result = cache.search(new TextQuery("bach den"));
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(1, result.values().iterator().next());

        result = cache.search(new TextQuery("bach"));
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void testWildcardIndexer() {
        var cache = CaffeineSearchableCache.<String, Integer>builder() //
                .indexer(new SimpleWildcardIndexer()) //
                .build();

        cache.put("*", 1);
        cache.put("issue.*", 2);
        cache.put("issue.update.*", 3);

        var testcase = Map.of( //
                "issue.update.assignee", Set.of(1, 2, 3), //
                "issue.update.status", Set.of(1, 2, 3), //
                "issue.created", Set.of(1, 2), //
                "issue", Set.of(1));

        for (var e : testcase.entrySet()) {
            var result = cache.search(new TextQuery(e.getKey()));
            assertNotNull(result);
            assertEquals(e.getValue().size(), result.size());
            assertEquals(e.getValue(), Set.copyOf(result.values()));
        }
    }
}
