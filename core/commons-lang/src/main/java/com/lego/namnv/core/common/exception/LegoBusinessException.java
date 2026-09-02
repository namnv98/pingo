package com.lego.namnv.core.common.exception;

import java.util.Collections;
import java.util.Map;

public class LegoBusinessException extends LegoException {
    private static final long serialVersionUID = 5371669456520334026L;
    private String key;
    private Map<String, ?> items;

    public LegoBusinessException(String key, Map<String, ?> items, Throwable cause) {
        super(key + (items != null && !items.isEmpty() ? " -> " + items : ""), cause);
        this.key = key;
        this.items = items;
    }

    public LegoBusinessException(String key, String message) {
        this(key, Map.of("message", message), (Throwable) null);
    }

    public LegoBusinessException(String key, Map<String, ?> items) {
        this(key, items, (Throwable) null);
    }

    public LegoBusinessException(String key) {
        this(key, Collections.emptyMap(), (Throwable) null);
    }

    public LegoBusinessException(String key, Throwable cause) {
        this(key, Collections.emptyMap(), cause);
    }

    public String getKey() {
        return this.key;
    }

    public Map<String, ?> getItems() {
        return this.items;
    }
}
