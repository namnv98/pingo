package com.lego.namnv.core.api;


public interface IContext {

    <T> T lookup(String key);

    default <T> T lookupMandatory(String key) {
        T value = lookup(key);
        if (value != null)
            return value;
        throw new RuntimeException(key);
    }

}
