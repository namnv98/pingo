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
import java.util.List;
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
    var memberUserIds = List.copyOf(toUuidSet(body.getJsonArray("memberUserIds")));

    for (var session : sessions.values()) {
      if (session.getUserId() == null || !newMemberUserIds.contains(session.getUserId())) {
        continue;
      }
      backendStreamGateway
          .wakeSubscribe(session, conversationId, memberUserIds, currentVersion)
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
