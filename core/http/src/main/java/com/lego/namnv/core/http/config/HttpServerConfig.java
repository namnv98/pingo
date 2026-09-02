package com.lego.namnv.core.http.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class HttpServerConfig {

    private String host = "0.0.0.0";
    private int port = 8080;
    private String tags;
    private String path;
    private String serviceName;
    private Map<String, Object> headers;
    private boolean printStackTrace = false;

//    public String[] getTags() {
//        return tags.split("\\s*,\\s*");
//    }
}

