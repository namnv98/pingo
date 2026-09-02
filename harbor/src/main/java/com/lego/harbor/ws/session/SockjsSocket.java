package com.lego.harbor.ws.session;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;

import lombok.*;

/**
 * Connection SockJS công khai (public-facing) của một client, đi kèm nhiều {@link BackendLink}
 * (mỗi pod colony sở hữu ít nhất 1 conversation mà session này đang subscribe — có thể nhiều
 * conversation dùng chung 1 link nếu chúng cùng hash ra 1 pod).
 */
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SockjsSocket implements MessageSocket {

  @EqualsAndHashCode.Include private final @NonNull String id;
  private final @NonNull String serverId;
  private final @NonNull SockJSSocket socket;
  private UUID userId;
  private volatile long lastSeenAt = System.currentTimeMillis();

  /** Link thật xuống từng pod colony, keyed theo tên pod — dùng chung cho mọi conversation trên cùng pod. */
  private final Map<String, BackendLink> linksByPod = new ConcurrentHashMap<>();
  /** conversationId đang subscribe → tên pod hiện đang sở hữu nó (trỏ vào key của {@link #linksByPod}). */
  private final Map<UUID, String> podByConversation = new ConcurrentHashMap<>();

  /**
   * "Nhớ" lại memberUserIds từng biết cho mỗi conversationId — CẦN THIẾT để gửi lại mỗi lần
   * SUBSCRIBE (kể cả reconnect ngầm do đổi routing version): {@code ChannelMembershipRegistry}
   * bên colony là in-memory theo từng pod (xem quyết định thiết kế), nên khi conversation đổi
   * sang 1 pod MỚI (pod cũ restart/scale), pod mới hoàn toàn không biết membership — nếu không gửi
   * lại memberUserIds thì SUBSCRIBE sẽ bị từ chối vĩnh viễn dù client vẫn còn hợp lệ.
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

  public SockjsSocket(@NonNull String id, @NonNull String serverId, @NonNull SockJSSocket socket) {
    this.id = id;
    this.serverId = serverId;
    this.socket = socket;
  }

  public BackendLink getLinkForPod(String podName) {
    return linksByPod.get(podName);
  }

  /**
   * Đăng ký link mới cho 1 pod — CHỈ đóng link CŨ của CÙNG pod đó (nếu có), giữ nguyên mọi link
   * khác — bảo toàn đúng tính chất "không đóng link cũ tới khi link mới được xác nhận" (giờ áp
   * dụng per-pod thay vì per-session).
   */
  public void putLink(String podName, BackendLink link) {
    var previous = linksByPod.put(podName, link);
    if (previous != null && previous != link && !previous.isClosed()) {
      previous.close();
    }
  }

  public Collection<BackendLink> allLinks() {
    return linksByPod.values();
  }

  public void closeLink(String podName) {
    var link = linksByPod.remove(podName);
    if (link != null) {
      link.close();
    }
  }

  public void closeAllBackendLinks() {
    linksByPod.values().forEach(BackendLink::close);
    linksByPod.clear();
    podByConversation.clear();
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
    // Cố tình dùng overload write(String) chứ không phải write(Buffer): trên transport
    // "raw websocket" của SockJS, write(Buffer) gửi xuống WS frame kiểu BINARY — browser/JS
    // WebSocket mặc định (binaryType="blob") sẽ trả event.data là Blob thay vì string, khiến
    // JSON.parse(event.data) lỗi ngay lập tức phía client. write(String) gửi TEXT frame, JS
    // nhận được string sẵn sàng JSON.parse() được luôn. Đã verify lại bằng test thật (Node WebSocket client).
    return socket
        .write(data.toString()) //
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
    closeAllBackendLinks();
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
