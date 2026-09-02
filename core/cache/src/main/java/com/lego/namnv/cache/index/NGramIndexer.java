package com.lego.namnv.cache.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.lego.namnv.core.common.collection.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.lego.namnv.cache.CacheQuery;
import com.lego.namnv.cache.Clearable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;

public abstract class NGramIndexer<K, V> implements CacheIndexer<K, V>, Clearable {

    @ToString
    @AllArgsConstructor
    private final static class Token {
        private final int start;
        private final @NonNull String text;
    }

    @ToString
    @EqualsAndHashCode
    @AllArgsConstructor
    private static final class MatchKey<K> {
        private final int start;
        private final @NonNull K key;
        private final @NonNull String text;
    }

    private final int minGram;
    private final int maxGram;
    private final boolean caseInsensitive;
    private final boolean normalizeNonAscii;

    private final @NonNull Map<String, Set<MatchKey<K>>>[] grams;

    @SuppressWarnings("unchecked")
    protected NGramIndexer(int minGram, int maxGram, boolean caseInsensitive, boolean normalizeNonAscii) {
        if (minGram <= 0)
            throw new IllegalArgumentException("minGram cannot be <= 0, got: " + minGram);

        if (maxGram < minGram)
            throw new IllegalArgumentException("maxGram cannot be <= minGram, got min=" + minGram + ", max=" + maxGram);

        this.minGram = minGram;
        this.maxGram = maxGram;
        this.caseInsensitive = caseInsensitive;
        this.normalizeNonAscii = normalizeNonAscii;

        var numGrams = maxGram - minGram + 1;
        this.grams = new ConcurrentHashMap[numGrams];
        for (var i = 0; i < grams.length; i++) {
            this.grams[i] = new ConcurrentHashMap<String, Set<MatchKey<K>>>();
        }
    }

    @Override
    public void clear() {
        for (var gram : grams) {
            gram.clear();
        }
    }

    private Collection<String> normalizeTexts(Collection<String> collection) {
        if (collection == null || collection.isEmpty())
            return List.of();
        return collection.stream() //
                .map(s -> caseInsensitive ? s.toLowerCase() : s) //
                .map(s -> normalizeNonAscii ? stripAccents(s) : s) //
                .collect(Collectors.toUnmodifiableSet());
    }

    private String stripAccents(String s) {
        return StringUtils.stripAccents(s) //
                .replace('đ', 'd') //
                .replace('Đ', 'D');
    }

    private List<Token> tokenize(int nGram, String text) {
        int end = text.length() - nGram;
        if (end < 0)
            return List.of();
        var list = new ArrayList<Token>(end + 1);
        for (var i = 0; i <= end; i++)
            list.add(new Token(i, text.substring(i, i + nGram)));
        return list;
    }

    @Override
    public void index(K key, V value) {
        var texts = normalizeTexts(extractTexts(key, value));
        if (texts == null || texts.isEmpty())
            return;

        for (var text : texts) {
            var index = 0;
            for (var nGram = minGram; nGram <= maxGram; nGram++) {
                var gram = grams[index];
                var tokens = tokenize(nGram, text);
                for (var token : tokens) {
                    gram.compute(token.text, (k, set) -> {
                        var term = new MatchKey<>(token.start, key, text);
                        if (set == null)
                            return Set.of(term);
                        return CollectionUtils.<MatchKey<K>>setBuilder() //
                                .addAll(set) //
                                .add(term) //
                                .buildImmutable();
                    });
                }
                index++;
            }
        }
    }

    @Override
    public void removeIndex(K key, V value) {
        var texts = normalizeTexts(extractTexts(key, value));
        if (texts == null || texts.isEmpty())
            return;

        for (var text : texts) {
            var index = 0;
            for (var nGram = minGram; nGram <= maxGram; nGram++) {
                var gram = grams[index];
                var tokens = tokenize(nGram, text);
                for (var token : tokens) {
                    gram.compute(token.text, (k, set) -> {
                        if (set == null)
                            return null;
                        var term = new MatchKey<>(token.start, key, text);
                        return CollectionUtils.<MatchKey<K>>setBuilder() //
                                .addAll(set) //
                                .remove(term) //
                                .buildImmutable();
                    });
                }
                index++;
            }
        }
    }

    @Override
    public Set<K> scan(CacheQuery query) {
        var texts = normalizeTexts(extractTexts(query));
        if (texts == null || texts.isEmpty())
            return null;

        var setBuilder = CollectionUtils.<K>setBuilder();
        for (var queryText : texts) {
            var queryLength = queryText.length();
            if (queryLength < minGram)
                continue;

            var nGram = Math.min(queryText.length(), maxGram);
            var gram = grams[nGram - minGram];

            var token = queryText.substring(0, nGram);
            var matchKeys = gram.get(token);
            if (matchKeys == null || matchKeys.isEmpty())
                continue;

            if (nGram >= queryLength) {
                for (var matchKey : matchKeys)
                    setBuilder.add(matchKey.key);
                continue;
            }

            collectKey: for (var matchKey : matchKeys) {
                var start = matchKey.start;
                if (start + queryLength > matchKey.text.length())
                    continue;

                for (var i = nGram; i < queryLength; i++)
                    if (queryText.charAt(i) != matchKey.text.charAt(start + i))
                        continue collectKey;

                setBuilder.add(matchKey.key);
            }
        }
        return setBuilder.build();
    }

    protected abstract Collection<String> extractTexts(K key, V value);

    protected abstract Collection<String> extractTexts(CacheQuery query);

}
