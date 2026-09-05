package com.pingo.colony.ws.session;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sổ đăng ký 1 tầng: {@link ChatSession} = 1 gRPC stream. KHÔNG còn 1-1 với 1 user — từ khi harbor
 * chuyển sang dùng CHUNG 1 stream/pod cho mọi user cục bộ của nó (đúng cách Slack làm, xem
 * ARCHITECTURE.md mục 12), 1 {@code ChatSession} ở đây thực chất đại diện "1 kết nối từ 1 pod
 * harbor", có thể mang nhiều user khác nhau tuỳ frame ({@code from_user_id} lấy trực tiếp từ mỗi
 * frame, KHÔNG còn field {@code userId} gắn với session — xem {@code ChatSessionManager#handleMessage}).
 * Tách khỏi {@code ChatSessionManager} để {@code MessageDelivery} dùng chung, không đụng thẳng vào
 * map nội bộ.
 */
public class SessionRegistry {

  private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> sessionIdsByConversation = new ConcurrentHashMap<>();

  public void register(ChatSession session) {
    sessions.put(session.getId(), session);
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
