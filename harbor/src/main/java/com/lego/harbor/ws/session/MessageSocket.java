package com.lego.harbor.ws.session;

import io.vertx.core.buffer.Buffer;
import java.io.Closeable;
import java.util.concurrent.CompletionStage;

public interface MessageSocket extends Closeable {

    String getId();

    CompletionStage<Void> send(Buffer data);

    String getHeader(String headerName);

    default void close() {
        // do nothing
    }

    default void cleanUpAfterClose() {
        // do nothing
    }
}
