package com.lego.namnv.core.common.support;

@SuppressWarnings("unchecked")
public interface Cast {

    default <T> T cast(Class<T> clz) {
        return (T) this;
    }

    default <T> T cast() {
        return (T) this;
    }
}

