package com.lego.colony.ws.session;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sổ đăng ký (registry) 1 tầng: {@link ChatSession} (1 gRPC stream = 1 subscriber logic, xem
 * {@link ChatSession}) — không còn tách link vật lý/subscriber như thời N-shard-link nữa. Tra cứu
 * được theo cả userId lẫn conversationId (1 user có thể có nhiều session/tab cùng lúc). Tách riêng
 * khỏi {@code com.lego.colony.ws.ChatSessionManager} để {@code com.lego.colony.ws.delivery.MessageDelivery}
 * dùng chung, không phải đụng thẳng vào các map nội bộ.
 */
public class SessionRegistry {

  private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, List<String>> sessionIdsByUser = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> sessionIdsByConversation = new ConcurrentHashMap<>();

  public void register(ChatSession session) {
    sessions.put(session.getId(), session);
  }

  /** Gắn session với 1 user id sau khi SUBSCRIBE thành công lần đầu. */
  public void attachUser(ChatSession session, UUID userId) {
    session.setUserId(userId);
    sessionIdsByUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(session.getId());
  }

  /** Đăng ký session này là subscriber cục bộ của 1 conversationId — gọi sau khi SUBSCRIBE hợp lệ (đã check membership). */
  public void subscribe(ChatSession session, UUID conversationId) {
    sessionIdsByConversation.computeIfAbsent(conversationId, key -> ConcurrentHashMap.newKeySet()).add(session.getId());
    session.getConversationIds().add(conversationId);
  }

  /** Gỡ session khỏi registry (stream kết thúc/lỗi, hoặc bị đóng vì idle) — vô hại nếu gọi 2 lần cho cùng 1 id. */
  public void remove(String sessionId) {
    var session = sessions.remove(sessionId);
    if (session == null) {
      return;
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
  }

  /** Mọi session cục bộ (local) đang subscribe 1 conversationId trên node này. */
  public List<ChatSession> subscribersOfConversation(UUID conversationId) {
    var ids = sessionIdsByConversation.get(conversationId);
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream().map(sessions::get).filter(Objects::nonNull).toList();
  }

  /** Toàn bộ session đang sống, dùng cho việc quét idle định kỳ. Trả về bản copy, an toàn để iterate. */
  public List<ChatSession> allSessions() {
    return List.copyOf(sessions.values());
  }
}
