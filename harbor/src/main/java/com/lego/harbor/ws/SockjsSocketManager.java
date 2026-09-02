package com.lego.harbor.ws;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.support.ConversationIds;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.harbor.ws.backend.BackendLinkGateway;
import com.lego.harbor.ws.dto.MessageType;
import com.lego.harbor.ws.dto.SocketFrame;
import com.lego.harbor.ws.dto.SocketFrames;
import com.lego.harbor.ws.routing.RoutingVersionSync;
import com.lego.harbor.ws.session.SockjsSocket;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý các SockJS session công khai (public) từ client, đóng vai trò "cửa trước": nhận
 * connection, dispatch protocol AUTH/MESSAGE/PING, và dọn dẹp session bị idle. Việc thật sự nói
 * chuyện với backend colony được giao hẳn cho {@link BackendLinkGateway}; việc đồng bộ
 * routing table version được giao cho {@link RoutingVersionSync} — 2 lớp này trước đây gộp chung
 * vào class này, tách ra để mỗi class chỉ lo đúng 1 việc.
 */
@Slf4j
@NoArgsConstructor
public class SockjsSocketManager {

  private static final long HEARTBEAT_SWEEP_INTERVAL_MS = 15_000;
  private static final long CLIENT_IDLE_TIMEOUT_MS = 60_000;
  private static final long DRAIN_GRACE_MS = 2_000;

  @Getter private String serverId;
  private final Map<String, SockjsSocket> sessions = new ConcurrentHashMap<>();
  private Vertx vertx;
  private BackendLinkGateway backendLinkGateway;
  private RoutingVersionSync routingVersionSync;
  /** false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe (xem {@code HealthCheckVerticle}). */
  @Getter private volatile boolean ready = true;

  public SockjsSocketManager(String serverId, Vertx vertx, PingoConnector connector, LegoConfig1 config) {
    this.serverId = serverId;
    this.vertx = vertx;
    this.backendLinkGateway = new BackendLinkGateway(vertx, connector, config, this::sendToClient);
    this.routingVersionSync = new RoutingVersionSync(vertx, connector, backendLinkGateway, sessions, this::sendToClient);
    vertx.setPeriodic(HEARTBEAT_SWEEP_INTERVAL_MS, tid -> heartbeatSweep());
  }

  /**
   * Gọi lúc pod chuẩn bị tắt (xem {@code HarborApp.doStop()}): đánh dấu not-ready ngay (cho
   * readinessProbe fail sớm), báo GOAWAY cho mọi client đang mở để họ chủ động reconnect sang pod
   * khác, rồi chờ 1 khoảng ngắn cho kịp trước khi caller đóng hẳn {@code Vertx}.
   */
  public CompletionStage<Void> drain() {
    ready = false;
    log.info("draining {} client session(s) before shutdown", sessions.size());
    var goAway = SocketFrame.builder().type(MessageType.GOAWAY).id(UUIDUtils.timeBasedUuidAsString()).reason("gateway shutting down").ts(System.currentTimeMillis()).build();
    for (var session : sessions.values()) {
      sendToClient(session, goAway);
    }
    var delayed = new CompletableFuture<Void>();
    vertx.setTimer(DRAIN_GRACE_MS, tid -> delayed.complete(null));
    return delayed;
  }

  void onConnection(SockJSSocket socket) {
    var id = generateSessionId();
    var session = new SockjsSocket(id, serverId, socket);
    sessions.put(id, session);
    socket.handler(buffer -> onClientFrame(session, buffer));
    socket.closeHandler(any -> onClose(session));
    socket.exceptionHandler(ex -> onException(session, ex));
  }

  private void onClose(SockjsSocket closedSession) {
    var session = sessions.remove(closedSession.getId());
    if (session == null) {
      return;
    }
    session.cleanUpAfterClose();
  }

  private void onException(SockjsSocket session, Throwable ex) {
    log.error("an error occurred while reading socket of session {}", session.getId(), ex);
  }

  private void heartbeatSweep() {
    var now = System.currentTimeMillis();
    for (var session : List.copyOf(sessions.values())) {
      if (now - session.getLastSeenAt() > CLIENT_IDLE_TIMEOUT_MS) {
        log.info("closing idle client session {} (userId={})", session.getId(), session.getUserId());
        session.close();
        continue;
      }
      backendLinkGateway.pingIfDue(session, now);
    }
  }

