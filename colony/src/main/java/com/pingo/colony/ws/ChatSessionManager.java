package com.pingo.colony.ws;

import com.pingo.connector.PingoConnector;
import com.pingo.core.common.exception.ExceptionUtils;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.chat.grpc.Frame;
import com.pingo.chat.grpc.FrameType;
import com.pingo.colony.ws.delivery.MessageDelivery;
import com.pingo.chat.domain.history.MessageHistoryRegistry;
import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.colony.ws.routing.RoutingVersionSync;
import com.pingo.colony.ws.session.ChatSession;
import com.pingo.colony.ws.session.SessionRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.ConnectionPoolTooBusyException;
import io.vertx.core.json.Json;
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

  private final String serverId;
  private final SessionRegistry registry = new SessionRegistry();
  private final ConversationMembershipRegistry membership;
  private final MessageHistoryRegistry history;
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
    session.send(Frame.newBuilder().setId(frame.getId()).setType(FrameType.ACK).setTs(now()).build());
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
    Object body = outgoing.getBodyJson().isEmpty() ? null : Json.decodeValue(outgoing.getBodyJson());
    history
        .saveMessage(UUID.randomUUID(), conversationId, fromUserId, body, outgoing.getTs())
        .exceptionally(
            ex -> {
              // DB pool qua tai (ConnectionPoolTooBusyException) la tin hieu backpressure THUONG GAP
              // duoi tai cao, khong phai loi la. Van de thuc te gap phai: duoi tai nang, exception nay
              // co the xay ra hang nghin lan/giay -- du chi log 1 dong ngan (khong full stack trace),
              // toc do GHI LOG THO (moi dong qua Disruptor ring buffer roi Console appender ghi xuong
              // container stdout pipe) van du lon de lam nghen chinh pipe do (container runtime doc
              // khong kip), roi ACK cham theo, harbor tuong stream chet (MESSAGE_ACK_TIMEOUT) roi
              // evict/error hang loat -- vong lap tu khuech dai (quan sat duoc: throughput sup do VA
              // "kubectl logs" tra ve rong dong thoi trong luc nay, ca 2 cung mot nguyen nhan). Throttle
              // con lai toi da 1 dong/giay cho dung 1 loai loi nay, kem so lan bi nen, la du de vong
              // lap khong the hinh thanh trong khi van giu duoc tin hieu debug that su can.
              var rootCause = ExceptionUtils.getRootCause(ex);
              if (rootCause instanceof ConnectionPoolTooBusyException) {
                var now = System.currentTimeMillis();
                var last = lastPoolExhaustedLogAt.get();
                if (now - last >= 1000 && lastPoolExhaustedLogAt.compareAndSet(last, now)) {
                  var suppressed = poolExhaustedSuppressedCount.getAndSet(0);
                  log.warn(
                      "failed to persist message {} for conversation {}: {} ({} lan khac bi nen trong 1s qua)",
                      frame.getId(), conversationId, rootCause.getMessage(), suppressed);
                } else {
                  poolExhaustedSuppressedCount.incrementAndGet();
                }
              } else {
                log.warn("failed to persist message {} for conversation {}", frame.getId(), conversationId, ex);
              }
              return null;
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
