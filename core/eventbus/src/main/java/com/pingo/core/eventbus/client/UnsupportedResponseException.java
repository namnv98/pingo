package com.pingo.core.eventbus.client;

import com.pingo.core.common.exception.LegoBusinessException;
import com.pingo.core.api.error.RegisterErrorMapper;

import java.util.Map;

public class UnsupportedResponseException extends LegoBusinessException {

    private static final long serialVersionUID = 87138116099807879L;

    @RegisterErrorMapper(500)
    public static final String KEY = "com.lego.namnv.eventbus.client.unsupported";

    public UnsupportedResponseException(Object value) {
        super(KEY, Map.of("type", extractTypeName(value)));
    }

    private static String extractTypeName(Object value) {
        if (value == null)
            return null;
        return value.getClass().getName();
    }

}