  private void onClientFrame(SockjsSocket session, Buffer buffer) {
    session.setLastSeenAt(System.currentTimeMillis());
    var frameOpt = SocketFrame.decode(buffer);
    if (frameOpt.isEmpty()) {
      log.debug("dropping undecodable frame from client session {}", session.getId());
      return;
    }
    var frame = frameOpt.get();
    switch (frame.getType()) {
      case AUTH -> handleAuth(session, frame);
      case SUBSCRIBE -> handleSubscribe(session, frame);
      case MESSAGE -> handleMessage(session, frame);
      case PING -> sendToClient(session, SocketFrames.pong(frame.getId()));
      default -> log.debug("unsupported frame type {} from client session {}", frame.getType(), session.getId());
    }
  }

  /** Chỉ còn xác định danh tính (userId) cho session — xử lý cục bộ hoàn toàn, KHÔNG còn tự động mở kết nối xuống backend nữa (xem SUBSCRIBE). */
  private void handleAuth(SockjsSocket session, SocketFrame frame) {
    if (isBlank(frame.getFromUserId())) {
      sendToClient(session, SocketFrames.error(frame.getId(), "missing fromUserId"));
      return;
    }
    UUID userId;
    try {
      userId = UUID.fromString(frame.getFromUserId());
    } catch (IllegalArgumentException e) {
      sendToClient(session, SocketFrames.error(frame.getId(), "invalid fromUserId"));
      return;
    }
    session.setUserId(userId);
    sendToClient(session, SocketFrame.builder().type(MessageType.AUTH_OK).id(frame.getId()).ts(System.currentTimeMillis()).build());
  }

  /** Đăng ký nhận/gửi tin cho đúng 1 conversationId — thật sự mở/dùng lại backend link xuống đúng pod sở hữu nó, thay cho vai trò cũ của AUTH. */
  private void handleSubscribe(SockjsSocket session, SocketFrame frame) {
    if (session.getUserId() == null) {
      sendToClient(session, SocketFrames.subscribeError(frame.getId(), "not authenticated"));
      return;
    }
    UUID conversationId;
    try {
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException | NullPointerException e) {
      sendToClient(session, SocketFrames.subscribeError(frame.getId(), "invalid/missing conversationId"));
      return;
    }
    var members = new ArrayList<UUID>();
    if (frame.getMemberUserIds() != null) {
      for (var memberUserId : frame.getMemberUserIds()) {
        var parsed = UUIDUtils.parseOrDefault(memberUserId);
        if (parsed != null) {
          members.add(parsed);
        }
      }
    }
    backendLinkGateway
        .subscribe(session, frame.getId(), conversationId, members, routingVersionSync.currentVersion())
        .exceptionally(
            ex -> {
              log.warn("subscribe failed for session {} conversation {}", session.getId(), conversationId, ex);
              var cause = ex.getCause() != null ? ex.getCause() : ex;
              var reason = cause.getMessage() != null ? cause.getMessage() : "routing/backend connect failed";
              sendToClient(session, SocketFrames.subscribeError(frame.getId(), reason));
              return null;
            });
  }

  /** Suy ra conversationId (ưu tiên field mới, fallback DM tất định từ toUserId) rồi forward xuống đúng backend link. */
  private void handleMessage(SockjsSocket session, SocketFrame frame) {
    if (session.getUserId() == null) {
      sendToClient(session, SocketFrames.error(frame.getId(), "not authenticated"));
      return;
    }
    UUID conversationId;
    if (!isBlank(frame.getConversationId())) {
      try {
        conversationId = UUID.fromString(frame.getConversationId());
      } catch (IllegalArgumentException e) {
        sendToClient(session, SocketFrames.error(frame.getId(), "invalid conversationId"));
        return;
      }
    } else if (!isBlank(frame.getToUserId())) {
      UUID toUserId;
      try {
        toUserId = UUID.fromString(frame.getToUserId());
      } catch (IllegalArgumentException e) {
        sendToClient(session, SocketFrames.error(frame.getId(), "invalid toUserId"));
        return;
      }
      conversationId = ConversationIds.dmId(session.getUserId(), toUserId);
    } else {
      sendToClient(session, SocketFrames.error(frame.getId(), "missing conversationId/toUserId"));
      return;
    }
    var outgoing = frame.toBuilder().conversationId(conversationId.toString()).build();
    backendLinkGateway.sendMessage(session, outgoing, conversationId, routingVersionSync.currentVersion());
  }

  private void sendToClient(SockjsSocket session, SocketFrame frame) {
    session.send(frame.encode());
  }

  private String generateSessionId() {
    return UUIDUtils.timeBasedUuidAsString();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
