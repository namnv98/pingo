package com.lego.harbor.ws.routing;

import com.lego.namnv.connector.Payload;
import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.connector.SignalingResponse;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.harbor.ws.backend.BackendLinkGateway;
import com.lego.harbor.ws.dto.MessageType;
import com.lego.harbor.ws.dto.SocketFrame;
import com.lego.harbor.ws.session.SockjsSocket;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Giữ cho gateway đồng bộ với routing table hiện hành (phát ra bởi beacon): lấy version ban
 * đầu, lắng nghe thay đổi, và mỗi khi có version mới thì di chuyển các session đã authenticate
 * sang đúng node colony mới qua {@link BackendLinkGateway}. Dùng bởi
 * {@code com.lego.harbor.ws.SockjsSocketManager}.
 */
@Slf4j
public class RoutingVersionSync {

  /**
   * Địa chỉ EventBus broadcast phía colony dùng để báo "1 user vừa được thêm vào 1 conversation"
   * (xem {@code ChatSessionManager#publishMembershipChanged} bên colony) — cùng pattern broadcast
   * với "beacon", chấp nhận được vì tần suất thấp hơn hẳn tin nhắn.
   */
  private static final String MEMBERSHIP_CHANGED_ADDRESS = "conversation_membership_changed";

  private final Vertx vertx;
  private final PingoConnector connector;
  private final BackendLinkGateway backendLinkGateway;
  private final Map<String, SockjsSocket> sessions;
  private final BiConsumer<SockjsSocket, SocketFrame> relayToClient;
  private volatile int currentVersion;

  public RoutingVersionSync(
      Vertx vertx, PingoConnector connector, BackendLinkGateway backendLinkGateway, Map<String, SockjsSocket> sessions,
      BiConsumer<SockjsSocket, SocketFrame> relayToClient) {
    this.vertx = vertx;
    this.connector = connector;
    this.backendLinkGateway = backendLinkGateway;
    this.sessions = sessions;
    this.relayToClient = relayToClient;
    vertx.eventBus().consumer("beacon", this::onSignalingChanged);
    vertx.eventBus().consumer(MEMBERSHIP_CHANGED_ADDRESS, this::onMembershipChanged);
    signalingInit();
  }

  public int currentVersion() {
    return currentVersion;
  }

  private void signalingInit() {
    vertx
        .eventBus()
        .request(
            "beacon_init",
            null,
            new DeliveryOptions(),
            event -> {
              if (event.failed()) {
                log.warn(
                    "beacon_init failed, retrying in 2s: {}",
                    event.cause() != null ? event.cause().getMessage() : "unknown");
                vertx.setTimer(2000, tid -> signalingInit());
                return;
              }
              var jsonObject = new JsonObject(event.result().body().toString());
              var signalingResponse = jsonObject.mapTo(SignalingResponse.class);
              connector.add(signalingResponse.getVersion(), signalingResponse.getDestinations());
              currentVersion = signalingResponse.getVersion();
              log.info(
                  "beacon_init: dong bo xong routing table version {}, {} colony destination",
                  currentVersion,
                  signalingResponse.getDestinations().size());
            });
  }

  private void onSignalingChanged(Message<JsonObject> jsonObjectMessage) {
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
