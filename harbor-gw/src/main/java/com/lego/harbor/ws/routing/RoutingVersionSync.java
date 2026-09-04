package com.lego.harbor.ws.routing;

import com.lego.namnv.connector.Payload;
import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.namnv.discovery.router.RoutingVersionTracker;
import com.lego.harbor.ws.backend.BackendStreamGateway;
import com.lego.harbor.ws.dto.MessageType;
import com.lego.harbor.ws.dto.SocketFrame;
import com.lego.harbor.ws.session.HarborSession;
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
 * Phần đồng bộ chung với beacon nằm ở {@link RoutingVersionTracker} — lớp này chỉ lo phần riêng
 * của harbor: mỗi khi có version mới thì di chuyển các session đã authenticate sang đúng node
 * colony mới qua {@link BackendStreamGateway} ({@link #onSignalingChanged}), và phản ứng với broadcast
 * "1 user vừa được thêm vào 1 conversation" từ colony ({@link #onMembershipChanged}). Dùng bởi
 * {@code com.lego.harbor.ws.HarborSessionManager}.
 */
@Slf4j
public class RoutingVersionSync extends RoutingVersionTracker {

  /**
   * Địa chỉ EventBus broadcast phía colony dùng để báo "1 user vừa được thêm vào 1 conversation"
   * (xem {@code ChatSessionManager#publishMembershipChanged} bên colony) — cùng pattern broadcast
   * với "beacon", chấp nhận được vì tần suất thấp hơn hẳn tin nhắn.
   */
  private static final String MEMBERSHIP_CHANGED_ADDRESS = "conversation_membership_changed";

  private final BackendStreamGateway backendLinkGateway;
  private final Map<String, HarborSession> sessions;
  private final BiConsumer<HarborSession, SocketFrame> relayToClient;

  public RoutingVersionSync(
      Vertx vertx, PingoConnector connector, BackendStreamGateway backendLinkGateway, Map<String, HarborSession> sessions,
      BiConsumer<HarborSession, SocketFrame> relayToClient) {
    super(vertx, connector);
    this.backendLinkGateway = backendLinkGateway;
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
   * Nhận broadcast "user X vừa được thêm vào conversationId" từ colony — chỉ những pod harbor đang
   * giữ session sống của X mới phản ứng (mọi pod khác bỏ qua, không tìm thấy session cục bộ).
   * Subscribe ngầm hộ (không đợi client tự biết) rồi relay 1 frame CONVERSATION_ADDED để client tự
   * cập nhật UI. Nếu X không online ở pod harbor nào lúc này thì không mất gì — lần sau X connect
   * lại, việc đồng bộ đầy đủ danh sách conversation từ nguồn bền (khi có) sẽ tự phủ luôn trường hợp
   * này; cơ chế wake ở đây chỉ là tối ưu "cho nhanh" khi X đang online sẵn, không phải đường đảm bảo
   * duy nhất.
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
      backendLinkGateway
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
   * Lặp qua từng (session, conversationId) đang subscribe thay vì chỉ theo session — vì giờ mỗi
   * session có thể có nhiều conversation nằm trên nhiều pod colony khác nhau (không còn "1 session
   * = 1 pod" như trước), mỗi cặp phải re-resolve độc lập. Chi phí tăng từ O(sessions) lên
   * O(sessions × số conversation mở/session) — chấp nhận được cho đợt này; dedupe-theo-pod-đích
   * (gộp các conversation cùng đổi sang cùng 1 pod mới thành 1 lần resolve) là tối ưu để sau,
   * không làm trong đợt này. {@code Set.copyOf(...)} chụp nhanh (snapshot) vì map gốc có thể bị
   * sửa đồng thời (concurrently) bởi subscribe()/sendMessage() đang chạy song song.
   */
  private CompletionStage<Void> reconnectSessionsToNewVersion(int newVersion) {
    var reconnects = new ArrayList<CompletableFuture<?>>();
    for (var session : sessions.values()) {
      if (session.getUserId() == null) {
        continue;
      }
      for (var conversationId : Set.copyOf(session.subscribedConversationIds())) {
        var reconnect =
            backendLinkGateway
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
