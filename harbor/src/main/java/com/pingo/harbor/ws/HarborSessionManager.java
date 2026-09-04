package com.pingo.harbor.ws;

import com.pingo.connector.PingoConnector;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.support.ConversationIds;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.common.token.NdlTokenException;
import com.pingo.harbor.ws.backend.BackendStreamGateway;
import com.pingo.harbor.ws.dto.MessageType;
import com.pingo.harbor.ws.dto.SocketFrame;
import com.pingo.harbor.ws.dto.SocketFrames;
import com.pingo.harbor.ws.routing.RoutingVersionSync;
import com.pingo.harbor.ws.session.HarborSession;
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
 * "Cửa trước" của các session client: nhận connection, dispatch protocol AUTH/MESSAGE/PING, dọn
 * session idle. Giao tiếp với backend colony cho {@link BackendStreamGateway}, đồng bộ routing
 * version cho {@link RoutingVersionSync}.
 */
@Slf4j
@NoArgsConstructor
public class HarborSessionManager {

    private static final long HEARTBEAT_SWEEP_INTERVAL_MS = 15_000;
    private static final long CLIENT_IDLE_TIMEOUT_MS = 60_000;
    private static final long DRAIN_GRACE_MS = 2_000;

    @Getter
    private String serverId;
    private final Map<String, HarborSession> sessions = new ConcurrentHashMap<>();
    private Vertx vertx;
    private BackendStreamGateway backendStreamGateway;
    private RoutingVersionSync routingVersionSync;
    private JwtHelper jwtHelper;
    /**
     * false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe.
     */
    @Getter
    private volatile boolean ready = true;

    public HarborSessionManager(String serverId, Vertx vertx, PingoConnector connector, LegoConfig1 config, JwtHelper jwtHelper) {
        this.serverId = serverId;
        this.vertx = vertx;
        this.backendStreamGateway = new BackendStreamGateway(vertx, connector, config, this::sendToClient);
        this.routingVersionSync = new RoutingVersionSync(vertx, connector, backendStreamGateway, sessions, this::sendToClient);
        this.jwtHelper = jwtHelper;
        vertx.setPeriodic(HEARTBEAT_SWEEP_INTERVAL_MS, tid -> heartbeatSweep());
    }

    /**
     * Lúc pod chuẩn bị tắt: đánh dấu not-ready, báo GOAWAY cho client reconnect sang pod khác, rồi chờ 1 nhịp.
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
        var session = new HarborSession(id, serverId, socket);
        sessions.put(id, session);

        socket.handler(buffer -> onClientFrame(session, buffer));
        socket.closeHandler(any -> onClose(session));
        socket.exceptionHandler(ex -> onException(session, ex));
    }

    private void onClose(HarborSession closedSession) {
        var session = sessions.remove(closedSession.getId());
        if (session == null) {
            return;
        }
        backendStreamGateway.closeAllStreams(session);
        session.cleanUpAfterClose();
    }

    private void onException(HarborSession session, Throwable ex) {
        log.error("an error occurred while reading socket of session {}", session.getId(), ex);
    }

    /**
     * Chỉ quét session client idle — liveness backend đã do HTTP/2 keepalive tự lo (xem {@code BackendStreamGateway#newClient}).
     */
    private void heartbeatSweep() {
        var now = System.currentTimeMillis();
        for (var session : List.copyOf(sessions.values())) {
            if (now - session.getLastSeenAt() > CLIENT_IDLE_TIMEOUT_MS) {
                log.info("closing idle client session {} (userId={})", session.getId(), session.getUserId());
                session.close();
            }
        }
    }

    private void onClientFrame(HarborSession session, Buffer buffer) {
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

    /**
     * Xác định userId bằng verify chữ ký JWT (secret dùng chung, không gọi ngược lại colony) —
     * không tự mở kết nối backend (đó là việc của SUBSCRIBE).
     */
    private void handleAuth(HarborSession session, SocketFrame frame) {
        if (isBlank(frame.getToken())) {
            sendToClient(session, SocketFrames.authError(frame.getId(), "missing token"));
            return;
        }
        UUID userId;
        try {
            var decoded = jwtHelper.decode(frame.getToken());
            userId = decoded.getUUID("userId");
        } catch (NdlTokenException e) {
            sendToClient(session, SocketFrames.authError(frame.getId(), "invalid or expired token"));
            return;
        }
        if (userId == null) {
            sendToClient(session, SocketFrames.authError(frame.getId(), "invalid token"));
            return;
        }
        session.setUserId(userId);
        sendToClient(session, SocketFrame.builder().type(MessageType.AUTH_OK).id(frame.getId()).ts(System.currentTimeMillis()).build());
    }

    /**
     * Đăng ký nhận/gửi tin cho 1 conversationId — mở/dùng lại backend stream xuống đúng pod sở hữu nó.
     */
    private void handleSubscribe(HarborSession session, SocketFrame frame) {
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
        backendStreamGateway
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

    /**
     * Suy ra conversationId (ưu tiên field mới, fallback DM tất định từ toUserId) rồi forward xuống backend.
     */
    private void handleMessage(HarborSession session, SocketFrame frame) {
        if (session.getUserId() == null) {
            sendToClient(session, SocketFrames.error(frame.getId(), "not authenticated"));
            return;
        }
        try {
            UUID conversationId = resolveConversationId(session, frame);
            var outgoing = frame.toBuilder().conversationId(conversationId.toString()).build();
            backendStreamGateway.sendMessage(session, outgoing, conversationId, routingVersionSync.currentVersion());
        } catch (IllegalArgumentException e) {
            sendToClient(session, SocketFrames.error(frame.getId(), e.getMessage()));
        }
    }

    /**
     * conversationId tường minh nếu client gửi kèm, ngược lại suy DM tất định từ toUserId — ném
     * {@link IllegalArgumentException} với message dùng thẳng làm lý do lỗi trả về client khi thiếu/sai.
     */
    private static UUID resolveConversationId(HarborSession session, SocketFrame frame) {
        if (!isBlank(frame.getConversationId())) {
            try {
                return UUID.fromString(frame.getConversationId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("invalid conversationId");
            }
        }
        if (isBlank(frame.getToUserId())) {
            throw new IllegalArgumentException("missing conversationId/toUserId");
        }
        try {
            return ConversationIds.dmId(session.getUserId(), UUID.fromString(frame.getToUserId()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid toUserId");
        }
    }

    private void sendToClient(HarborSession session, SocketFrame frame) {
        session.send(frame.encode());
    }

    private String generateSessionId() {
        return UUIDUtils.timeBasedUuidAsString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
