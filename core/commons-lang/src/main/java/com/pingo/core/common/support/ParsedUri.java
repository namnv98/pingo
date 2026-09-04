package com.pingo.core.common.support;

import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class ParsedUri {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private String scheme;
    private String databaseType;
    private String user;
    private String password;
    @Singular
    private List<String> addresses;
    private String path;
    @Singular
    private Map<String, String> params;

    public List<L4Address> getParsedAddresses(int defaultPort) {
        return addresses.stream() //
                .map(address -> {
                    var sepIndex = address.indexOf(":");
                    var host = sepIndex == -1 ? address : address.substring(0, sepIndex);
                    var port = sepIndex == -1 ? defaultPort : Integer.parseInt(address.substring(sepIndex + 1));
                    return new L4Address(host, port);
                }) //
                .collect(Collectors.toUnmodifiableList());
    }

    public static ParsedUri parse(String uriString) {

        var uri = URI.create(uriString);
        var rawPath = uri.getPath();

        var builder = builder() //
                .databaseType(uri.getScheme().split("-")[0])//
                .scheme(uri.getScheme().split("-")[1]) //
                .path(isNull(rawPath) || rawPath.isBlank() ? "" : uri.getPath().substring(1));

        populateAuthority(uri, builder);
        populateParams(uri, builder);

        return builder.build();
    }

    private static void populateAuthority(URI uri, ParsedUriBuilder builder) {
        var authority = uri.getAuthority();
        if (isNull(authority) || authority.isBlank())
            return;
        var sepIndex = authority.indexOf(":");
        var hostIndex = authority.lastIndexOf("@");
        if (hostIndex == -1) {
            parseAddresses(authority, builder);
            return;
        }
        builder.user(authority.substring(0, sepIndex)) //
                .password(authority.substring(sepIndex + 1, hostIndex));

        parseAddresses(authority.substring(hostIndex + 1), builder);
    }

    private static void parseAddresses(String addressesStr, ParsedUriBuilder builder) {
        var addresses = addressesStr.split(",");
        for (var address : addresses)
            builder.address(address);
    }

    private static void populateParams(URI uri, ParsedUriBuilder builder) {
        var parameters = new QueryStringDecoder("?" + uri.getQuery()).parameters();
        for (var item : parameters.entrySet()) {
            var value = item.getValue().get(0); // get only first params
            builder.param(item.getKey(), value);
        }
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();

        sb.append(scheme).append("://");

        if (user != null)
            sb.append(URLEncoder.encode(user, UTF8));
        if (password != null)
            sb.append(":").append(URLEncoder.encode(password, UTF8));

        if (password != null || user != null)
            sb.append("@");

        var it = addresses.iterator();
        if (it.hasNext()) {
            sb.append(it.next());
            while (it.hasNext())
                sb.append(",").append(it.next());
        }

        if (path != null && !path.isBlank()) {
            if (!path.startsWith("/"))
                sb.append("/");
            sb.append(path);
        }

        if (params != null) {
            var pit = params.entrySet().iterator();
            if (pit.hasNext()) {
                var e = pit.next();
                appendParams(sb, e.getKey(), e.getValue(), true);
                while (pit.hasNext()) {
                    e = pit.next();
                    appendParams(sb, e.getKey(), e.getValue(), false);
                }
            }
        }

        return sb.toString();
    }

    private void appendParams(StringBuilder sb, String key, String value, boolean isFirst) {
        if (key == null || value == null)
            return;
        sb.append(isFirst ? "?" : "&") //
                .append(URLEncoder.encode(key, UTF8)) //
                .append("=") //
                .append(URLEncoder.encode(value, UTF8));

    }
}
