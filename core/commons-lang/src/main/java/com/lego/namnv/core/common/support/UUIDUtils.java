package com.lego.namnv.core.common.support;

import com.fasterxml.uuid.Generators;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UUIDUtils {

    public static UUID parseOrDefault(String string) {
        if (isNull(string)) {
            return null;
        }
        try {
            return UUID.fromString(string);
        } catch (Exception ex) {
            return null;
        }
    }

    public static List<UUID> parseOrDefaultEmpty(String string) {
        if (isNull(string)) {
            return Collections.emptyList();
        }
        try {
            return Arrays.stream(string.split(","))
                .map(UUID::fromString)
                .collect(Collectors.toList());
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    public static UUID timeBasedUuid() {
        return Generators.timeBasedGenerator().generate();
    }

    public static String timeBasedUuidAsString() {
        return timeBasedUuid().toString();
    }
}
