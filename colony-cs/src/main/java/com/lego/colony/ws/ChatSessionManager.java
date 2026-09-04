package com.lego.colony.ws;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.namnv.discovery.grpc.Frame;
import com.lego.namnv.discovery.grpc.FrameType;
import com.lego.colony.ws.delivery.MessageDelivery;
import com.lego.colony.ws.history.MessageHistoryRegistry;
import com.lego.colony.ws.membership.ConversationMembershipRegistry;
import com.lego.colony.ws.routing.RoutingVersionSync;
import com.lego.colony.ws.session.ChatSession;
import com.lego.colony.ws.session.SessionRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.grpc.server.GrpcServerRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý các gRPC stream đang sống (live) của node colony này — "cửa trước": nhận call
 * {@code Link.Stream}, dispatch protocol {@link Frame} (SUBSCRIBE, MESSAGE), và dọn dẹp session bị
 * idle. Việc thật sự deliver/forward MESSAGE được giao cho {@link MessageDelivery}, việc đồng bộ
 * routing table version được giao cho {@link RoutingVersionSync}, và việc tra cứu subscriber theo
 * user id/conversationId được giao cho {@link SessionRegistry} — tách ra để mỗi class chỉ lo đúng 1
 * việc, dễ đọc và dễ mở rộng.
 *
 * <p>Từ khi chuyển sang gRPC (xem ARCHITECTURE.md mục 12), 1 call = đúng 1 {@link ChatSession} —
 * không còn phải phân biệt "link vật lý" với "subscriber logic" như thời N-shard-link nữa (HTTP/2
 * tự multiplex nhiều stream trên chung 1 connection, mỗi stream vẫn là 1 danh tính riêng). Stream
 * kết thúc (client end()) hoặc lỗi là tín hiệu dọn dẹp duy nhất, không cần frame {@code SESSION_CLOSED}
 * tự viết như trước.
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
   * harbor). Cùng pattern broadcast với "beacon" (RoutingGossipPublisher) -- broadcast chấp nhận
   * được ở đây vì tần suất thấp hơn hẳn tin nhắn (chỉ bắn khi membership thực sự đổi).
   */
  private static final String MEMBERSHIP_CHANGED_ADDRESS = "conversation_membership_changed";

  private final String serverId;
  private final SessionRegistry registry = new SessionRegistry();
  private final ConversationMembershipRegistry membership;
  private final MessageHistoryRegistry history;
  private final Vertx vertx;
  private final RoutingVersionSync routingVersionSync;
  private final MessageDelivery messageDelivery;
  /** false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe (xem {@code RestApiVerticle}). */
  private volatile boolean ready = true;

  public ChatSessionManager(
      String serverId, Vertx vertx, PingoConnector connector, ConversationMembershipRegistry membership, MessageHistoryRegistry history) {
    this.serverId = serverId;
    this.vertx = vertx;
    this.membership = membership;
    this.history = history;
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
   * readinessProbe fail sớm, và để {@link #handleSubscribe} từ chối subscribe mới), rồi chờ 1
   * khoảng ngắn cho REMOVE gossip của beacon (xem {@code RoutingGossipPublisher}) kịp lan ra trước
   * khi caller đóng hẳn {@code Vertx}. Không báo gì cho stream đang mở — chúng cứ tiếp tục chạy bình
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

  void onConnection(GrpcServerRequest<Frame, Frame> request) {
    var session = new ChatSession(generateSessionId(), request.response());
    registry.register(session);
    request.handler(frame -> onMessage(session, frame));
    request.endHandler(v -> onClose(session));
    request.exceptionHandler(ex -> onException(session, ex));
  }

  private void onClose(ChatSession session) {
    registry.remove(session.getId());
  }

  private void onException(ChatSession session, Throwable ex) {
    log.error("an error occurred on session {}", session.getId(), ex);
    registry.remove(session.getId());
  }

  private void sweepIdleSessions() {
    var now = System.currentTimeMillis();
    for (var session : registry.allSessions()) {
      if (now - session.getLastSeenAt() > SESSION_IDLE_TIMEOUT_MS) {
        log.info("closing idle session {}", session.getId());
        session.close();
        registry.remove(session.getId());
      }
    }
  }

  private void onMessage(ChatSession session, Frame frame) {
    session.setLastSeenAt(System.currentTimeMillis());
    switch (frame.getType()) {
      case SUBSCRIBE -> handleSubscribe(session, frame);
      case MESSAGE -> handleMessage(session, frame);
      default -> log.debug("unsupported frame type {} from session {}", frame.getType(), session.getId());
    }
  }

  /**
   * Đăng ký session này là subscriber của 1 {@code conversationId} cụ thể — thay thế vai trò cũ
   * của AUTH trên chặng gateway<->backend (AUTH giờ chỉ còn xử lý cục bộ ở harbor). Đồng thời làm
   * luôn việc lazy-create/join membership: nếu frame mang kèm {@code memberUserIds}, union thẳng
   * vào {@link ConversationMembershipRegistry} trước khi check — đủ cho cả DM (harbor tự gửi kèm 2
   * userId lần subscribe đầu) lẫn group (client tự chọn danh sách thành viên), không cần flow
   * "tạo group" riêng vì hệ thống chưa có AuthN thật cho việc đó (xem ARCHITECTURE.md mục 8).
   */
  private void handleSubscribe(ChatSession session, Frame frame) {
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

    CompletionStage<Void> membershipWrite =
        frame.getMemberUserIdsList().isEmpty()
            ? CompletableFuture.completedFuture(null)
            : writeNewMembers(conversationId, userId, frame.getMemberUserIdsList());

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
                  Frame.newBuilder()
                      .setId(frame.getId())
                      .setType(FrameType.SUBSCRIBE_OK)
                      .setConversationId(finalConversationId.toString())
                      .setTs(now())
                      .build());
            })
        .exceptionally(
            ex -> {
              log.error("failed to check/write membership for conversation {}", finalConversationId, ex);
              session.send(subscribeError(frame.getId(), "internal error"));
              return null;
            });
  }

  /** Union {@code userId} + các member hợp lệ trong {@code rawMemberUserIds} vào membership, publish nếu có ai thật sự mới. */
  private CompletionStage<Void> writeNewMembers(UUID conversationId, UUID userId, List<String> rawMemberUserIds) {
    var members = new ArrayList<UUID>();
    members.add(userId);
    for (var memberUserId : rawMemberUserIds) {
      var parsed = UUIDUtils.parseOrDefault(memberUserId);
      if (parsed != null) {
        members.add(parsed);
      }
    }
    return membership
        .addMembers(conversationId, members)
        .thenCompose(
            newlyAdded -> newlyAdded.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : publishMembershipChanged(conversationId, newlyAdded));
  }

  private void handleMessage(ChatSession session, Frame frame) {
    if (session.getUserId() == null) {
      session.send(error(frame.getId(), "not authenticated"));
      return;
    }
    if (isBlank(frame.getConversationId())) {
      session.send(error(frame.getId(), "missing conversationId"));
      return;
    }

    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.MESSAGE)
            .setFromUserId(session.getUserId().toString())
            .setConversationId(frame.getConversationId())
            .setBodyJson(frame.getBodyJson())
            .setTs(now())
            .build();

    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
    persistMessage(session, frame, outgoing);
    session.send(Frame.newBuilder().setId(frame.getId()).setType(FrameType.ACK).setTs(now()).build());
  }

  /**
   * Ghi lại lịch sử tin nhắn — best-effort, KHÔNG chặn đường đi real-time ở trên (deliverLocally/
   * forwardToOwningNode/ACK đã chạy xong hoặc đang chạy song song, không đợi write này). Dùng
   * {@code outgoing.getConversationId()}/{@code getTs()} (đã chuẩn hoá — server tự stamp) thay vì
   * đọc lại từ {@code frame} gốc của client. Bỏ qua lặng lẽ nếu conversationId sai định dạng — cùng
   * mức độ khoan dung với {@code MessageDelivery.deliverLocally}, không phải lỗi cần báo client.
   */
  private void persistMessage(ChatSession session, Frame frame, Frame outgoing) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(outgoing.getConversationId());
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    Object body = outgoing.getBodyJson().isEmpty() ? null : Json.decodeValue(outgoing.getBodyJson());
    history
        .saveMessage(UUID.randomUUID(), conversationId, session.getUserId(), body, outgoing.getTs())
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

  private static Frame error(String id, String reason) {
    return Frame.newBuilder().setId(id).setType(FrameType.ERROR).setReason(reason).setTs(now()).build();
  }

  private static Frame subscribeError(String id, String reason) {
    return Frame.newBuilder().setId(id).setType(FrameType.SUBSCRIBE_ERROR).setReason(reason).setTs(now()).build();
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
