package com.pingo.colony.ws;

import com.pingo.connector.PingoConnector;
import com.pingo.core.common.exception.ExceptionUtils;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.chat.grpc.Frame;
import com.pingo.chat.grpc.FrameType;
import com.pingo.colony.ws.delivery.MessageDelivery;
import com.pingo.chat.domain.history.MessageHistoryRegistry;
import com.pingo.chat.domain.link.MessageLinkRegistry;
import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.chat.domain.notification.NotificationRegistry;
import com.pingo.chat.domain.pin.MessagePinRegistry;
import com.pingo.chat.domain.preview.LinkPreviewService;
import com.pingo.colony.ws.routing.RoutingVersionSync;
import com.pingo.colony.ws.session.ChatSession;
import com.pingo.colony.ws.session.SessionRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.grpc.server.GrpcServerRequest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * "Cửa trước" của node colony: nhận gRPC call {@code Link.Stream}, dispatch {@link Frame}
 * (SUBSCRIBE, MESSAGE), dọn session idle. Deliver/forward MESSAGE giao cho {@link MessageDelivery},
 * đồng bộ routing version giao cho {@link RoutingVersionSync}, tra cứu subscriber giao cho
 * {@link SessionRegistry}.
 *
 * <p>1 gRPC call = đúng 1 {@link ChatSession} — không còn tách "link vật lý" khỏi "subscriber logic"
 * như thời N-shard-link (HTTP/2 tự multiplex, mỗi stream là 1 danh tính riêng). Stream end()/lỗi là
 * tín hiệu dọn dẹp duy nhất, không cần frame {@code SESSION_CLOSED} tự viết như trước.
 *
 * <p>{@code serverId} (tên pod k8s, hoặc fallback config cho local dev — xem {@code ColonyAppModule})
 * là địa chỉ EventBus riêng của node này để nhận tin forward xuyên node. BẮT BUỘC mỗi node dùng địa
 * chỉ riêng, không dùng chung 1 địa chỉ tĩnh — dùng chung thì EventBus round-robin tin tới bất kỳ pod
 * nào đang nghe, chứ không phải đúng pod giữ session của recipient.
 */
@Slf4j
public class ChatSessionManager {

  private static final long HEARTBEAT_SWEEP_INTERVAL_MS = 15_000;
  private static final long SESSION_IDLE_TIMEOUT_MS = 60_000;
  private static final long DRAIN_GRACE_MS = 1_000;
  /**
   * Địa chỉ EventBus broadcast "1 tin nhắn vừa gửi tới conversationId X, đây là member (trừ người
   * gửi)" cho herald tự lọc online/offline rồi lưu noti nếu cần — xem
   * {@code NotificationConsumer} bên herald. Colony KHÔNG tự lọc (không cần thêm phụ thuộc
   * PresenceRegistry/Hazelcast vào đường xử lý tin nhắn nóng), chỉ báo "ai CÓ THỂ cần noti".
   */
  private static final String NOTIFY_CANDIDATES_ADDRESS = "message_notify_candidates";

  private final String serverId;
  private final SessionRegistry registry = new SessionRegistry();
  private final ConversationMembershipRegistry membership;
  private final MessageHistoryRegistry history;
  private final MessagePinRegistry pins;
  private final MessageLinkRegistry links;
  private final LinkPreviewService linkPreviewService;
  private final NotificationRegistry notifications;
  private final Vertx vertx;
  private final RoutingVersionSync routingVersionSync;
  private final MessageDelivery messageDelivery;
  /** false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe (xem {@code RestApiVerticle}). */
  private volatile boolean ready = true;
  /**
   * Throttle log cho {@link #persistMessage} khi DB pool qua tai — xem giai thich chi tiet tai noi
   * dung. Dung {@link AtomicLong} thay vi volatile long vi nhieu event-loop thread (moi thread 1
   * context rieng) co the goi persistMessage dong thoi.
   */
  private final AtomicLong lastPoolExhaustedLogAt = new AtomicLong();
  private final AtomicLong poolExhaustedSuppressedCount = new AtomicLong();

  public ChatSessionManager(
      String serverId,
      Vertx vertx,
      PingoConnector connector,
      ConversationMembershipRegistry membership,
      MessageHistoryRegistry history,
      MessagePinRegistry pins,
      MessageLinkRegistry links,
      LinkPreviewService linkPreviewService,
      NotificationRegistry notifications) {
    this.serverId = serverId;
    this.vertx = vertx;
    this.membership = membership;
    this.history = history;
    this.pins = pins;
    this.links = links;
    this.linkPreviewService = linkPreviewService;
    this.notifications = notifications;
    this.routingVersionSync = new RoutingVersionSync(vertx, connector);
    this.messageDelivery = new MessageDelivery(registry, connector);
    vertx.eventBus().consumer(serverId, messageDelivery::onRoutedMessage);
    vertx.setPeriodic(HEARTBEAT_SWEEP_INTERVAL_MS, tid -> sweepIdleSessions());
  }

