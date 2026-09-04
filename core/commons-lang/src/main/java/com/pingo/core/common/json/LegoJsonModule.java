package com.pingo.core.common.json;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.UUIDDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.pingo.core.common.json.address.L4AddressDeserializer;
import com.pingo.core.common.json.address.L4AddressSerializer;
import com.pingo.core.common.json.address.L6AddressDeserializer;
import com.pingo.core.common.json.address.L6AddressSerializer;
import com.pingo.core.common.json.time.*;
import com.pingo.core.common.json.uri.ParsedUriDeserializer;
import com.pingo.core.common.json.uuid.UuidSerializer;
import com.pingo.core.common.json.vertx.JsonObjectDeserializer;
import com.pingo.core.common.support.L4Address;
import com.pingo.core.common.support.L6Address;
import com.pingo.core.common.support.ParsedUri;
import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
public enum LegoJsonModule {

    ADDRESS(new SimpleModule("lego_network_address") //
            .addSerializer(new L4AddressSerializer()) //
            .addDeserializer(L4Address.class, new L4AddressDeserializer()) //
            .addSerializer(new L6AddressSerializer()) //
            .addDeserializer(L6Address.class, new L6AddressDeserializer())),
    UUID(new SimpleModule("lego_uuid") //
            .addSerializer(new UuidSerializer()) //
            .addDeserializer(UUID.class, new UUIDDeserializer())),
    URI(new SimpleModule("lego_uri") //
            .addDeserializer(ParsedUri.class, new ParsedUriDeserializer())),
    TEMPORAL(new SimpleModule("lego_temporal") //
            .addSerializer(new InstantSerializer()) //
            .addDeserializer(Instant.class, new InstantDeserializer()) //
            .addSerializer(new LocalDateSerializer()) //
            .addDeserializer(LocalDate.class, new LocalDateDeserializer()) //
            .addSerializer(new LocalDateTimeSerializer()) //
            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer()) //
            .addSerializer(new OffsetDateTimeSerializer()) //
            .addDeserializer(OffsetDateTime.class, new OffsetDateTimeDeserializer()) //
            .addSerializer(new ZonedDateTimeSerializer()) //
            .addDeserializer(ZonedDateTime.class, new ZonedDateTimeDeserializer()) //
            .addSerializer(new ZoneIdSerializer()) //
            .addDeserializer(ZoneId.class, new ZoneIdDeserializer())),
    VERTX(new SimpleModule("lego_vertx_json") //
            .addDeserializer(JsonObject.class, new JsonObjectDeserializer()) //
            // .addSerializer(new JsonObjectSerializer())
            );

    public static final List<Module> ALL_MODULES = Arrays.stream(values()) //
            .map(LegoJsonModule::getModule) //
            .toList();

    public static void registerAllWith(ObjectMapper... mappers) {
        for (var value : values())
            value.registerWith(mappers);
    }

    @Getter
    private final Module module;

    public void registerWith(ObjectMapper... mappers) {
        if (module == null || mappers == null || mappers.length == 0)
            return;
        for (var mapper : mappers)
            mapper.registerModule(module);
    }
}
