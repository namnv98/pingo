package com.lego.colony.ws;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.colony.ws.delivery.MessageDelivery;
import com.lego.colony.ws.dto.MessageType;
import com.lego.colony.ws.dto.SocketFrame;
import com.lego.colony.ws.membership.ChannelMembershipRegistry;
import com.lego.colony.ws.routing.RoutingVersionSync;
import com.lego.colony.ws.session.ChatSession;
import com.lego.colony.ws.session.SessionRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý các WebSocket session đang sống (live) của node colony này — "cửa trước": nhận
 * connection, dispatch protocol {@link SocketFrame} (AUTH, MESSAGE, PING/PONG), và dọn dẹp session
 * bị idle (không hoạt động) quá lâu. Việc thật sự deliver/forward MESSAGE được giao cho
 * {@link MessageDelivery}, việc đồng bộ routing table version được giao cho
 * {@link RoutingVersionSync}, và việc tra cứu session theo user id được giao cho
 * {@link SessionRegistry} — tách ra để mỗi class chỉ lo đúng 1 việc, dễ đọc và dễ mở rộng
 * (thêm loại frame mới, thêm cách route mới... không phải sửa cả 1 file lớn).
 *
 * <p>{@code serverId} là định danh routing của chính node này (là tên pod k8s khi chạy production,
 * hoặc một giá trị fallback cấu hình sẵn khi chạy local dev — xem thêm {@code ColonyAppModule}).
 * Giá trị này còn được dùng làm địa chỉ EventBus để các node khác forward tin nhắn tới đây, mỗi khi
 * router xác định node này đang giữ session của người nhận (recipient). Đây chính là điểm mấu chốt
 * để việc deliver tin nhắn xuyên node (cross-node) hoạt động đúng: mỗi node BẮT BUỘC phải lắng nghe
 * (consumer) trên một địa chỉ EventBus riêng của chính nó, chứ không dùng chung một địa chỉ tĩnh
 * (static) cho mọi replica — nếu dùng chung, EventBus sẽ round-robin tin nhắn tới bất kỳ pod nào
 * đang lắng nghe, chứ không phải đúng pod đang giữ session của recipient.
 */
@Slf4j
public class ChatSessionManager {

  private static final long HEARTBEAT_SWEEP_INTERVAL_MS = 15_000;
  private static final long SESSION_IDLE_TIMEOUT_MS = 60_000;
  private static final long DRAIN_GRACE_MS = 1_000;
  /**
   * Địa chỉ EventBus broadcast (publish, không phải point-to-point) cho sự kiện "1 user vừa được
   * thêm vào 1 conversation" -- mọi pod harbor đều lắng nghe, tự kiểm tra cục bộ xem có session nào
   * của user đó đang sống không rồi tự subscribe hộ + báo cho client (xem RoutingVersionSync bên
   * harbor). Cùng pattern với "beacon" (RoutingGossipPublisher) -- broadcast chấp nhận được ở đây
   * vì tần suất thấp hơn hẳn tin nhắn (chỉ bắn khi membership thực sự đổi, không phải mỗi MESSAGE).
   */
  private static final String MEMBERSHIP_CHANGED_ADDRESS = "conversation_membership_changed";

  private final String serverId;
  private final SessionRegistry registry = new SessionRegistry();
  private final ChannelMembershipRegistry membership;
  private final Vertx vertx;
  private final RoutingVersionSync routingVersionSync;
  private final MessageDelivery messageDelivery;
  /** false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe (xem {@code RestApiVerticle}). */
  private volatile boolean ready = true;

  public ChatSessionManager(String serverId, Vertx vertx, PingoConnector connector, ChannelMembershipRegistry membership) {
    this.serverId = serverId;
    this.vertx = vertx;
    this.membership = membership;
    this.routingVersionSync = new RoutingVersionSync(vertx, connector);
    this.messageDelivery = new MessageDelivery(registry, connector);
    vertx.eventBus().consumer(serverId, messageDelivery::onRoutedMessage);
    vertx.setPeriodic(HEARTBEAT_SWEEP_INTERVAL_MS, tid -> sweepIdleSessions());
  }

