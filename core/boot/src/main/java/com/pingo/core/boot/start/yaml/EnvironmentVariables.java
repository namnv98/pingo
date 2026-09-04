package com.pingo.core.boot.start.yaml;

import com.pingo.core.boot.start.yaml.ex.PrebootException;
import com.pingo.core.boot.start.yaml.ex.StringGeneratorNotFoundException;
import lombok.NonNull;
import lombok.extern.log4j.Log4j2;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.System.*;

@Log4j2
public class EnvironmentVariables {

    private static final Pattern ENV_VAR_PATTERN =
            Pattern.compile("(?i)\\$\\{([a-z0-9_]+)(\\s*\\|(.+)\\s*)?\\}");

    private static final Pattern GENERATOR_PATTERN = Pattern.compile("(?i)\\$([a-z0-9_]+)");

    public static Properties resolve(Properties properties) {
        var propertiesResult = new Properties();
        properties.forEach((key, value) -> propertiesResult.put(key, resolve((String) value)));
        return propertiesResult;
    }

    public static String resolve(String input) {
        var resolved = input;
        resolved =
                resolve(resolved, ENV_VAR_PATTERN.matcher(input), EnvironmentVariables::resolveEnvVar);
        resolved =
                resolve(resolved, GENERATOR_PATTERN.matcher(resolved), EnvironmentVariables::generateValue);
        return resolved;
    }

    private static String resolve(String input, Matcher matcher, Function<String, String> resolver) {

        var sb = new StringBuilder();

        var lastIndex = 0;
        while (matcher.find()) {
            var start = matcher.start();
            var end = matcher.end();

            var envName = matcher.group(1);
            var envValue = resolver.apply(envName);
            if (envValue == null) {
                if (Objects.nonNull(matcher.group(2))) {
                    envValue = matcher.group(3);
                } else {
                    log.warn("Environment variable `{}` cannot be resolved", envName);
                    envValue = input.substring(start, end);
                }
            }

            if (start > lastIndex) sb.append(input.substring(lastIndex, start));

            sb.append(envValue);

            lastIndex = end;
        }

        if (lastIndex < input.length()) sb.append(input.substring(lastIndex));

        return sb.toString().trim();
    }

    private static String resolveEnvVar(@NonNull String envName) {
        return Optional.ofNullable(getenv(envName)).orElseGet(() -> getProperty(envName, null));
    }

    private static String generateValue(String name) {
        var generator = StringGeneratorRegistry.getInstance().lookup(name);
        if (generator == null) throw new StringGeneratorNotFoundException(name);
        return generator.generate();
    }

    public static void setSystemProperties(File file) {
        try (var input = new FileInputStream(file)) {
            setSystemProperties(input);
        } catch (Exception e) {
            throw new PrebootException("Error while loading file", e);
        }
    }

    public static void setSystemProperties(InputStream input) {
        var props = new Properties();
        try {
            props.load(input);
        } catch (IOException e) {
            throw new PrebootException("Error while loading input stream", e);
        }
        setSystemProperties(props);
    }

    public static void setSystemProperties(Properties props) {
        props.entrySet().stream() //
                .filter(entry -> entry.getValue() != null) //
                .forEach(entry -> setProperty(entry.getKey().toString(), entry.getValue().toString()));
    }
}
