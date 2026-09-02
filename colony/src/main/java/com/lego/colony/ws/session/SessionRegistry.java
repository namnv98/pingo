package com.lego.colony.ws.session;

import io.vertx.core.http.ServerWebSocket;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sổ đăng ký (registry) các {@link ChatSession} đang sống trên node này, tra cứu được theo cả
 * session id lẫn user id (1 user có thể có nhiều session/tab cùng lúc). Tách riêng khỏi
 * {@code com.lego.colony.ws.ChatSessionManager} để {@code com.lego.colony.ws.delivery.MessageDelivery}
 * dùng chung, không phải đụng thẳng vào 2 map nội bộ.
 */
public class SessionRegistry {

  private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, List<String>> sessionIdsByUser = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> sessionIdsByConversation = new ConcurrentHashMap<>();

  public ChatSession register(String id, String serverId, ServerWebSocket socket) {
    var session = new ChatSession(id, serverId, socket);
    sessions.put(id, session);
    return session;
  }

  /** Gắn session với 1 user id sau khi AUTH thành công (1 user có thể có nhiều session). */
  public void attachUser(ChatSession session, UUID userId) {
    session.setUserId(userId);
    sessionIdsByUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(session.getId());
  }

  /**
   * Đăng ký session này là subscriber cục bộ của 1 conversationId — gọi sau khi SUBSCRIBE hợp lệ
   * (đã check membership, xem ChatSessionManager.handleSubscribe).
   */
  public void subscribe(ChatSession session, UUID conversationId) {
    sessionIdsByConversation.computeIfAbsent(conversationId, key -> ConcurrentHashMap.newKeySet()).add(session.getId());
    session.getConversationIds().add(conversationId);
  }

  /** Gỡ session khỏi registry (khi socket đóng) — trả về session đã gỡ, hoặc null nếu đã gỡ trước đó rồi. */
  public ChatSession remove(String sessionId) {
    var session = sessions.remove(sessionId);
    if (session == null) {
      return null;
    }
    var userId = session.getUserId();
    if (userId != null) {
      sessionIdsByUser.computeIfPresent(
          userId,
          (key, ids) -> {
            ids.remove(sessionId);
            return ids.isEmpty() ? null : ids;
          });
    }
    for (var conversationId : session.getConversationIds()) {
      sessionIdsByConversation.computeIfPresent(
          conversationId,
          (key, ids) -> {
            ids.remove(sessionId);
            return ids.isEmpty() ? null : ids;
          });
    }
    return session;
  }

  /** Mọi session cục bộ (local) của 1 user trên node này — rỗng nếu user chưa từng AUTH ở đây. */
  public List<ChatSession> sessionsOf(UUID userId) {
    var ids = sessionIdsByUser.get(userId);
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream().map(sessions::get).filter(Objects::nonNull).toList();
  }

  /** Mọi session cục bộ (local) đang subscribe 1 conversationId trên node này. */
  public List<ChatSession> sessionsOfConversation(UUID conversationId) {
    var ids = sessionIdsByConversation.get(conversationId);
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream().map(sessions::get).filter(Objects::nonNull).toList();
  }

  /** Toàn bộ session đang sống, dùng cho việc quét idle định kỳ. Trả về bản copy, an toàn để iterate. */
  public List<ChatSession> all() {
    return List.copyOf(sessions.values());
  }
}
