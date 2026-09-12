package com.pingo.harbor.ws.routing;

import com.pingo.connector.Payload;
import com.pingo.connector.PingoConnector;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.discovery.router.RoutingVersionTracker;
import com.pingo.harbor.ws.backend.BackendStreamGateway;
import com.pingo.harbor.ws.dto.MessageType;
import com.pingo.harbor.ws.dto.SocketFrame;
import com.pingo.harbor.ws.session.HarborSession;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Đồng bộ chung với beacon nằm ở {@link RoutingVersionTracker} — lớp này lo phần riêng của harbor:
 * khi có version mới, di chuyển session đã authenticate sang đúng node colony ({@link #onSignalingChanged}),
 * và phản ứng broadcast "user vừa được thêm vào conversation" từ colony ({@link #onMembershipChanged}).
 */
@Slf4j
public class RoutingVersionSync extends RoutingVersionTracker {

  /** Địa chỉ EventBus colony broadcast "user vừa được thêm vào conversation" (xem {@code ChatSessionManager#publishMembershipChanged}). */
  private static final String MEMBERSHIP_CHANGED_ADDRESS = "conversation_membership_changed";
  /** Địa chỉ EventBus hall broadcast "conversation X vừa bị xoá hẳn" (xem {@code HallApiHandlers#deleteConversation}). */
  private static final String CONVERSATION_DELETED_ADDRESS = "conversation_deleted";
  /** Địa chỉ EventBus hall broadcast "user X vừa bị xoá khỏi conversation Y" (kick hoặc tự rời, xem {@code HallApiHandlers#removeConversationMember}). */
  private static final String MEMBER_REMOVED_ADDRESS = "conversation_member_removed";
  /** Địa chỉ EventBus harbor tự publish (chính pod này hoặc pod khác) lúc 1 user đổi trạng thái online/offline, xem {@code HarborSessionManager#broadcastPresenceChange}. */
  private static final String PRESENCE_ADDRESS = "user_presence_changed";

  private final BackendStreamGateway backendStreamGateway;
  private final Map<String, HarborSession> sessions;
  private final BiConsumer<HarborSession, SocketFrame> relayToClient;

  public RoutingVersionSync(
      Vertx vertx, PingoConnector connector, BackendStreamGateway backendStreamGateway, Map<String, HarborSession> sessions,
      BiConsumer<HarborSession, SocketFrame> relayToClient) {
    super(vertx, connector);
    this.backendStreamGateway = backendStreamGateway;
    this.sessions = sessions;
    this.relayToClient = relayToClient;
    vertx.eventBus().consumer(MEMBERSHIP_CHANGED_ADDRESS, this::onMembershipChanged);
    vertx.eventBus().consumer(CONVERSATION_DELETED_ADDRESS, this::onConversationDeleted);
    vertx.eventBus().consumer(MEMBER_REMOVED_ADDRESS, this::onMemberRemoved);
    vertx.eventBus().consumer(PRESENCE_ADDRESS, this::onPresenceChanged);
  }

  @Override
  protected void onSignalingChanged(Message<JsonObject> jsonObjectMessage) {
    Payload payload = jsonObjectMessage.body().mapTo(Payload.class);
    var newVersion = payload.getVersion();
    if (newVersion <= 0) {
      return;
    }
    log.info(
        "nhan gossip tu beacon: routing table doi sang version {}, {} thay doi colony destination trong lan nay",
        newVersion,
        payload.getAllElements().size());
    connector
        .addDestinationChangeEvent(newVersion, payload.getAllElements())
        .thenCompose(unused -> reconnectSessionsToNewVersion(newVersion))
        .thenAccept(unused -> currentVersion = newVersion)
        .exceptionally(
            throwable -> {
              log.error("failed to apply beacon routing version {}", newVersion, throwable);
              return null;
            });
  }

  /**
   * Nhận broadcast "user X vừa được thêm vào conversationId" — chỉ pod đang giữ session sống của X
   * mới phản ứng. Subscribe ngầm hộ rồi relay CONVERSATION_ADDED để client cập nhật UI. Nếu X không
   * online ở pod nào lúc này thì không mất gì — chỉ là tối ưu "cho nhanh", không phải đường đảm bảo duy nhất.
   */
  private void onMembershipChanged(Message<JsonObject> message) {
    var body = message.body();
    UUID conversationId;
    try {
      conversationId = UUID.fromString(body.getString("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    var newMemberUserIds = toUuidSet(body.getJsonArray("newMemberUserIds"));
    if (newMemberUserIds.isEmpty()) {
      return;
    }

    for (var session : sessions.values()) {
      if (session.getUserId() == null || !newMemberUserIds.contains(session.getUserId())) {
        continue;
      }
      backendStreamGateway
          .wakeSubscribe(session, conversationId, currentVersion)
          .thenAccept(
              unused ->
                  relayToClient.accept(
                      session,
                      SocketFrame.builder()
                          .type(MessageType.CONVERSATION_ADDED)
                          .id(UUIDUtils.timeBasedUuidAsString())
                          .conversationId(conversationId.toString())
                          .ts(System.currentTimeMillis())
                          .build()))
          .exceptionally(
              ex -> {
                log.warn("failed to wake-subscribe session {} to newly-added conversation {}", session.getId(), conversationId, ex);
                return null;
              });
    }
  }

  /**
   * Nhận broadcast "conversationId X vừa bị xoá hẳn" (xem {@code HallApiHandlers#deleteConversation})
   * — chỉ pod đang giữ session có subscribe conversationId đó mới phản ứng: quên routing (xem
   * {@link HarborSession#forgetConversation}, tránh lần đổi routing-version sau còn cố reconnect 1
   * conversation không còn tồn tại) rồi relay {@code CONVERSATION_DELETED} để client tự dọn UI.
   * KHÔNG chủ động đóng stream gRPC xuống colony (không có unsubscribe-riêng-1-conversation trên 1
   * stream dùng chung nhiều conversation, xem BackendStreamGateway) -- chấp nhận được vì DB đã xoá
   * xong (nguồn sự thật), phần routing còn sót trên colony chỉ là bộ nhớ đệm vô hại, tự dọn khi
   * session đóng hẳn (xem {@code BackendStreamGateway#closeAllStreams}).
   */
  private void onConversationDeleted(Message<JsonObject> message) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(message.body().getString("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    var finalConversationId = conversationId;
    for (var session : sessions.values()) {
      if (!session.subscribedConversationIds().contains(finalConversationId)) {
        continue;
      }
      session.forgetConversation(finalConversationId);
      relayToClient.accept(
          session,
          SocketFrame.builder()
              .type(MessageType.CONVERSATION_DELETED)
              .id(UUIDUtils.timeBasedUuidAsString())
              .conversationId(finalConversationId.toString())
              .ts(System.currentTimeMillis())
              .build());
    }
  }

  /**
   * Nhận broadcast "user X vừa bị xoá khỏi conversationId Y" (kick HOẶC tự rời — cùng 1 đường,
   * xem {@code HallApiHandlers#removeConversationMember}) — CHỈ phản ứng với session của ĐÚNG user
   * X (khác {@link #onConversationDeleted}, lọc theo ai đang subscribe conversation đó — ở đây
   * chính X có thể vẫn còn subscribe conversation trên gRPC stream tới lúc bị dọn tự nhiên, không
   * quan trọng vì UI đã bị dọn). X có thể có nhiều session/tab đang mở, lặp hết. Tái dùng nguyên
   * {@code MessageType.CONVERSATION_DELETED} — với X, hiệu ứng đúng là "conversation biến mất khỏi
   * UI của tôi", frontend đã xử lý sẵn type này (xem {@code removeConversationLocally}).
   */
  private void onMemberRemoved(Message<JsonObject> message) {
    var body = message.body();
    UUID conversationId;
    UUID removedUserId;
    try {
      conversationId = UUID.fromString(body.getString("conversationId"));
      removedUserId = UUID.fromString(body.getString("removedUserId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    var finalConversationId = conversationId;
    for (var session : sessions.values()) {
      if (!removedUserId.equals(session.getUserId())) {
        continue;
      }
      session.forgetConversation(finalConversationId);
      relayToClient.accept(
          session,
          SocketFrame.builder()
              .type(MessageType.CONVERSATION_DELETED)
              .id(UUIDUtils.timeBasedUuidAsString())
              .conversationId(finalConversationId.toString())
              .ts(System.currentTimeMillis())
              .build());
    }
  }

  /**
   * Nhận broadcast "user X vừa đổi trạng thái online/offline" (xem
   * {@code HarborSessionManager#broadcastPresenceChange}) — relay {@code PRESENCE} cho MỌI session
   * đang kết nối (đã AUTH) trên pod này, KHÔNG lọc theo "có liên quan không" (khác
   * {@link #onMembershipChanged}/{@link #onConversationDeleted}, vốn biết chính xác ai cần biết) —
   * presence không có khái niệm "ai đang theo dõi ai" ở tầng server, client tự lọc theo userId mình
   * quan tâm (DM peer/thành viên group đang mở). Chấp nhận được vì đây chỉ là optimization hiển thị
   * UI, không phải dữ liệu quan trọng — không đáng xây thêm cơ chế "subscribe presence theo user".
   */
  private void onPresenceChanged(Message<JsonObject> message) {
    var body = message.body();
    var userId = body.getString("userId");
    if (userId == null) {
      return;
    }
    var online = body.getBoolean("online", false);
    var frame =
        SocketFrame.builder()
            .type(MessageType.PRESENCE)
            .id(UUIDUtils.timeBasedUuidAsString())
            .fromUserId(userId)
            .body(Map.of("online", online))
            .ts(System.currentTimeMillis())
            .build();
    for (var session : sessions.values()) {
      if (session.getUserId() != null) {
        relayToClient.accept(session, frame);
      }
    }
  }

  private static Set<UUID> toUuidSet(JsonArray array) {
    if (array == null) {
      return Set.of();
    }
    var result = new HashSet<UUID>();
    for (var item : array) {
      var parsed = UUIDUtils.parseOrDefault(String.valueOf(item));
      if (parsed != null) {
        result.add(parsed);
      }
    }
    return result;
  }

  /**
   * Lặp từng (session, conversationId) thay vì chỉ theo session — 1 session có thể có nhiều
   * conversation trên nhiều pod colony khác nhau, mỗi cặp phải re-resolve độc lập. Chi phí
   * O(sessions × conversation/session), chấp nhận được; dedupe-theo-pod-đích để sau nếu cần.
   * {@code Set.copyOf(...)} chụp snapshot vì map gốc có thể bị sửa đồng thời bởi subscribe()/sendMessage().
   */
  private CompletionStage<Void> reconnectSessionsToNewVersion(int newVersion) {
    var reconnects = new ArrayList<CompletableFuture<?>>();
    for (var session : sessions.values()) {
      if (session.getUserId() == null) {
        continue;
      }
      for (var conversationId : Set.copyOf(session.subscribedConversationIds())) {
        var reconnect =
            backendStreamGateway
                .reconnectConversationToVersion(session, conversationId, newVersion)
                .exceptionally(
                    ex -> {
                      log.warn(
                          "failed to move session {} conversation {} to routing version {}",
                          session.getId(), conversationId, newVersion, ex);
                      return null;
                    });
        reconnects.add(reconnect.toCompletableFuture());
      }
    }
    return CompletableFuture.allOf(reconnects.toArray(CompletableFuture[]::new));
  }
}