  public boolean isReady() {
    return ready;
  }

  /**
   * Gọi lúc pod chuẩn bị tắt (xem {@code ColonyApp.doStop()}): đánh dấu not-ready ngay (cho
   * readinessProbe fail sớm, và để {@link #handleAuth} từ chối session mới), rồi chờ 1 khoảng ngắn
   * cho REMOVE gossip của beacon (xem {@code RoutingGossipPublisher}) kịp lan ra trước khi caller
   * đóng hẳn {@code Vertx}. Không báo gì cho session đang mở — chúng cứ tiếp tục chạy bình thường
   * tới phút chót, vì colony không có quyền tự ý bảo gateway "đi nối chỗ khác" (phải đúng theo
   * routing table mà mọi node khác cũng đang dùng).
   */
  public CompletionStage<Void> drain() {
    ready = false;
    log.info("draining chat node {} before shutdown", serverId);
    var delayed = new CompletableFuture<Void>();
    vertx.setTimer(DRAIN_GRACE_MS, tid -> delayed.complete(null));
    return delayed;
  }

  void onConnection(ServerWebSocket socket) {
    var id = generateSessionId();
    var session = registry.register(id, serverId, socket);
    socket.handler(buffer -> onMessage(session, buffer));
    socket.closeHandler(any -> onClose(session));
    socket.exceptionHandler(ex -> onException(session, ex));
  }

  private void onClose(ChatSession closedSession) {
    registry.remove(closedSession.getId());
    closedSession.cleanUpAfterClose();
  }

  private void onException(ChatSession session, Throwable ex) {
    log.error("an error occurred while reading socket of session {}", session.getId(), ex);
  }

  private void sweepIdleSessions() {
    var now = System.currentTimeMillis();
    for (var session : registry.all()) {
      if (now - session.getLastSeenAt() > SESSION_IDLE_TIMEOUT_MS) {
        log.info("closing idle session {} (userId={})", session.getId(), session.getUserId());
        session.close();
      }
    }
  }

  private void onMessage(ChatSession session, Buffer buffer) {
    session.setLastSeenAt(System.currentTimeMillis());
    var frameOpt = SocketFrame.decode(buffer);
    if (frameOpt.isEmpty()) {
      log.debug("dropping undecodable frame from session {}", session.getId());
      return;
    }
    var frame = frameOpt.get();
    switch (frame.getType()) {
      case SUBSCRIBE -> handleSubscribe(session, frame);
      case MESSAGE -> handleMessage(session, frame);
      case PING -> session.send(pong(frame.getId()));
      default -> log.debug("unsupported frame type {} from session {}", frame.getType(), session.getId());
    }
  }

