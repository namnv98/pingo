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
 * Sổ đăng ký (registry) 2 tầng: {@link ChatLink} (connection vật lý đang sống) và
 * {@link ChatSubscriber} (subscriber logic, 1:1 với 1 harbor session — nhiều subscriber có thể
 * cùng dùng chung 1 link, xem {@link ChatLink}). Tra cứu được subscriber theo cả userId lẫn
 * conversationId (1 user có thể có nhiều subscriber/tab cùng lúc). Tách riêng khỏi
 * {@code com.lego.colony.ws.ChatSessionManager} để {@code com.lego.colony.ws.delivery.MessageDelivery}
 * dùng chung, không phải đụng thẳng vào các map nội bộ.
 */
public class SessionRegistry {

  private final Map<String, ChatLink> links = new ConcurrentHashMap<>();
  private final Map<String, ChatSubscriber> subscribers = new ConcurrentHashMap<>();
  private final Map<UUID, List<String>> subscriberIdsByUser = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> subscriberIdsByConversation = new ConcurrentHashMap<>();

  public ChatLink registerLink(String id, String serverId, ServerWebSocket socket) {
    var link = new ChatLink(id, serverId, socket);
    links.put(id, link);
    return link;
  }

  /**
   * Trả về subscriber logic của {@code harborSessionId}, tạo mới nếu chưa từng thấy trên node này.
   * Nếu subscriber đã tồn tại nhưng đang gắn với 1 link KHÁC (harbor phía đó vừa reconnect sang 1
   * physical link mới, cùng shard key), rebind sang link mới — gỡ khỏi {@code subscriberIds} của
   * link cũ, thêm vào link mới — để việc dọn dẹp khi 1 trong 2 link đóng luôn đúng.
   */
  public ChatSubscriber subscriberFor(String harborSessionId, ChatLink link) {
    var subscriber = subscribers.computeIfAbsent(harborSessionId, id -> new ChatSubscriber(id, link));
    if (subscriber.getLink() != link) {
      subscriber.getLink().getSubscriberIds().remove(harborSessionId);
      subscriber.setLink(link);
    }
    link.getSubscriberIds().add(harborSessionId);
    return subscriber;
  }

  /** Gắn subscriber với 1 user id sau khi SUBSCRIBE thành công lần đầu (1 user có thể có nhiều subscriber). */
  public void attachUser(ChatSubscriber subscriber, UUID userId) {
    subscriber.setUserId(userId);
    subscriberIdsByUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>()).add(subscriber.getId());
  }

  /**
   * Đăng ký subscriber này là subscriber cục bộ của 1 conversationId — gọi sau khi SUBSCRIBE hợp lệ
   * (đã check membership, xem ChatSessionManager.handleSubscribe).
   */
  public void subscribe(ChatSubscriber subscriber, UUID conversationId) {
    subscriberIdsByConversation.computeIfAbsent(conversationId, key -> ConcurrentHashMap.newKeySet()).add(subscriber.getId());
    subscriber.getConversationIds().add(conversationId);
  }

  /** Gỡ subscriber khỏi registry (khi harbor báo session đóng, hoặc link vật lý của nó chết) — trả về subscriber đã gỡ, hoặc null nếu đã gỡ trước đó rồi. */
  public ChatSubscriber removeSubscriber(String harborSessionId) {
    var subscriber = subscribers.remove(harborSessionId);
    if (subscriber == null) {
      return null;
    }
    subscriber.getLink().getSubscriberIds().remove(harborSessionId);
    var userId = subscriber.getUserId();
    if (userId != null) {
      subscriberIdsByUser.computeIfPresent(
          userId,
          (key, ids) -> {
            ids.remove(harborSessionId);
            return ids.isEmpty() ? null : ids;
          });
    }
    for (var conversationId : subscriber.getConversationIds()) {
      subscriberIdsByConversation.computeIfPresent(
          conversationId,
          (key, ids) -> {
            ids.remove(harborSessionId);
            return ids.isEmpty() ? null : ids;
          });
    }
    return subscriber;
  }

  /** Gỡ 1 link vật lý đã chết khỏi registry — gọi SAU khi mọi subscriber của nó đã được gỡ (xem ChatSessionManager.onClose). */
  public void removeLink(String linkId) {
    links.remove(linkId);
  }

  /** Mọi subscriber cục bộ (local) đang subscribe 1 conversationId trên node này. */
  public List<ChatSubscriber> subscribersOfConversation(UUID conversationId) {
    var ids = subscriberIdsByConversation.get(conversationId);
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream().map(subscribers::get).filter(Objects::nonNull).toList();
  }

  /** Toàn bộ link vật lý đang sống, dùng cho việc quét idle định kỳ. Trả về bản copy, an toàn để iterate. */
  public List<ChatLink> allLinks() {
    return List.copyOf(links.values());
  }
}