  public boolean isReady() {
    return ready;
  }

  /**
   * Gọi lúc pod chuẩn bị tắt: đánh dấu not-ready ngay (readinessProbe fail sớm, {@link #handleSubscribe}
   * từ chối subscribe mới), rồi chờ 1 khoảng ngắn cho gossip REMOVE của beacon lan ra trước khi đóng
   * Vertx. Không báo gì cho stream đang mở — chúng chạy tới phút chót, vì colony không có quyền tự ý
   * điều hướng gateway (phải theo đúng routing table chung).
   */
  public CompletionStage<Void> drain() {
    ready = false;
    log.info("draining chat node {} before shutdown", serverId);
    var delayed = new CompletableFuture<Void>();
    vertx.setTimer(DRAIN_GRACE_MS, tid -> delayed.complete(null));
    return delayed;
  }

  public void onConnection(GrpcServerRequest<Frame, Frame> request) {
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

  /**
   * {@code lastSeenAt} chỉ cập nhật khi nhận frame TỪ harbor ({@link #onMessage}) -- 1 stream chỉ
   * dùng để ĐẨY tin cho 1 người nhận thụ động (không tự SUBSCRIBE/MESSAGE gì thêm lên) không bao
   * giờ tự sinh hoạt động, nên nếu không chủ động hỏi thăm sẽ bị coi là chết và đóng oan (đã gặp
   * thật, cùng loại bug với client<->harbor -- xem HarborSessionManager#heartbeatSweep). Chủ động
   * PING khi đã idle quá nửa ngưỡng, cho harbor còn nửa thời gian để PONG lại trước khi bị đóng thật.
   */
  private void sweepIdleSessions() {
    var now = System.currentTimeMillis();
    for (var session : registry.allSessions()) {
      var idleMs = now - session.getLastSeenAt();
      if (idleMs > SESSION_IDLE_TIMEOUT_MS) {
        log.info("closing idle session {}", session.getId());
        session.close();
        registry.remove(session.getId());
      } else if (idleMs > SESSION_IDLE_TIMEOUT_MS / 2) {
        session.send(Frame.newBuilder().setId(UUIDUtils.timeBasedUuidAsString()).setType(FrameType.PING).setTs(now).build());
      }
    }
  }

  private void onMessage(ChatSession session, Frame frame) {
    session.setLastSeenAt(System.currentTimeMillis());
    switch (frame.getType()) {
      case SUBSCRIBE -> handleSubscribe(session, frame);
      case SUBSCRIBE_BULK -> handleSubscribeBulk(session, frame);
      case MESSAGE -> handleMessage(session, frame);
      case TYPING -> handleTyping(session, frame);
      case SEEN -> handleSeen(session, frame);
      case REACTION -> handleReaction(session, frame);
      case DELETE -> handleDelete(session, frame);
      case PIN -> handlePin(session, frame);
      case PONG -> {} // chi can cham lastSeenAt (da lam o tren), khong can xu ly gi them
      default -> log.debug("unsupported frame type {} from session {}", frame.getType(), session.getId());
    }
  }

  /**
   * Đăng ký hàng loạt conversationId ĐÃ TỒN TẠI (member đã có sẵn trong DB) — khác {@link
   * #handleSubscribe}: không mang {@code memberUserIds}, không lazy-create/ghi membership, chỉ đăng
   * ký subscriber cục bộ. Harbor tự gửi 1 lần ngay sau AUTH, gom hết conversationId của user theo
   * từng pod đích (xem {@code HarborSessionManager#autoSubscribeAllConversations}), thay vì client
   * phải tự gửi SUBSCRIBE riêng cho từng conversationId một (tham khảo Slack, xem ARCHITECTURE.md
   * mục 12). Best-effort: conversationId nào parse lỗi thì bỏ qua lặng lẽ, không fail cả frame.
   */
  private void handleSubscribeBulk(ChatSession session, Frame frame) {
    // subscribeError() (khong phai error() chung) -- harbor chi xu ly ERROR nhu phan hoi cho 1
    // pending MESSAGE (xem BackendStreamGateway#onBackendFrame), khong phai cho 1 pending
    // SUBSCRIBE_BULK; dung sai type se khien request cu treo toi khi het han HANDSHAKE_TIMEOUT_MS
    // thay vi fail ngay (da gap that su khi quen set fromUserId o phia harbor).
    if (UUIDUtils.parseOrDefault(frame.getFromUserId()) == null) {
      session.send(subscribeError(frame.getId(), "missing/invalid fromUserId"));
      return;
    }
    for (var rawConversationId : frame.getConversationIdsList()) {
      var conversationId = UUIDUtils.parseOrDefault(rawConversationId);
      if (conversationId != null) {
        registry.subscribe(session, conversationId);
      }
    }
    session.send(Frame.newBuilder().setId(frame.getId()).setType(FrameType.SUBSCRIBE_BULK_OK).setTs(now()).build());
  }

  /**
   * Đăng ký session làm subscriber của 1 conversationId — thay vai trò AUTH cũ trên chặng
   * gateway↔backend. KHÔNG còn lazy-create membership qua {@code memberUserIds} nữa (bỏ từ khi có
   * {@code POST /conversations} riêng bên hall — mọi conversation MỚI (cả DM lẫn group) đều phải
   * tạo qua đó trước, giống Slack/Discord bắt buộc gọi API tạo trước khi gửi tin được, xem
   * ARCHITECTURE.md mục 12) — ở đây chỉ còn CHECK membership đã có sẵn trong DB, không ghi gì cả.
   */
  private void handleSubscribe(ChatSession session, Frame frame) {
    if (!ready) {
      session.send(subscribeError(frame.getId(), "chat node draining"));
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
    var finalConversationId = conversationId;
    membership
        .isMember(finalConversationId, userId)
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
              log.error("failed to check membership for conversation {}", finalConversationId, ex);
              session.send(subscribeError(frame.getId(), "internal error"));
              return null;
            });
  }

