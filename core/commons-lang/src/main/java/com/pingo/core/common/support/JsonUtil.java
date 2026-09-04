package com.pingo.core.common.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class JsonUtil {
    private static final String STRING_NULL = "null";
    private static final ThreadLocal<ObjectMapper> objectMapper =
        ThreadLocal.withInitial(
            () -> {
                ObjectMapper om = new ObjectMapper();
                om.findAndRegisterModules();
                om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                return om;
            });

    private static final ThreadLocal<ObjectMapper> objectMapperIgnoreNull =
        ThreadLocal.withInitial(
            () -> {
                ObjectMapper om = new ObjectMapper();
                om.findAndRegisterModules();
                om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
                om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                return om;
            });

    public static String stringify(Object object) {
        if (object == null) {
            return STRING_NULL;
        }
        try {
            return objectMapper.get().writeValueAsString(object);
        } catch (Exception ignored) {
            return "*{}";
        }
    }

    @SneakyThrows
    public static <T> T parse(String text, Class<T> valueType) {
        return objectMapper.get().readValue(text, valueType);
    }

    public static String toJson(Object object) {
        try {
            return objectMapper.get().writeValueAsString(object);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toJson(Object object, boolean ignoreNulls) {
        try {
            if (ignoreNulls) {
                return objectMapperIgnoreNull.get().writeValueAsString(object);
            } else {
                return objectMapper.get().writeValueAsString(object);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}