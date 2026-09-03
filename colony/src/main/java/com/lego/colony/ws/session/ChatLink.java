package com.lego.colony.ws.session;

import com.lego.colony.ws.dto.SocketFrame;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import lombok.*;

/**
 * Một connection WebSocket vật lý đang sống (live) tới colony — KHÔNG còn đồng nghĩa với 1
 * subscriber logic nữa: từ khi harbor sharded link theo pod (dùng chung 1 link cho nhiều client
 * session), 1 {@code ChatLink} có thể mang nhiều {@link ChatSubscriber} (mỗi cái là 1 harbor
 * session cụ thể) cùng lúc — xem {@code SessionRegistry#subscriberFor}. {@code subscriberIds} giữ
 * lại đúng những harborSessionId nào đang "cưỡi" trên link này, để {@code ChatSessionManager#onClose}
 * biết cần gỡ đúng những subscriber nào khi link vật lý này chết.
 *
 * <p>Lưu ý: dù tên class server của package này trước đây có "Sockjs", thực chất đây là plain WebSocket, không phải SockJS.
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Setter
public class ChatLink {

  @EqualsAndHashCode.Include private final @NonNull String id;
  private final @NonNull String serverId;
  private final @NonNull ServerWebSocket socket;
  private volatile long lastSeenAt = System.currentTimeMillis();
  /** harborSessionId nào đang dùng chung link này — xem {@code SessionRegistry}. */
  private final Set<String> subscriberIds = ConcurrentHashMap.newKeySet();

  public ChatLink(@NonNull String id, @NonNull String serverId, @NonNull ServerWebSocket socket) {
    this.id = id;
    this.serverId = serverId;
    this.socket = socket;
  }

  public CompletionStage<Void> send(SocketFrame frame) {
    return send(frame.encode());
  }

  public CompletionStage<Void> send(Buffer data) {
    return socket
        .write(data) //
        .toCompletionStage();
  }

  public CompletionStage<Void> send(String data) {
    return socket
        .write(Buffer.buffer(data)) //
        .toCompletionStage();
  }

  public String getHeader(String headerName) {
    return socket.headers().get(headerName);
  }

  public void close() {
    socket.close();
  }

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