  private void handleMessage(ChatSession session, Frame frame) {
    var fromUserId = UUIDUtils.parseOrDefault(frame.getFromUserId());
    if (fromUserId == null) {
      session.send(error(frame.getId(), "missing/invalid fromUserId"));
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
            .setFromUserId(fromUserId.toString())
            .setConversationId(frame.getConversationId())
            .setBodyJson(frame.getBodyJson())
            .setTs(now())
            .build();

    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
    persistMessage(frame, outgoing);
    publishNotificationCandidates(outgoing);
    session.send(Frame.newBuilder().setId(frame.getId()).setType(FrameType.ACK).setTs(now()).build());
  }

  /**
   * "User X đang gõ trong conversation Y" -- dùng LẠI đúng đường fan-out của MESSAGE
   * (deliverLocally/forwardToOwningNode, xem {@link MessageDelivery}), nhưng KHÔNG persist, KHÔNG
   * publish notification candidate, KHÔNG gửi ACK về người gửi -- chỉ là tín hiệu tạm thời, best-
   * effort tuyệt đối (rớt 1 lần không sao, sẽ có lần gõ tiếp theo).
   */
  private void handleTyping(ChatSession session, Frame frame) {
    var fromUserId = UUIDUtils.parseOrDefault(frame.getFromUserId());
    if (fromUserId == null || isBlank(frame.getConversationId())) {
      return;
    }
    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.TYPING)
            .setFromUserId(fromUserId.toString())
            .setConversationId(frame.getConversationId())
            .setTs(now())
            .build();
    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
  }

