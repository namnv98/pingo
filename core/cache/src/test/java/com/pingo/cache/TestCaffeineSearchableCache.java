package com.pingo.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import com.pingo.cache.UptimeIndexer.UptimeQuery;
import com.pingo.cache.UserNameIndexer.UserNameQuery;
import com.pingo.cache.index.HashTableIndexer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

public class TestCaffeineSearchableCache {

    public static void main(String[] args) {
        var searchableCache = CaffeineSearchableCache.<String, Session>builder() //
                .indexer(new UserNameIndexer()) //
                .indexer(new UptimeIndexer()) //
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

        var result = searchableCache.search(UserNameQuery.in("bach", "phanh").and(new UptimeQuery(1000)));
        assertEquals(1, result.size());
    }

}

class UptimeIndexer extends HashTableIndexer<Integer, String, Session> {

    @AllArgsConstructor
    public static class UptimeQuery implements CacheQuery {
        private final int uptime;
    }
    
    @Override
    protected Integer genIndexKey(String key, Session value) {
        return value.getUptime();
    }

    @Override
    public boolean acceptQuery(CacheQuery query) {
        return query instanceof UptimeQuery;
    }

    @Override
    protected Integer genIndexKey(CacheQuery query) {
        return query.<UptimeQuery>cast().uptime;
    }
}

class UserNameIndexer extends HashTableIndexer<String, String, Session> {

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

    @Override
    protected String genIndexKey(String key, Session value) {
        return value.getUserName();
    }

    @Override
    public boolean acceptQuery(CacheQuery query) {
        return query instanceof UserNameQuery;
    }

    @Override
    protected String genIndexKey(CacheQuery query) {
        return query.<UserNameQuery>cast().getUserName();
    }
}

@Getter
@Builder
@ToString
class Session {

    private final String socketId;
    private final String userName;
    private final int uptime;
}