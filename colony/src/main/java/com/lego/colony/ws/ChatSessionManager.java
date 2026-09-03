package com.lego.colony.ws;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.colony.ws.delivery.MessageDelivery;
import com.lego.colony.ws.dto.MessageType;
import com.lego.colony.ws.dto.SocketFrame;
import com.lego.colony.ws.history.MessageHistoryRegistry;
import com.lego.colony.ws.membership.ChannelMembershipRegistry;
import com.lego.colony.ws.routing.RoutingVersionSync;
import com.lego.colony.ws.session.ChatLink;
import com.lego.colony.ws.session.ChatSubscriber;
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
 * Quản lý các WebSocket link đang sống (live) của node colony này — "cửa trước": nhận connection,
 * dispatch protocol {@link SocketFrame} (SUBSCRIBE, MESSAGE, PING/PONG, SESSION_CLOSED), và dọn
 * dẹp link bị idle (không hoạt động) quá lâu. Việc thật sự deliver/forward MESSAGE được giao cho
 * {@link MessageDelivery}, việc đồng bộ routing table version được giao cho
 * {@link RoutingVersionSync}, và việc tra cứu subscriber theo user id/conversationId được giao cho
 * {@link SessionRegistry} — tách ra để mỗi class chỉ lo đúng 1 việc, dễ đọc và dễ mở rộng
 * (thêm loại frame mới, thêm cách route mới... không phải sửa cả 1 file lớn).
 *
 * <p>1 {@link ChatLink} (connection vật lý) giờ có thể mang nhiều {@link ChatSubscriber} (mỗi cái
 * là 1 harbor session cụ thể, định danh bởi {@code SocketFrame.harborSessionId}) cùng lúc — từ khi
 * harbor sharded link theo pod, dùng chung 1 link cho nhiều client session thay vì 1 link/session
 * (xem {@code BackendLinkGateway} bên harbor). Vì vậy TCP close của 1 link không còn đồng nghĩa
 * "1 subscriber vừa rời đi" nữa — harbor phải chủ động báo bằng frame {@code SESSION_CLOSED} khi 1
 * session cụ thể đóng (xem {@link #onMessage}), còn TCP close của chính link đó vẫn dọn dẹp mọi
 * subscriber đang "cưỡi" trên nó (xem {@link #onClose}).
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
  private final MessageHistoryRegistry history;
  private final Vertx vertx;
  private final RoutingVersionSync routingVersionSync;
  private final MessageDelivery messageDelivery;
  /** false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe (xem {@code RestApiVerticle}). */
  private volatile boolean ready = true;

  public ChatSessionManager(
      String serverId, Vertx vertx, PingoConnector connector, ChannelMembershipRegistry membership, MessageHistoryRegistry history) {
    this.serverId = serverId;
    this.vertx = vertx;
    this.membership = membership;
    this.history = history;
    this.routingVersionSync = new RoutingVersionSync(vertx, connector);
    this.messageDelivery = new MessageDelivery(registry, connector);
    vertx.eventBus().consumer(serverId, messageDelivery::onRoutedMessage);
    vertx.setPeriodic(HEARTBEAT_SWEEP_INTERVAL_MS, tid -> sweepIdleLinks());
  }

  public boolean isReady() {
    return ready;
  }

  /**
   * Gọi lúc pod chuẩn bị tắt (xem {@code ColonyApp.doStop()}): đánh dấu not-ready ngay (cho
   * readinessProbe fail sớm, và để {@link #handleSubscribe} từ chối subscribe mới), rồi chờ 1
   * khoảng ngắn cho REMOVE gossip của beacon (xem {@code RoutingGossipPublisher}) kịp lan ra trước
   * khi caller đóng hẳn {@code Vertx}. Không báo gì cho link đang mở — chúng cứ tiếp tục chạy bình
   * thường tới phút chót, vì colony không có quyền tự ý bảo gateway "đi nối chỗ khác" (phải đúng
   * theo routing table mà mọi node khác cũng đang dùng).
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
    var link = registry.registerLink(id, serverId, socket);
    socket.handler(buffer -> onMessage(link, buffer));
    socket.closeHandler(any -> onClose(link));
    socket.exceptionHandler(ex -> onException(link, ex));
  }

  /** Link vật lý vừa đóng — gỡ MỌI subscriber đang cưỡi trên nó (có thể nhiều, giờ link dùng chung), rồi gỡ chính link đó. */
  private void onClose(ChatLink closedLink) {
    for (var harborSessionId : Set.copyOf(closedLink.getSubscriberIds())) {
      registry.removeSubscriber(harborSessionId);
    }
    registry.removeLink(closedLink.getId());
    closedLink.cleanUpAfterClose();
  }

  private void onException(ChatLink link, Throwable ex) {
    log.error("an error occurred while reading socket of link {}", link.getId(), ex);
  }

  private void sweepIdleLinks() {
    var now = System.currentTimeMillis();
    for (var link : registry.allLinks()) {
      if (now - link.getLastSeenAt() > SESSION_IDLE_TIMEOUT_MS) {
        log.info("closing idle link {} ({} subscriber(s))", link.getId(), link.getSubscriberIds().size());
        link.close();
      }
    }
  }

  private void onMessage(ChatLink link, Buffer buffer) {
    link.setLastSeenAt(System.currentTimeMillis());
    var frameOpt = SocketFrame.decode(buffer);
    if (frameOpt.isEmpty()) {
      log.debug("dropping undecodable frame from link {}", link.getId());
      return;
    }
    var frame = frameOpt.get();
    if (frame.getType() == MessageType.PING) {
      link.send(pong(frame.getId()));
      return;
    }
    if (frame.getType() == MessageType.SESSION_CLOSED) {
      registry.removeSubscriber(frame.getHarborSessionId());
      return;
    }
    if (frame.getType() != MessageType.SUBSCRIBE && frame.getType() != MessageType.MESSAGE) {
      log.debug("unsupported frame type {} from link {}", frame.getType(), link.getId());
      return;
    }
    if (isBlank(frame.getHarborSessionId())) {
      log.warn("dropping {} frame with missing harborSessionId on link {}", frame.getType(), link.getId());
      return;
    }
    var subscriber = registry.subscriberFor(frame.getHarborSessionId(), link);
    switch (frame.getType()) {
      case SUBSCRIBE -> handleSubscribe(subscriber, frame);
      case MESSAGE -> handleMessage(subscriber, frame);
      default -> {}
    }
  }

  /**
   * Đăng ký subscriber này là subscriber của 1 {@code conversationId} cụ thể — thay thế vai trò cũ
   * của AUTH trên chặng gateway<->backend (AUTH giờ chỉ còn xử lý cục bộ ở harbor). Đồng thời làm
   * luôn việc lazy-create/join membership: nếu frame mang kèm {@code memberUserIds}, union thẳng
   * vào {@link ChannelMembershipRegistry} trước khi check — đủ cho cả DM (harbor tự gửi kèm 2
   * userId lần subscribe đầu) lẫn group (client tự chọn danh sách thành viên), không cần flow
   * "tạo group" riêng vì hệ thống chưa có AuthN thật (xem ARCHITECTURE.md mục 8).
   */
  private void handleSubscribe(ChatSubscriber subscriber, SocketFrame frame) {
    if (!ready) {
      subscriber.send(subscribeError(subscriber, frame.getId(), "chat node draining"));
      return;
    }
    if (isBlank(frame.getFromUserId())) {
      subscriber.send(subscribeError(subscriber, frame.getId(), "missing fromUserId"));
      return;
    }
    UUID userId;
    UUID conversationId;
    try {
      userId = UUID.fromString(frame.getFromUserId());
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException | NullPointerException e) {
      subscriber.send(subscribeError(subscriber, frame.getId(), "invalid/missing fromUserId or conversationId"));
      return;
    }
    if (subscriber.getUserId() == null) {
      registry.attachUser(subscriber, userId);
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
                subscriber.send(subscribeError(subscriber, frame.getId(), "not a member of this conversation"));
                return;
              }
              registry.subscribe(subscriber, finalConversationId);
              subscriber.send(
                  SocketFrame.builder()
                      .type(MessageType.SUBSCRIBE_OK)
                      .id(frame.getId())
                      .conversationId(finalConversationId.toString())
                      .harborSessionId(subscriber.getHarborSessionId())
                      .ts(now())
                      .build());
            })
        .exceptionally(
            ex -> {
              log.error("failed to check/write membership for conversation {}", finalConversationId, ex);
              subscriber.send(subscribeError(subscriber, frame.getId(), "internal error"));
              return null;
            });
  }

  private void handleMessage(ChatSubscriber subscriber, SocketFrame frame) {
    if (subscriber.getUserId() == null) {
      subscriber.send(error(subscriber, frame.getId(), "not authenticated"));
      return;
    }
    if (isBlank(frame.getConversationId())) {
      subscriber.send(error(subscriber, frame.getId(), "missing conversationId"));
      return;
    }

    var outgoing =
        SocketFrame.builder()
            .type(MessageType.MESSAGE)
            .id(frame.getId())
            .fromUserId(subscriber.getUserId().toString())
            .toUserId(frame.getToUserId())
            .conversationId(frame.getConversationId())
            .body(frame.getBody())
            .ts(now())
            .build();

    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
    persistMessage(subscriber, frame, outgoing);
    subscriber.send(
        SocketFrame.builder().type(MessageType.ACK).id(frame.getId()).harborSessionId(subscriber.getHarborSessionId()).ts(now()).build());
  }

  /**
   * Ghi lại lịch sử tin nhắn — best-effort, KHÔNG chặn đường đi real-time ở trên (deliverLocally/
   * forwardToOwningNode/ACK đã chạy xong hoặc đang chạy song song, không đợi write này). Dùng
   * {@code outgoing.getConversationId()}/{@code getTs()} (đã chuẩn hoá — server tự stamp) thay vì
   * đọc lại từ {@code frame} gốc của client. Bỏ qua lặng lẽ nếu conversationId sai định dạng — cùng
   * mức độ khoan dung với {@code MessageDelivery.deliverLocally}, không phải lỗi cần báo client.
   */
  private void persistMessage(ChatSubscriber subscriber, SocketFrame frame, SocketFrame outgoing) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(outgoing.getConversationId());
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    history
        .saveMessage(UUID.randomUUID(), conversationId, subscriber.getUserId(), outgoing.getBody(), outgoing.getTs())
        .exceptionally(
            ex -> {
              log.warn("failed to persist message {} for conversation {}", frame.getId(), conversationId, ex);
              return null;
            });
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

  private static SocketFrame error(ChatSubscriber subscriber, String id, String reason) {
    return SocketFrame.builder().type(MessageType.ERROR).id(id).harborSessionId(subscriber.getHarborSessionId()).reason(reason).ts(now()).build();
  }

  private static SocketFrame subscribeError(ChatSubscriber subscriber, String id, String reason) {
    return SocketFrame.builder().type(MessageType.SUBSCRIBE_ERROR).id(id).harborSessionId(subscriber.getHarborSessionId()).reason(reason).ts(now()).build();
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
