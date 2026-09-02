package com.lego.namnv.cache;

import java.util.*;

import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.lego.namnv.cache.index.NGramIndexer;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import static org.assertj.core.api.Assertions.assertThat;

@Log4j2
class TestNGramIndexer {

    @Getter
    @AllArgsConstructor
    static class NGramQuery implements CacheQuery {
        private final @NonNull String text;
    }

    @Getter
    @ToString
    @AllArgsConstructor
    static class User {
        private final @NonNull UUID id;
        private final @NonNull String username;
        private final @NonNull String displayName;

        static Object printMulti(Collection<User> users) {
            return new Object() {
                @Override
                public String toString() {
                    var sb = new StringBuilder();
                    var it = users.iterator();
                    if (it.hasNext()) {
                        sb.append(it.next());
                        while (it.hasNext()) {
                            sb.append(",\n").append(it.next());
                        }
                    }
                    return sb.toString();
                }
            };
        }
    }

    static class UserCaseInsensitiveNonUtf8Indexer extends NGramIndexer<UUID, User> {

        protected UserCaseInsensitiveNonUtf8Indexer() {
            super(1, 3, true, true);
        }

        @Override
        public boolean acceptQuery(CacheQuery query) {
            return query instanceof NGramQuery;
        }

        @Override
        protected Collection<String> extractTexts(UUID key, User value) {
            return Set.of(value.username.toLowerCase(), value.displayName.toLowerCase());
        }

        @Override
        protected Collection<String> extractTexts(CacheQuery query) {
            var ngramQuery = query.<NGramQuery>cast();
            return List.of(ngramQuery.text.toLowerCase());
        }
    }

    @Test
    @DisplayName("Test simple ngram index query")
    void t00() {
        var users = List.of( //
                new User(UUID.randomUUID(), "doc_xong", "Đọc xong")
//                new User(UUID.randomUUID(), "tung", "tùng ơi"), //
//                new User(UUID.randomUUID(), "bach", "Nguyễn Hoàng Bách"), //
//                new User(UUID.randomUUID(), "anhpt", "Phan Tuấn Anh"), //
//                new User(UUID.randomUUID(), "quannv", "Nguyễn Văn Quân"), //
//                new User(UUID.randomUUID(), "ngocnd", "Nguyễn Đình Ngọc"), //
//                new User(UUID.randomUUID(), "binhnn", "Nguyễn Ngọc Bình"), //
//                new User(UUID.randomUUID(), "cuongnh", "Nguyễn Hữu Cường"), //
//                new User(UUID.randomUUID(), "phongmt", "Mẫn Tuấn Phong"), //
//                new User(UUID.randomUUID(), "phongdh", "Đoàn Hùng Phong"), //
//                new User(UUID.randomUUID(), "toanlm", "Lê Mạnh Toàn") //
        );

        var searchableCache = CaffeineSearchableCache.<UUID, User>builder() //
                .indexer(new UserCaseInsensitiveNonUtf8Indexer()) //
                .build();

        for (var user : users) {
            searchableCache.put(user.getId(), user);
        }

        var results = searchableCache.search(new NGramQuery("doc"));
        log.debug("result: \n{}", User.printMulti(results.values()));
    }

    @Test
    @DisplayName("Search case / mark insensitive successfully")
    void t01() {
        var users = List.of( //
            new User(UUID.randomUUID(), "oc_xong", "Đọc xong"),
                new User(UUID.randomUUID(), "tung", "tùng ơi"), //
                new User(UUID.randomUUID(), "bach", "Nguyễn Hoàng Bách"), //
                new User(UUID.randomUUID(), "anhpt", "Phan Tuấn Anh"), //
                new User(UUID.randomUUID(), "quannv", "Nguyễn Văn Quân"), //
                new User(UUID.randomUUID(), "ngocnd", "Nguyễn Đình Ngọc"), //
                new User(UUID.randomUUID(), "binhnn", "Nguyễn Ngọc Bình"), //
                new User(UUID.randomUUID(), "cuongnh", "Nguyễn Hữu Cường"), //
                new User(UUID.randomUUID(), "phongmt", "Mẫn Tuấn Phong"), //
                new User(UUID.randomUUID(), "phongdh", "Đoàn Hùng Phong"), //
                new User(UUID.randomUUID(), "toanlm", "Lê Mạnh Toàn") //
        );

        var searchableCache = CaffeineSearchableCache.<UUID, User>builder() //
            .indexer(new UserCaseInsensitiveNonUtf8Indexer()) //
            .build();

        for (var user : users) {
            searchableCache.put(user.getId(), user);
        }
        // Đ - d
        assertThat(searchableCache.search(new NGramQuery("do")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("oc_xong"))
            .anyMatch(res -> res.username.equals("phongdh"));
        assertThat(searchableCache.search(new NGramQuery("Đo")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("oc_xong"))
            .anyMatch(res -> res.username.equals("phongdh"));
        assertThat(searchableCache.search(new NGramQuery("đo")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("oc_xong"))
            .anyMatch(res -> res.username.equals("phongdh"));

        // a â ă

        var l1 = Arrays.asList("bach", "anhpt", "toanlm", "quannv", "phongmt", "phongdt");
        assertThat(searchableCache.search(new NGramQuery("a")).values())
            .hasSize(6)
            .anyMatch(res -> l1.contains(res.username));

        assertThat(searchableCache.search(new NGramQuery("â")).values())
            .hasSize(6)
            .anyMatch(res -> l1.contains(res.username));

        assertThat(searchableCache.search(new NGramQuery("ấ")).values())
            .hasSize(6)
            .anyMatch(res -> l1.contains(res.username));

        assertThat(searchableCache.search(new NGramQuery("ẵ")).values())
            .hasSize(6)
            .anyMatch(res -> l1.contains(res.username));

        // u ư
        assertThat(searchableCache.search(new NGramQuery("cuong")).values())
            .hasSize(1)
            .anyMatch(res -> res.username.equals("cuongnh"));
        assertThat(searchableCache.search(new NGramQuery("cường")).values())
            .hasSize(1)
            .anyMatch(res -> res.username.equals("cuongnh"));
        assertThat(searchableCache.search(new NGramQuery("Cường")).values())
            .hasSize(1)
            .anyMatch(res -> res.username.equals("cuongnh"));

        // e ê
        assertThat(searchableCache.search(new NGramQuery("le")).values())
            .hasSize(1)
            .anyMatch(res -> res.username.equals("toanlm"));

        assertThat(searchableCache.search(new NGramQuery("lê")).values())
            .hasSize(1)
            .anyMatch(res -> res.username.equals("toanlm"));

        // o ô ơ
        assertThat(searchableCache.search(new NGramQuery("ngo")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("ngocnd"))
            .anyMatch(res -> res.username.equals("binhnn"))
        ;
        assertThat(searchableCache.search(new NGramQuery("ngô")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("ngocnd"))
            .anyMatch(res -> res.username.equals("binhnn"))
        ;
        assertThat(searchableCache.search(new NGramQuery("ngơ")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("ngocnd"))
            .anyMatch(res -> res.username.equals("binhnn"))
        ;
        assertThat(searchableCache.search(new NGramQuery("ngỡ")).values())
            .hasSize(2)
            .anyMatch(res -> res.username.equals("ngocnd"))
            .anyMatch(res -> res.username.equals("binhnn"))
        ;
    }

}
