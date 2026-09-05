package com.pingo.harbor.ws.session;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

import lombok.*;

/**
 * Connection public-facing của 1 client — WebSocket thuần (Vert.x native, không qua SockJS, xem
 * {@code LegoSocketServer}). KHÔNG còn giữ gRPC stream riêng xuống colony (xem
 * {@code com.pingo.harbor.ws.backend.BackendStream}, ARCHITECTURE.md mục 12) — stream đó giờ dùng
 * CHUNG cho mọi session trên pod harbor này, sống ở {@code BackendStreamGateway} (gateway-level),
 * đúng cách Slack làm ("GS subscribes to all channel servers... asynchronously", GS không mở 1 kết
 * nối/user, mà 1 kết nối/channel-server dùng chung).
 */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HarborSession implements MessageSocket {

  @EqualsAndHashCode.Include private final @NonNull String id;
  private final @NonNull String serverId;
  private final @NonNull ServerWebSocket socket;
  private UUID userId;
  private volatile long lastSeenAt = System.currentTimeMillis();

  /** conversationId đang subscribe → tên pod hiện đang sở hữu nó. */
  private final Map<UUID, String> podByConversation = new ConcurrentHashMap<>();

  public HarborSession(@NonNull String id, @NonNull String serverId, @NonNull ServerWebSocket socket) {
    this.id = id;
    this.serverId = serverId;
    this.socket = socket;
  }

  public String getPodFor(UUID conversationId) {
    return podByConversation.get(conversationId);
  }

  public void setPodFor(UUID conversationId, String podName) {
    podByConversation.put(conversationId, podName);
  }

  public Set<UUID> subscribedConversationIds() {
    return podByConversation.keySet();
  }

  @Override
  public CompletionStage<Void> send(Buffer data) {
    // Cố tình dùng writeTextMessage(), không phải writeBinaryMessage(): browser trả event.data là
    // Blob thay vì string cho frame BINARY, JSON.parse() lỗi ngay. writeTextMessage() gửi TEXT frame.
    return socket
        .writeTextMessage(data.toString())
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
