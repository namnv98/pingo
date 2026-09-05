package com.pingo.harbor.ws;

import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.connector.PingoConnector;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.common.token.NdlTokenException;
import com.pingo.core.grpc.client.GrpcClientPool;
import com.pingo.harbor.ws.backend.BackendStreamGateway;
import com.pingo.harbor.ws.dto.MessageType;
import com.pingo.harbor.ws.dto.SocketFrame;
import com.pingo.harbor.ws.dto.SocketFrames;
import com.pingo.harbor.ws.routing.RoutingVersionSync;
import com.pingo.harbor.ws.session.HarborSession;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.ServerWebSocket;

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
    private ConversationMembershipRegistry membership;
    /**
     * false kể từ khi {@link #drain()} bắt đầu — dùng cho readinessProbe.
     */
    @Getter
    private volatile boolean ready = true;

    public HarborSessionManager(
            String serverId, Vertx vertx, PingoConnector connector, LegoConfig1 config, JwtHelper jwtHelper,
            ConversationMembershipRegistry membership) {
        this.serverId = serverId;
        this.vertx = vertx;
        this.backendStreamGateway = new BackendStreamGateway(vertx, connector, config, this::sendToClient, new GrpcClientPool(vertx));
        this.routingVersionSync = new RoutingVersionSync(vertx, connector, backendStreamGateway, sessions, this::sendToClient);
        this.jwtHelper = jwtHelper;
        this.membership = membership;
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

    public void onConnection(ServerWebSocket socket) {
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

    /**
     * {@code HttpClosedException: Connection was closed} la binh thuong -- client dong WS "tho"
     * (dong tab/mat mang/kill app) thay vi dong gon qua handshake WS close, xay ra thuong xuyen voi
     * moi WS server, khong phai loi. Ha xuong DEBUG cho dung ban chat; con lai (that su bat ngo) van
     * giu ERROR de khong bo sot tin hieu can chu y.
     */
    private void onException(HarborSession session, Throwable ex) {
        if (ex instanceof HttpClosedException) {
            log.debug("client session {} closed the socket abruptly", session.getId(), ex);
            return;
        }
        log.error("an error occurred while reading socket of session {}", session.getId(), ex);
    }

    /**
     * Quét session client idle — liveness backend đã do HTTP/2 keepalive tự lo (xem {@code BackendStreamGateway#newClient}).
     * {@code lastSeenAt} chỉ được cập nhật khi CLIENT tự gửi frame lên ({@link #onClientFrame}) --
     * 1 session chỉ NHẬN tin (vd thành viên group không chủ động gõ gì) không bao giờ tự sinh hoạt
     * động, nên nếu server không chủ động hỏi thăm thì sau {@code CLIENT_IDLE_TIMEOUT_MS} sẽ bị coi
     * là chết và đóng oan dù đang nhận tin bình thường -- demo.html reconnect lại sau đó nhưng KHÔNG
     * tự SUBSCRIBE lại các conversation cũ, nên mọi tin nhắn tiếp theo coi như mất (đã gặp thật: "tạo
     * group xong chỉ nhận 1 tin đầu rồi miss" khi người nhận không gõ gì trong lúc chờ). Chủ động PING
     * khi đã idle quá NỬA ngưỡng, cho client còn nửa thời gian còn lại để PONG (cũng đi qua
     * {@link #onClientFrame}, tự cập nhật lastSeenAt) trước khi bị coi là chết thật sự.
     */
    private void heartbeatSweep() {
        var now = System.currentTimeMillis();
        for (var session : List.copyOf(sessions.values())) {
            var idleMs = now - session.getLastSeenAt();
            if (idleMs > CLIENT_IDLE_TIMEOUT_MS) {
                log.info("closing idle client session {} (userId={})", session.getId(), session.getUserId());
                session.close();
            } else if (idleMs > CLIENT_IDLE_TIMEOUT_MS / 2) {
                sendToClient(session, SocketFrames.ping(UUIDUtils.timeBasedUuidAsString()));
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
        sendToClient(session, SocketFrame.builder().type(MessageType.AUTH_OK).id(frame.getId()).serverId(serverId).ts(System.currentTimeMillis()).build());
        autoSubscribeAllConversations(session, userId);
    }

    /**
     * Tự subscribe session vào TOÀN BỘ conversation user đang là thành viên, ngay sau AUTH_OK —
     * tránh việc client phải tự biết trước danh sách rồi gửi 1 frame SUBSCRIBE/conversationId (user
     * có N conversation sẽ cần N frame, N lớn thì không ổn). Chạy ngầm, không relay SUBSCRIBE_OK/lỗi
     * cho client (dùng lại {@code wakeSubscribe}, cùng đường với thông báo "vừa được thêm vào
     * conversation mới" ở {@link RoutingVersionSync}). Conversation MỚI giờ tạo qua
     * {@code POST /conversations} (hall) — không còn cách nào client cần tự gửi SUBSCRIBE tường
     * minh nữa (kể cả lúc tạo mới: hall tự publish membership-changed, harbor tự wake-subscribe
     * đúng như 1 thành viên khác được mời, xem ARCHITECTURE.md mục 12). Không dùng
     * {@code wakeSubscribe} theo từng conversationId (N conversation = N handshake) — gom hết
     * conversationId rồi giao 1 lần cho {@code BackendStreamGateway#autoSubscribeAll} tự nhóm theo
     * pod đích.
     */
    private void autoSubscribeAllConversations(HarborSession session, UUID userId) {
        membership
                .listConversationsForUser(userId)
                .thenCompose(
                        conversations -> {
                            var conversationIds = new ArrayList<UUID>();
                            for (var item : conversations) {
                                var conv = (io.vertx.core.json.JsonObject) item;
                                var conversationId = UUIDUtils.parseOrDefault(conv.getString("conversationId"));
                                if (conversationId != null) {
                                    conversationIds.add(conversationId);
                                }
                            }
                            return backendStreamGateway.autoSubscribeAll(session, conversationIds, routingVersionSync.currentVersion());
                        })
                .exceptionally(
                        ex -> {
                            log.warn("failed to auto-subscribe conversations for userId {}", userId, ex);
                            return null;
                        });
    }

    /**
     * Đăng ký nhận/gửi tin cho 1 conversationId — mở/dùng lại backend stream xuống đúng pod sở hữu
     * nó. Chỉ còn cần cho case defensive/idempotent (client tự gọi lại "cho chắc" — vô hại nếu đã
     * subscribe rồi); dòng chảy chính (conversation có sẵn hoặc mới tạo) đều tự động qua
     * {@link #autoSubscribeAllConversations}/{@link RoutingVersionSync} rồi.
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
        backendStreamGateway
                .subscribe(session, frame.getId(), conversationId, routingVersionSync.currentVersion())
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
     * conversationId giờ LUÔN phải tường minh — không còn suy DM tất định từ toUserId nữa. Mọi
     * conversation (cả DM lẫn group) đều phải tạo qua {@code POST /conversations} (hall) trước,
     * client dùng conversationId server trả về từ đó (giống Slack/Discord: bắt buộc gọi API tạo
     * trước khi gửi tin được, xem ARCHITECTURE.md mục 12).
     */
    private void handleMessage(HarborSession session, SocketFrame frame) {
        if (session.getUserId() == null) {
            sendToClient(session, SocketFrames.error(frame.getId(), "not authenticated"));
            return;
        }
        UUID conversationId;
        try {
            conversationId = UUID.fromString(frame.getConversationId());
        } catch (IllegalArgumentException | NullPointerException e) {
            sendToClient(session, SocketFrames.error(frame.getId(), "missing/invalid conversationId"));
            return;
        }
        backendStreamGateway.sendMessage(session, frame, conversationId, routingVersionSync.currentVersion());
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