  /**
   * "User X vừa THỰC SỰ xem tin {@code frame.getId()} trong conversation Y" -- fan-out giống
   * {@link #handleTyping} (dùng lại deliverLocally/forwardToOwningNode, không notify/ACK), khác ở 2
   * điểm: (1) đây là tín hiệu cho người GỬI tin đó biết (vẽ dấu "đã xem"), không phải cho người đọc;
   * (2) CÓ persist ({@link #markRead}, bảng {@code message_reads}) để dấu "đã xem" sống qua reload
   * trang -- khác TYPING (thuần tạm thời, không lưu gì). {@code frame.getId()} = id của chính tin
   * nhắn đang được xác nhận (đúng quy ước correlation id của {@code MessageType#READ} bên harbor —
   * client gửi READ với id đó, harbor forward xuống đây nguyên vẹn).
   */
  private void handleSeen(ChatSession session, Frame frame) {
    var fromUserId = UUIDUtils.parseOrDefault(frame.getFromUserId());
    var messageId = UUIDUtils.parseOrDefault(frame.getId());
    if (fromUserId == null || messageId == null || isBlank(frame.getConversationId())) {
      return;
    }
    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.SEEN)
            .setFromUserId(fromUserId.toString())
            .setConversationId(frame.getConversationId())
            .setTs(now())
            .build();
    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
    markRead(messageId, fromUserId);
  }

  private void markRead(UUID messageId, UUID userId) {
    history
        .markRead(messageId, userId)
        .exceptionally(
            ex -> {
              logDbPoolThrottled("failed to mark message {} as read", messageId, ex);
              return null;
            });
  }

  /**
   * Đặt/đổi/huỷ reaction (emoji) trên tin {@code frame.getId()} -- cùng pattern {@link #handleSeen}
   * (fan-out + persist). {@code frame.getBodyJson()} = {@code {"emoji": "..."}} để đặt/đổi, rỗng
   * hoặc thiếu {@code emoji} để huỷ (bấm lại đúng emoji đang chọn, kiểu Facebook — logic "đây có
   * phải bấm lại để huỷ không" nằm ở CLIENT, xem demo.html, colony chỉ biết "đặt X" hoặc "huỷ", không
   * tự suy toggle).
   */
  private void handleReaction(ChatSession session, Frame frame) {
    var fromUserId = UUIDUtils.parseOrDefault(frame.getFromUserId());
    var messageId = UUIDUtils.parseOrDefault(frame.getId());
    if (fromUserId == null || messageId == null || isBlank(frame.getConversationId())) {
      return;
    }
    String emoji = null;
    if (!frame.getBodyJson().isEmpty()) {
      var body = (JsonObject) Json.decodeValue(frame.getBodyJson());
      emoji = body.getString("emoji");
    }
    var finalEmoji = isBlank(emoji) ? null : emoji;
    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.REACTION)
            .setFromUserId(fromUserId.toString())
            .setConversationId(frame.getConversationId())
            .setBodyJson(finalEmoji == null ? "" : Json.encode(new JsonObject().put("emoji", finalEmoji)))
            .setTs(now())
            .build();
    if (!messageDelivery.deliverLocally(outgoing)) {
      messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
    }
    var persist = finalEmoji == null ? history.removeReaction(messageId, fromUserId) : history.setReaction(messageId, fromUserId, finalEmoji);
    persist.exceptionally(
        ex -> {
          logDbPoolThrottled("failed to save reaction for message {}", messageId, ex);
          return null;
        });
    // Chỉ noti lúc ĐẶT reaction (không phải huỷ) -- giống "gạch bỏ" 1 reaction thì không ai cần biết.
    // conversationId ở đây CHỈ dùng để noti nhảy đúng chỗ, không phải khoá bảo mật gì -- sai định
    // dạng (khó xảy ra, harbor luôn gửi đúng) thì bỏ qua noti, không cần fail cả frame.
    var finalConversationId = UUIDUtils.parseOrDefault(frame.getConversationId());
    if (finalEmoji != null && finalConversationId != null) {
      history
          .getOwner(messageId)
          .thenAccept(
              ownerOpt -> ownerOpt
                  .filter(owner -> !owner.fromUserId().equals(fromUserId))
                  // ts = LÚC REACT (now(), giữ nguyên -- quyết định thứ tự "mới nhất trước" trong
                  // chuông, KHÔNG được đổi thành giờ tin gốc, không thì 1 reaction vừa xảy ra vào tin
                  // 3 ngày trước sẽ bị xếp tuột xuống đáy danh sách). messageTs (tham số riêng, xem
                  // createNotification) mới là giờ tin gốc THẬT SỰ được gửi -- client dùng riêng
                  // trường này làm mốc "seek" khi tin chưa có sẵn trong khung chat (xem
                  // jumpToMessage) -- lẫn 2 giá trị này bug thật đã gặp: seek sai hẳn quanh "bây giờ"
                  // thay vì quanh lúc tin gốc, luôn báo "không tìm thấy tin gốc" dù tin còn nguyên.
                  .ifPresent(owner -> createNotification(
                      owner.fromUserId(), finalConversationId, fromUserId, messageId, "reaction", finalEmoji, now(), owner.createdAtEpochMillis())))
          .exceptionally(ex -> {
            logDbPoolThrottled("failed to look up owner for reaction notification on message {}", messageId, ex);
            return null;
          });
    }
  }

  /**
   * Xoá MỀM tin {@code frame.getId()} -- CHỈ chính người gửi gốc mới xoá được, kiểm tra thật trong
   * DB ({@code from_user_id}, xem {@link MessageHistoryRegistry#markDeleted}) chứ KHÔNG tin
   * {@code frame.getFromUserId()} client tự khai (session đã xác thực fromUserId lúc AUTH nên vẫn
   * đáng tin cho MỤC ĐÍCH XÁC ĐỊNH AI ĐANG XOÁ, nhưng "có phải người gửi gốc không" phải hỏi DB).
   * Xoá thất bại (không phải tin của mình, đã xoá rồi, hoặc id không tồn tại) -- im lặng bỏ qua,
   * không fan-out gì cả (demo tool, không cần ERROR riêng cho path này).
   */
  private void handleDelete(ChatSession session, Frame frame) {
    var fromUserId = UUIDUtils.parseOrDefault(frame.getFromUserId());
    var messageId = UUIDUtils.parseOrDefault(frame.getId());
    if (fromUserId == null || messageId == null || isBlank(frame.getConversationId())) {
      return;
    }
    var finalConversationId = frame.getConversationId();
    history
        .markDeleted(messageId, fromUserId)
        .thenAccept(
            deleted -> {
              if (!Boolean.TRUE.equals(deleted)) {
                return;
              }
              var outgoing =
                  Frame.newBuilder()
                      .setId(frame.getId())
                      .setType(FrameType.DELETE)
                      .setFromUserId(fromUserId.toString())
                      .setConversationId(finalConversationId)
                      .setTs(now())
                      .build();
              if (!messageDelivery.deliverLocally(outgoing)) {
                messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
              }
            })
        .exceptionally(
            ex -> {
              logDbPoolThrottled("failed to delete message {}", messageId, ex);
              return null;
            });
  }

  /**
   * Ghim/bỏ ghim tin {@code frame.getId()} -- {@code frame.getBodyJson()} = {@code {"scope":
   * "shared"|"private", "pinned": true|false}}. "shared": persist bảng {@code message_pins_shared}
   * RỒI fan-out cho mọi subscriber khác (cùng pattern {@link #handleReaction}/{@link #handleDelete})
   * -- ai trong conversation cũng ghim/bỏ ghim chung được, không riêng admin. "private": CHỈ persist
   * bảng {@code message_pins_private}, KHÔNG fan-out gì cả -- tránh lộ cho thành viên khác biết ai
   * đang ghim riêng gì; các tab/thiết bị khác của chính người đó tự đồng bộ lại qua
   * {@code GET /pins} lần sau mở tab Pins.
   */
  private void handlePin(ChatSession session, Frame frame) {
    var fromUserId = UUIDUtils.parseOrDefault(frame.getFromUserId());
    var messageId = UUIDUtils.parseOrDefault(frame.getId());
    if (fromUserId == null || messageId == null || isBlank(frame.getConversationId())) {
      return;
    }
    UUID conversationId;
    try {
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException e) {
      return;
    }
    if (frame.getBodyJson().isEmpty()) {
      return;
    }
    var body = (JsonObject) Json.decodeValue(frame.getBodyJson());
    var scope = body.getString("scope");
    var pinned = body.getBoolean("pinned", Boolean.FALSE);
    if (!"shared".equals(scope) && !"private".equals(scope)) {
      return;
    }
    var finalConversationId = conversationId;
    if ("private".equals(scope)) {
      var persist = Boolean.TRUE.equals(pinned)
          ? pins.pinPrivate(finalConversationId, messageId, fromUserId)
          : pins.unpinPrivate(messageId, fromUserId);
      persist.exceptionally(ex -> {
        logDbPoolThrottled("failed to save private pin for message {}", messageId, ex);
        return null;
      });
      return;
    }
    var persist = Boolean.TRUE.equals(pinned)
        ? pins.pinShared(finalConversationId, messageId, fromUserId)
        : pins.unpinShared(messageId);
    persist
        .thenAccept(unused -> {
          var outgoing =
              Frame.newBuilder()
                  .setId(frame.getId())
                  .setType(FrameType.PIN)
                  .setFromUserId(fromUserId.toString())
                  .setConversationId(frame.getConversationId())
                  .setBodyJson(Json.encode(new JsonObject().put("scope", "shared").put("pinned", pinned)))
                  .setTs(now())
                  .build();
          if (!messageDelivery.deliverLocally(outgoing)) {
            messageDelivery.forwardToOwningNode(outgoing, routingVersionSync.currentVersion());
          }
        })
        .exceptionally(ex -> {
          logDbPoolThrottled("failed to save shared pin for message {}", messageId, ex);
          return null;
        });
  }

  /**
   * Báo cho herald "tin nhắn vừa gửi tới conversationId X, đây là member (trừ người gửi)" — herald
   * tự lọc ai đang online (đã nhận real-time rồi, không cần noti) vs offline (lưu noti), xem
   * {@code NotificationConsumer}. Best-effort, không chặn ACK — cùng tinh thần {@link #persistMessage},
   * dùng chung throttle log khi DB pool quá tải (đọc {@code membership.getMembers} cũng qua pool đó).
   */
  private void publishNotificationCandidates(Frame outgoing) {
    UUID conversationId;
    UUID fromUserId;
    try {
      conversationId = UUID.fromString(outgoing.getConversationId());
      fromUserId = UUID.fromString(outgoing.getFromUserId());
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    var finalFromUserId = fromUserId;
    membership
        .getMembers(conversationId)
        .thenCompose(
            members -> membership
                .getMutedUserIds(conversationId)
                .thenApply(
                    mutedUserIds -> members.stream()
                        .filter(id -> !id.equals(finalFromUserId) && !mutedUserIds.contains(id))
                        .map(UUID::toString)
                        .toList()))
        .thenAccept(
            candidateUserIds -> {
              if (candidateUserIds.isEmpty()) {
                return;
              }
              var bodyPreview = outgoing.getBodyJson().isEmpty() ? null : outgoing.getBodyJson();
              var payload =
                  new JsonObject()
                      .put("conversationId", conversationId.toString())
                      .put("fromUserId", finalFromUserId.toString())
                      .put("candidateUserIds", candidateUserIds)
                      .put("bodyPreview", bodyPreview)
                      .put("messageId", outgoing.getId())
                      .put("ts", outgoing.getTs());
              vertx.eventBus().publish(NOTIFY_CANDIDATES_ADDRESS, payload);
            })
        .exceptionally(
            ex -> {
              logDbPoolThrottled("failed to compute notification candidates for conversation {}", conversationId, ex);
              return null;
            });
  }

  /**
   * Ghi lịch sử tin nhắn — best-effort, không chặn đường real-time (deliverLocally/forwardToOwningNode/
   * ACK không đợi write này). Dùng field đã chuẩn hoá của {@code outgoing} (server tự stamp), không
   * đọc lại {@code frame} gốc. Bỏ qua lặng lẽ nếu conversationId sai định dạng — không phải lỗi cần
   * báo client.
   */
  private void persistMessage(Frame frame, Frame outgoing) {
    UUID conversationId;
    UUID fromUserId;
    try {
      conversationId = UUID.fromString(outgoing.getConversationId());
      fromUserId = UUID.fromString(outgoing.getFromUserId());
    } catch (IllegalArgumentException | NullPointerException e) {
      return;
    }
    // messageId PHAI la id client da biet (outgoing.getId(), = frame.getId() client tu sinh) --
    // KHONG duoc tu sinh UUID rieng o day: SEEN/REACTION client gui sau nay dung DUNG id nay de
    // tham chieu (xem handleSeen/handleReaction), phai khop CHINH XAC voi PRIMARY KEY cua dong nay
    // thi listMessages moi JOIN ra dung "seen"/"reactions" -- tung la bug that (dung random UUID rieng
    // khien SEEN/REACTION luon tham chieu toi 1 id khong ton tai, "seen"/"reactions" luon rong sau
    // reload du da luu DB dung). Fallback random UUID neu client lo gui id khong phai dinh dang UUID
    // (khong nen xay ra voi client dung san, nhung khong de crash/mat tin nhan vi 1 id sai dinh dang).
    UUID messageId;
    try {
      messageId = UUID.fromString(outgoing.getId());
    } catch (IllegalArgumentException e) {
      messageId = UUID.randomUUID();
    }
    Object body = outgoing.getBodyJson().isEmpty() ? null : Json.decodeValue(outgoing.getBodyJson());
    var savedMessageId = messageId;
    history
        .saveMessage(messageId, conversationId, fromUserId, body, outgoing.getTs())
        // Tự người GỬI "đọc" luôn chính tin mình vừa gửi -- tiến LUÔN con trỏ đã đọc
        // (conversation_reads, xem MessageHistoryRegistry#markRead) của họ tới tin này. Không có
        // bước này: gửi 1 loạt tin liên tiếp mà bên kia chưa kịp đọc gì thêm sẽ khiến con trỏ đã đọc
        // của MÌNH kẹt lại ở tin CUỐI CÙNG bên kia từng gửi (chỉ tiến khi có READ, mà READ chỉ gửi
        // cho tin CỦA NGƯỜI KHÁC) -- mở lại conversation sẽ hiểu lầm toàn bộ tin mình vừa gửi là
        // "chưa đọc", cuộn lệch về đúng chỗ tin cuối bên kia gửi thay vì xuống đáy thật. message_reads
        // ghi thêm dòng (message_id, fromUserId) này KHÔNG ảnh hưởng cột "seen" trả cho client (đã lọc
        // {@code mr.user_id != m.from_user_id}, xem listMessages) -- chỉ phục vụ tiến con trỏ.
        .thenCompose(unused -> history.markRead(savedMessageId, fromUserId))
        .whenComplete(
            (unused, ex) -> {
              if (ex != null) {
                logDbPoolThrottled("failed to persist message " + frame.getId() + " for conversation {}", conversationId, ex);
                return;
              }
              // CHỈ enrich SAU khi dòng đã thật sự nằm trong DB -- UPDATE body mà chạy trước INSERT
              // thì rowCount() = 0, preview mất luôn (race thật: fetch og: vài trăm ms có thể nhanh
              // hơn 1 write DB lúc pool đang bận).
              enrichLinkPreview(savedMessageId, body);
              extractMessageLinks(conversationId, savedMessageId, fromUserId, body);
              notifyReplyAndMentions(conversationId, fromUserId, savedMessageId, body, outgoing.getTs());
            });
  }

  /**
   * Trích mọi URL trong {@code body.message} vào bảng {@code message_links} cho tab "Links" -- xem
   * {@link MessageLinkRegistry#extractAndSave}. Best-effort, không chặn gì cả -- chạy sau khi tin đã
   * lưu xong, cùng chỗ với {@link #enrichLinkPreview}.
   */
  private void extractMessageLinks(UUID conversationId, UUID messageId, UUID fromUserId, Object body) {
    var messageText = body instanceof JsonObject j ? j.getString("message") : null;
    links.extractAndSave(conversationId, messageId, fromUserId, messageText)
        .exceptionally(ex -> {
          logDbPoolThrottled("failed to extract links for message {}", messageId, ex);
          return null;
        });
  }

  /**
   * Tin VỪA gửi có phải trả lời tin của ai đó, hoặc @nhắc ai đó không -- tạo noti "reply"/"mention"
   * cho đúng người liên quan (không phải người vừa gửi). LUÔN LƯU, không điều kiện online/offline
   * (khác {@link #publishNotificationCandidates} -- xem javadoc {@code NotificationRegistry}).
   *
   * <p>{@code body.replyTo.fromUserId}: client tự đính kèm sẵn khi bấm nút Trả lời (xem
   * {@code messaging-core.js} nút replyBtn/setPendingReply) -- không cần tự JOIN lại bảng messages
   * để biết tin gốc của ai. {@code body.mentionedUserIds}: client tự tính SẴN lúc gửi (quét
   * "@username" khớp đúng member của conversation, xem {@code sendMsg} trong messaging-core.js) --
   * server KHÔNG tự parse lại text ở đây (tránh phải thêm 1 bảng tra username↔userId riêng vào
   * đường xử lý tin nóng); chấp nhận client tự khai đúng vì đây chỉ ảnh hưởng tới VIỆC AI ĐÓ CÓ THẤY
   * 1 THÔNG BÁO PHỤ hay không, không phải quyền truy cập/nội dung tin nhắn thật.
   */
  private void notifyReplyAndMentions(UUID conversationId, UUID fromUserId, UUID messageId, Object body, long ts) {
    if (!(body instanceof JsonObject json)) {
      return;
    }
    var messageText = json.getString("message");
    var preview = messageText != null && messageText.length() > 140 ? messageText.substring(0, 140) : messageText;

    var replyTo = json.getJsonObject("replyTo");
    if (replyTo != null) {
      var repliedOwner = UUIDUtils.parseOrDefault(replyTo.getString("fromUserId"));
      if (repliedOwner != null && !repliedOwner.equals(fromUserId)) {
        // ts == messageTs ở đây -- tin reply CHÍNH LÀ tin vừa gửi, không có độ lệch nào giữa
        // "lúc xảy ra" và "giờ tin gốc" như trường hợp reaction (xem handleReaction).
        createNotification(repliedOwner, conversationId, fromUserId, messageId, "reply", preview, ts, ts);
      }
    }

    var mentioned = json.getJsonArray("mentionedUserIds");
    if (mentioned != null) {
      for (var raw : mentioned) {
        var userId = UUIDUtils.parseOrDefault(String.valueOf(raw));
        if (userId != null && !userId.equals(fromUserId)) {
          createNotification(userId, conversationId, fromUserId, messageId, "mention", preview, ts, ts);
        }
      }
    }
  }

  /**
   * {@code ts}: lúc SỰ KIỆN xảy ra (react/trả lời/nhắc tên) -- quyết định thứ tự "mới nhất trước"
   * trong chuông (xem {@code NotificationRegistry#listForUser}). {@code messageTs}: giờ tin GỐC
   * (được nhắc/được react/được trả lời) THẬT SỰ được gửi -- {@code jumpToMessage} bên client dùng
   * riêng giá trị này làm mốc "seek" khi tin chưa có sẵn trong khung chat. Với mention/reply, tin
   * gốc CHÍNH LÀ tin vừa gửi nên 2 giá trị này luôn bằng nhau; với reaction thì có thể lệch xa
   * (reaction vào 1 tin rất cũ) -- xem javadoc {@code MessageHistoryRegistry.MessageOwner}.
   */
  private void createNotification(
      UUID userId, UUID conversationId, UUID fromUserId, UUID messageId, String type, String bodyPreview, long ts, long messageTs) {
    membership
        .isMuted(conversationId, userId)
        .thenAccept(
            muted -> {
              if (muted) {
                return;
              }
              notifications
                  .create(UUID.randomUUID(), userId, conversationId, fromUserId, messageId, type, bodyPreview, ts, messageTs)
                  .exceptionally(ex -> {
                    logDbPoolThrottled("failed to persist " + type + " notification for message {}", conversationId, ex);
                    return null;
                  });
            })
        .exceptionally(
            ex -> {
              logDbPoolThrottled("failed to check mute state for " + type + " notification {}", conversationId, ex);
              return null;
            });
  }

  /**
   * Vá {@code body.preview} vào 1 tin chỉ-chứa-1-link mà client KHÔNG tự resolve trước khi gửi (client
   * cũ, hoặc client bấm gửi lúc fetch og: chưa về -- xem demo.html {@code maybeFetchComposeLinkPreview}).
   * Client nào ĐÃ gửi kèm {@code preview} thì {@link LinkPreviewService#soleUrlToPreview} trả null,
   * không fetch lại lần 2.
   *
   * <p>Best-effort tuyệt đối: fetch ra internet có thể chậm tới vài giây nên CHẠY SAU khi đã ACK +
   * fan-out + persist xong, không ai đợi nó; lỗi/thì tin vẫn nguyên vẹn, chỉ thiếu preview (client
   * render tự fetch bù qua hall {@code GET /link-preview}).
   *
   * <p>Giống pha 2 của Slack ({@code chat.unfurlLink} chạy sau {@code chat.postMessage}), khác ở chỗ
   * pingo KHÔNG broadcast {@code message_changed}: người nhận đang online vẫn thấy card qua đường
   * fallback của client, còn lần mở lại sau (đọc từ DB) đã có preview sẵn nên vẽ ĐỒNG BỘ đúng kích
   * thước, không còn "nở" sau làm đẩy các tin khác.
   */
  private void enrichLinkPreview(UUID messageId, Object body) {
    var url = LinkPreviewService.soleUrlToPreview(body);
    if (url == null) {
      return;
    }
    linkPreviewService
        .fetch(url)
        .whenComplete(
            (preview, ex) -> {
              if (ex != null || preview == null) {
                return; // không có metadata -- giữ body nguyên trạng, client vẽ chữ link trần
              }
              // cast an toàn: soleUrlToPreview chỉ trả url khác null khi body đã là JsonObject
              var enriched = ((JsonObject) body).copy().put("preview", preview);
              history
                  .updateBodyJson(messageId, enriched.encode())
                  .exceptionally(
                      dbEx -> {
                        logDbPoolThrottled("failed to store link preview for message {}", messageId, dbEx);
                        return null;
                      });
            });
  }

  /**
   * DB pool quá tải ({@link ConnectionPoolTooBusyException}) là tín hiệu backpressure THƯỜNG GẶP
   * dưới tải cao, không phải lỗi lạ. Vấn đề thực tế đã gặp: dưới tải nặng, exception này có thể
   * xảy ra hàng nghìn lần/giây — dù chỉ log 1 dòng ngắn (không full stack trace), tốc độ GHI LOG
   * THÔ (mỗi dòng qua Disruptor ring buffer rồi Console appender ghi xuống container stdout pipe)
   * vẫn đủ lớn để làm nghẽn chính pipe đó (container runtime đọc không kịp), rồi ACK chậm theo,
   * harbor tưởng stream chết (MESSAGE_ACK_TIMEOUT) rồi evict/error hàng loạt — vòng lặp tự khuếch
   * đại. Throttle còn lại tối đa 1 dòng/giây cho DÙNG 1 LOẠI LỖI này (dùng chung giữa
   * {@link #persistMessage} và {@link #publishNotificationCandidates} — cả 2 đọc/ghi CÙNG 1 pool),
   * kèm số lần bị nén, là đủ để vòng lặp không thể hình thành trong khi vẫn giữ được tín hiệu debug
   * thật sự cần.
   */
  private void logDbPoolThrottled(String messageTemplate, UUID conversationId, Throwable ex) {
    var rootCause = ExceptionUtils.getRootCause(ex);
    if (rootCause instanceof ConnectionPoolTooBusyException) {
      var now = System.currentTimeMillis();
      var last = lastPoolExhaustedLogAt.get();
      if (now - last >= 1000 && lastPoolExhaustedLogAt.compareAndSet(last, now)) {
        var suppressed = poolExhaustedSuppressedCount.getAndSet(0);
        log.warn(messageTemplate + ": {} ({} lan khac bi nen trong 1s qua)", conversationId, rootCause.getMessage(), suppressed);
      } else {
        poolExhaustedSuppressedCount.incrementAndGet();
      }
    } else {
      log.warn(messageTemplate, conversationId, ex);
    }
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