  /**
   * Đăng ký link này làm subscriber của 1 {@code conversationId} cụ thể — thay thế vai trò cũ của
   * AUTH trên chặng gateway&lt;-&gt;backend (AUTH giờ chỉ còn xử lý cục bộ ở harbor). Đồng thời làm
   * luôn việc lazy-create/join membership: nếu frame mang kèm {@code memberUserIds}, union thẳng
   * vào {@link ChannelMembershipRegistry} trước khi check — đủ cho cả DM (harbor tự gửi kèm 2
   * userId lần subscribe đầu) lẫn group (client tự chọn danh sách thành viên), không cần flow
   * "tạo group" riêng vì hệ thống chưa có AuthN thật (xem ARCHITECTURE.md mục 8).
   */
  private void handleSubscribe(ChatSession session, SocketFrame frame) {
    if (!ready) {
      session.send(subscribeError(frame.getId(), "chat node draining"));
      return;
    }
    if (isBlank(frame.getFromUserId())) {
      session.send(subscribeError(frame.getId(), "missing fromUserId"));
      return;
    }
    UUID userId;
    UUID conversationId;
    try {
      userId = UUID.fromString(frame.getFromUserId());
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException | NullPointerException e) {
      session.send(subscribeError(frame.getId(), "invalid/missing fromUserId or conversationId"));
      return;
    }
    if (session.getUserId() == null) {
      registry.attachUser(session, userId);
    }

    CompletionStage<Void> membershipWrite;
    if (frame.getMemberUserIds() != null && !frame.getMemberUserIds().isEmpty()) {
      var members = new ArrayList<UUID>();
      members.add(userId);
      for (var memberUserId : frame.getMemberUserIds()) {
        var parsed = UUIDUtils.parseOrDefault(memberUserId);
        if (parsed != null) {
          members.add(parsed);
        }
      }
      membershipWrite =
          membership
              .addMembers(conversationId, members)
              .thenCompose(
                  newlyAdded -> newlyAdded.isEmpty()
                      ? CompletableFuture.completedFuture(null)
                      : publishMembershipChanged(conversationId, newlyAdded));
    } else {
      membershipWrite = CompletableFuture.completedFuture(null);
    }

    var finalConversationId = conversationId;
    membershipWrite
        .thenCompose(unused -> membership.isMember(finalConversationId, userId))
        .thenAccept(
            isMember -> {
              if (!isMember) {
                session.send(subscribeError(frame.getId(), "not a member of this conversation"));
                return;
              }
              registry.subscribe(session, finalConversationId);
              session.send(
                  SocketFrame.builder()
                      .type(MessageType.SUBSCRIBE_OK)
                      .id(frame.getId())
                      .conversationId(finalConversationId.toString())
                      .ts(now())
                      .build());
            })
        .exceptionally(
            ex -> {
              log.error("failed to check/write membership for conversation {}", finalConversationId, ex);
              session.send(subscribeError(frame.getId(), "internal error"));
              return null;
            });
  }

  private void handleMessage(ChatSession session, SocketFrame frame) {
    if (session.getUserId() == null) {
      session.send(error(frame.getId(), "not authenticated"));
      return;
    }
    if (isBlank(frame.getConversationId())) {
      session.send(error(frame.getId(), "missing conversationId"));
      return;
    }

    var outgoing =
        SocketFrame.builder()
            .type(MessageType.MESSAGE)
            .id(frame.getId())
            .fromUserId(session.getUserId().toString())
            .toUserId(frame.getToUserId())
            .conversationId(frame.getConversationId())
            .body(frame.getBody())
            .ts(now())
            .build();

    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
    session.send(SocketFrame.builder().type(MessageType.ACK).id(frame.getId()).ts(now()).build());
  }

  /**
   * Broadcast "user X vừa được thêm vào conversationId" cho mọi pod harbor -- kèm luôn FULL danh
   * sách member hiện tại (không chỉ phần mới) để phía harbor "nhớ" đủ cho lần reconnect sau này
   * (xem SockjsSocket.rememberMembers), không chỉ nhớ mỗi userId vừa được thêm.
   */
  private CompletionStage<Void> publishMembershipChanged(UUID conversationId, Set<UUID> newlyAddedUserIds) {
    return membership
        .getMembers(conversationId)
        .thenAccept(
            allMembers -> {
              var payload =
                  new JsonObject()
                      .put("conversationId", conversationId.toString())
                      .put("newMemberUserIds", newlyAddedUserIds.stream().map(UUID::toString).toList())
                      .put("memberUserIds", allMembers.stream().map(UUID::toString).toList());
              vertx.eventBus().publish(MEMBERSHIP_CHANGED_ADDRESS, payload);
            });
  }

  private String generateSessionId() {
    return UUIDUtils.timeBasedUuidAsString();
  }

  private static SocketFrame pong(String id) {
    return SocketFrame.builder().type(MessageType.PONG).id(id).ts(now()).build();
  }

  private static SocketFrame error(String id, String reason) {
    return SocketFrame.builder().type(MessageType.ERROR).id(id).reason(reason).ts(now()).build();
  }

  private static SocketFrame subscribeError(String id, String reason) {
    return SocketFrame.builder().type(MessageType.SUBSCRIBE_ERROR).id(id).reason(reason).ts(now()).build();
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
