package com.pingo.harbor.ws.session;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

import lombok.*;

/**
 * Connection public-facing của 1 client. Giữ riêng 1 {@link BackendStream} cho MỖI pod colony nó
 * cần nói chuyện — không dùng chung với session khác (xem {@code BackendStreamGateway},
 * ARCHITECTURE.md mục 12): HTTP/2 tự multiplex nhiều stream trên 1 connection vật lý tới cùng pod.
 */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HarborSession implements MessageSocket {

  @EqualsAndHashCode.Include private final @NonNull String id;
  private final @NonNull String serverId;
  private final @NonNull SockJSSocket socket;
  private UUID userId;
  private volatile long lastSeenAt = System.currentTimeMillis();

  /** conversationId đang subscribe → tên pod hiện đang sở hữu nó. */
  private final Map<UUID, String> podByConversation = new ConcurrentHashMap<>();

  /** Tên pod colony → gRPC stream đang mở tới đúng pod đó cho CHÍNH session này (xem {@link BackendStream}). */
  private final Map<String, BackendStream> backendStreams = new ConcurrentHashMap<>();
  /** Single-flight: nhiều SUBSCRIBE/MESSAGE cùng cần mở stream tới 1 pod lần đầu chỉ connect đúng 1 lần. */
  private final Map<String, CompletableFuture<BackendStream>> connectingStreams = new ConcurrentHashMap<>();

  /**
   * "Nhớ" memberUserIds từng biết cho mỗi conversationId — cần để gửi lại mỗi lần SUBSCRIBE (kể cả
   * reconnect ngầm do đổi routing version), tránh SUBSCRIBE bị từ chối khi pod mới chưa biết membership.
   */
  private final Map<UUID, Set<UUID>> membersByConversation = new ConcurrentHashMap<>();

  public void rememberMembers(UUID conversationId, Collection<UUID> members) {
    if (members == null || members.isEmpty()) {
      return;
    }
    membersByConversation.computeIfAbsent(conversationId, key -> ConcurrentHashMap.newKeySet()).addAll(members);
  }

  public List<UUID> getRememberedMembers(UUID conversationId) {
    var members = membersByConversation.get(conversationId);
    return members == null ? List.of() : List.copyOf(members);
  }

  public HarborSession(@NonNull String id, @NonNull String serverId, @NonNull SockJSSocket socket) {
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
    // Cố tình dùng write(String), không phải write(Buffer): Buffer gửi WS frame BINARY, browser
    // trả event.data là Blob thay vì string, JSON.parse() lỗi ngay. write(String) gửi TEXT frame.
    return socket
        .write(data.toString())
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
