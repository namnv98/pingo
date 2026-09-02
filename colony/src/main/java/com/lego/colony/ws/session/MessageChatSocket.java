package com.lego.colony.ws.session;

import io.vertx.core.buffer.Buffer;
import java.io.Closeable;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface MessageChatSocket extends Closeable {

  String getId();

  UUID getUserId();

  CompletionStage<Void> send(Buffer data);

  CompletionStage<Void> send(String data);

  String getHeader(String headerName);

  default void close() {
    // do nothing
  }

  default void cleanUpAfterClose() {
    // do nothing
  }
}
