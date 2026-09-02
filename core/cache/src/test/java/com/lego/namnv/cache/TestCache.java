package com.lego.namnv.cache;

import com.lego.namnv.cache.index.HashTableIndexer;
import lombok.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestCache {

    @Test
    void testNotQuery() {
        var searchableCache = CaffeineSearchableCache.<String, Session>builder() //
            .indexer(new TTUserNameIndexer()) //
            .indexer(new TTUptimeIndexer()) //
            .build();

        searchableCache.put("s1", Session.builder() //
            .socketId(UUID.randomUUID().toString()) //
            .userName("bach") //
            .uptime(100000).build());

        searchableCache.put("s2", Session.builder() //
            .socketId(UUID.randomUUID().toString()) //
            .userName("phanh") //
            .uptime(1000).build());

        searchableCache.put("s3", Session.builder() //
            .socketId(UUID.randomUUID().toString()) //
            .userName("cuong") //
            .uptime(10000034).build());
        var result = searchableCache.search(CacheQuery.not(TTUserNameIndexer.UserNameQuery.in("phanh")));
        assertEquals(2, result.size());
    }

    @AllArgsConstructor
    private static class DefaultPrimaryQuery<K> implements PrimaryQuery<K> {
        @Getter
        private final @NonNull K key;
    }

    public static class TTUptimeIndexer extends HashTableIndexer<Integer, String, Session> {

        @Override
        public boolean acceptQuery(CacheQuery query) {
            return query instanceof UptimeQuery;
        }

        @Override
        protected Integer genIndexKey(String key, Session value) {
            return value.getUptime();
        }

        @Override
        protected Integer genIndexKey(CacheQuery query) {
            return query.<UptimeQuery>cast().uptime;
        }

        @AllArgsConstructor
        public static class UptimeQuery implements CacheQuery {
            private final int uptime;
        }
    }

    @Getter
    @Builder
    @ToString
    private static class Session {

        private final String socketId;
        private final String userName;
        private final int uptime;
    }

    public class TTUserNameIndexer extends HashTableIndexer<String, String, Session> {

        @Override
        public boolean acceptQuery(CacheQuery query) {
            return query instanceof UserNameQuery;
        }

        @Override
        protected String genIndexKey(String key, Session value) {
            return value.getUserName();
        }

        @Override
        protected String genIndexKey(CacheQuery query) {
            return query.<UserNameQuery>cast().getUserName();
        }

        @Getter
        @AllArgsConstructor(staticName = "of")
        public static class UserNameQuery implements CacheQuery {
            private final @NonNull String userName;

            public static CacheQuery in(String... userNames) {
                CacheQuery q = of(userNames[0]);
                for (var i = 1; i < userNames.length; i++) {
                    q = q.or(of(userNames[i]));
                }
                return q;
            }
        }
    }
}
