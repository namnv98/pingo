package com.lego.namnv.core.boot.start.yaml;

import io.vertx.core.json.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import org.yaml.snakeyaml.Yaml;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class YamlConfigReader<ConfigType> {

    private final Class<ConfigType> type;

    public static <T> YamlConfigReader<T> forType(Class<T> type) {
        return new YamlConfigReader<T>(type);
    }

    public ConfigType readYaml(String yamlFilePath) throws Exception {
        return readYaml(new File(yamlFilePath));
    }

    public ConfigType readYaml(File propertiesFile) throws Exception {
        try (var inputStream = new FileInputStream(propertiesFile)) {
            return readYaml(inputStream);
        }
    }

    public ConfigType readYaml(InputStream inputStream) throws Exception {
        var data = inputStream.readAllBytes();
        return resolveYaml(new String(data));
    }

    public ConfigType resolveYaml(String content) throws Exception {
        var resolvedData = EnvironmentVariables.resolve(content);
        var yaml = new Yaml();
        //TODO:
        var data = yaml.loadAs(resolvedData, Map.class);
        return JsonObject.mapFrom(data).mapTo(type);
//        return yaml.loadAs(resolvedData, type);
    }
}

