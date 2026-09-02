package com.lego.namnv.cache.index;

import java.util.regex.Pattern;

import lombok.NonNull;

public abstract class WildcardIndexer<K, V> extends RegexIndexer<K, V> {

    private static final Pattern WILDCARD_PATTERN = Pattern.compile("(\\*+)|(\\?)");

    protected abstract String genIndexKey(K key, V value);

    @NonNull
    protected final String genRegex(@NonNull K key, V value) {
        var input = genIndexKey(key, value);
        var matcher = WILDCARD_PATTERN.matcher(input);
        var sb = new StringBuilder();
        var lastPoint = 0;
        while (matcher.find()) {
            var start = matcher.start();
            if (start > lastPoint)
                sb.append(Pattern.quote(input.substring(lastPoint, start)));
            var end = matcher.end();
            var group = input.substring(start, end);
            if (group.startsWith("*"))
                sb.append(".*");
            else
                sb.append(".");
            lastPoint = end;
        }

        if (lastPoint < input.length() - 1)
            sb.append(Pattern.quote(input.substring(lastPoint)));

        return sb.toString();
    }
}
