package com.pingo.core.common.collection;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.experimental.Delegate;

public class CollectionUtils {

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class ListBuilder<T> implements Iterable<T> {

        @Delegate(types = Iterable.class)
        private final @NonNull List<T> source;

        public List<T> build() {
            return source;
        }

        public List<T> buildImmutable() {
            return Collections.unmodifiableList(source);
        }

        public T[] buildArray(Class<T> componentType) {
            return ArrayUtils.toArray(componentType, source);
        }

        public ListBuilder<T> add(T element) {
            source.add(element);
            return this;
        }

        public ListBuilder<T> addAll(Collection<? extends T> elements) {
            source.addAll(elements);
            return this;
        }

        public ListBuilder<T> addAll(List<? extends T> elements, int start, int end) {
            end = Math.min(end, elements.size());
            start = Math.max(0, start);
            for (int i = start; i < end; i++)
                source.add(elements.get(i));
            return this;
        }

        public ListBuilder<T> addAll(List<? extends T> elements, int start) {
            return this.addAll(elements, start, elements.size());
        }

        public ListBuilder<T> addAll(SetBuilder<? extends T> elements) {
            source.addAll(elements.source);
            return this;
        }

        public ListBuilder<T> addAll(ListBuilder<? extends T> elements) {
            source.addAll(elements.source);
            return this;
        }

        public ListBuilder<T> addAll(@SuppressWarnings("unchecked") T... elements) {
            return this.addAll(Arrays.asList(elements));
        }

        public ListBuilder<T> clear() {
            source.clear();
            return this;
        }

        public ListBuilder<T> remove(T element) {
            source.remove(element);
            return this;
        }

        public ListBuilder<T> removeAll(Collection<T> removedElements) {
            source.removeAll(removedElements);
            return this;
        }

        public ListBuilder<T> removeAll(@SuppressWarnings("unchecked") T... elements) {
            return removeAll(Arrays.asList(elements));
        }

        public int size() {
            return source.size();
        }

        public boolean contains(@NonNull T element) {
            return source.contains(element);
        }

        public boolean retainAll(Collection<T> subset) {
            return source.retainAll(subset);
        }
    }

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    public static class SetBuilder<T> implements Iterable<T> {

        @Delegate(types = Iterable.class)
        private final @NonNull Set<T> source;

        public Set<T> build() {
            return source;
        }

        public Set<T> buildImmutable() {
            return Collections.unmodifiableSet(source);
        }

        public SetBuilder<T> add(T element) {
            source.add(element);
            return this;
        }

        public SetBuilder<T> addAll(Collection<? extends T> elements) {
            source.addAll(elements);
            return this;
        }

        public SetBuilder<T> addAll(SetBuilder<? extends T> elements) {
            source.addAll(elements.source);
            return this;
        }

        public SetBuilder<T> addAll(ListBuilder<? extends T> elements) {
            source.addAll(elements.source);
            return this;
        }

        public SetBuilder<T> addAll(@SuppressWarnings("unchecked") T... elements) {
            return this.addAll(Arrays.asList(elements));
        }

        public SetBuilder<T> clear() {
            source.clear();
            return this;
        }

        public SetBuilder<T> remove(T element) {
            source.remove(element);
            return this;
        }

        public SetBuilder<T> removeAll(Collection<T> removedElements) {
            source.removeAll(removedElements);
            return this;
        }

        public SetBuilder<T> removeAll(@SuppressWarnings("unchecked") T... elements) {
            return removeAll(Arrays.asList(elements));
        }

        public int size() {
            return source.size();
        }

        public boolean contains(@NonNull T element) {
            return source.contains(element);
        }

        public boolean retainAll(Collection<T> subset) {
            return source.retainAll(subset);
        }

        public boolean retainAll(SetBuilder<T> subset) {
            return source.retainAll(subset.source);
        }
    }

    public static <T> ListBuilder<T> listBuilder(List<T> holder) {
        return new ListBuilder<T>(holder);
    }

    public static <T> ListBuilder<T> listBuilder() {
        return listBuilder(new ArrayList<T>());
    }

    public static <T> SetBuilder<T> setBuilder(Set<T> holder) {
        return new SetBuilder<T>(holder);
    }

    public static <T> SetBuilder<T> setBuilder() {
        return setBuilder(new HashSet<T>());
    }

    public static <T> List<T> subList(List<T> source, int start, int length) {
        var list = new ArrayList<T>();
        start = Math.max(start, 0);
        var size = Math.min(start + length, source.size());
        for (int i = start; i < size; i++)
            list.add(source.get(i));
        return list;
    }
}
