package com.lego.colony.ws.session;

import com.lego.colony.ws.dto.SocketFrame;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import lombok.*;

/**
 * Một connection WebSocket đang sống (live) tới colony.
 * Lưu ý: dù tên class server của package này trước đây có "Sockjs", thực chất đây là plain WebSocket, không phải SockJS.
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@AllArgsConstructor
@Setter
public class ChatSession implements MessageChatSocket {

  @EqualsAndHashCode.Include private final @NonNull String id;
  private final @NonNull String serverId;
  private final @NonNull ServerWebSocket socket;
  private UUID userId;
  private String ip;
  private int currentVersion;
  private volatile long lastSeenAt = System.currentTimeMillis();
  /** conversationId nao dang duoc subscribe tren link nay -- xem SessionRegistry.subscribe/remove. */
  private final Set<UUID> conversationIds = ConcurrentHashMap.newKeySet();

  public ChatSession(@NonNull String id, @NonNull String serverId, @NonNull ServerWebSocket socket) {
    this.id = id;
    this.serverId = serverId;
    this.socket = socket;
  }

  public CompletionStage<Void> send(SocketFrame frame) {
    return send(frame.encode());
  }

  @Override
  public CompletionStage<Void> send(Buffer data) {
    return socket
        .write(data) //
        .toCompletionStage();
  }

  @Override
  public CompletionStage<Void> send(String data) {
    return socket
        .write(Buffer.buffer(data)) //
        .toCompletionStage();
  }

  @Override
  public String getHeader(String headerName) {
    return socket.headers().get(headerName);
  }

  @Override
  public void close() {
    socket.close();
  }

  @Override
  public void cleanUpAfterClose() {
    try {
      socket.handler(null);
      socket.drainHandler(null);
      socket.closeHandler(null);
      socket.exceptionHandler(null);
    } catch (Exception e) {
      // socket đã bị đóng/huỷ rồi, không còn gì để dọn nữa nên bỏ qua exception này
    }
  }
}
