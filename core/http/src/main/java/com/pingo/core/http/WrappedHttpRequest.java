package com.pingo.core.http;


import com.pingo.core.api.IApiKey;
import com.pingo.core.api.IRequest;
import com.pingo.core.message.file.LegoFileUpload;
import com.pingo.core.message.file.LegoHasFileUploads;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public class WrappedHttpRequest implements IRequest, LegoHasFileUploads {

    @Getter
    private final @NonNull IApiKey apiKey;

    @Getter(AccessLevel.PRIVATE)
    @Accessors(fluent = true)
    private final @NonNull RoutingContext context;

    @Getter
    private final Set<LegoFileUpload> fileUploads;

    WrappedHttpRequest(IApiKey apiKey, RoutingContext rc) {
        this.apiKey = apiKey;
        this.context = rc;
        var fileUploads = rc.fileUploads();
        this.fileUploads = (fileUploads == null || fileUploads.isEmpty()) ? Set.of() //
            : fileUploads.stream() //
            .map(HttpFileUpload::new) //
            .collect(Collectors.toUnmodifiableSet());
    }


    @Override
    public Buffer getBody() {
        return context.body().buffer();
    }

    @Override
    public String toString() {
        var request = context.request();
        var method = request.method().toString().toUpperCase();
        var sb = new StringBuilder() //
            .append("--------\n") //
            .append("HTTP:\n  ").append(method).append(" ").append(request.path()).append("\n") //
            .append("API KEY:\n  ").append(apiKey).append("\n") //
            .append("HEADERS:\n");

        var it = getHeaders().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            sb.append("  ") //
                .append(entry.getKey()) //
                .append(": ") //
                .append(entry.getValue()) //
                .append("\n");
        }

        sb.append("PARAMS:\n");
        it = getParams().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            sb.append("  ") //
                .append(entry.getKey()) //
                .append(": ") //
                .append(entry.getValue()) //
                .append("\n");
        }

        sb.append("BODY:\n  ") //
            .append(context.body().asString()) //
            .append("\n");

        return sb.append("--------") //
            .toString();
    }

    @Override
    public String getHeader(String name) {
        var request = context.request();
        return request.getHeader(name);
    }

    @Override
    public Iterable<Entry<String, String>> getHeaders() {
        var request = context.request();
        return request.headers();
    }

    @Override
    public String getParam(String name) {
        var request = context.request();
        return request.getParam(name);
    }

    @Override
    public Iterable<Entry<String, String>> getParams() {
        var request = context.request();
        return request.params();
    }

    @AllArgsConstructor
    private static class HttpFileUpload implements LegoFileUpload {

        private final @NonNull FileUpload original;

        @Override
        public String getName() {
            return original.name();
        }

        @Override
        public String getOriginalFileName() {
            return original.fileName();
        }

        @Override
        public String getUploadedFileName() {
            return original.uploadedFileName();
        }

        @Override
        public long getSize() {
            return original.size();
        }

        @Override
        public Charset getCharset() {
            return Charset.forName(original.charSet());
        }

        @Override
        public String getContentTransferEncoding() {
            return original.contentTransferEncoding();
        }

        @Override
        public String getContentType() {
            return original.contentType();
        }

        @Override
        public File getUploadedFile() {
            return new File(original.uploadedFileName());
        }

        @Override
        public String getFilePath() {
            return getUploadedFile().getPath();
        }

        @Override
        public String getAbsolutePath() {
            return getUploadedFile().getAbsolutePath();
        }
    }

}
