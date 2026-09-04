package com.pingo.harbor.ws.dto;

/**
 * Các loại frame trao đổi trên chat socket client&lt;-&gt;gateway (SockJS/WebSocket) — chặng
 * gateway&lt;-&gt;chat-backend giờ dùng {@code FrameType} bên protobuf, xem
 * {@code discovery/src/main/proto/link.proto}.
 */
public enum MessageType {
    /** Client -&gt; gateway: xác định userId cho connection — xử lý cục bộ, không tự mở kết nối backend (xem SUBSCRIBE). */
    AUTH,
    /**
     * Server -&gt; client/gateway: AUTH thành công.
     */
    AUTH_OK,
    /**
     * Server -&gt; client/gateway: AUTH thất bại (ví dụ userId không hợp lệ).
     */
    AUTH_ERROR,
    /**
     * Cả 2 chiều: đăng ký nhận/gửi tin cho 1 {@code conversationId} — handshake mở/dùng lại kết nối
     * xuống đúng pod backend sở hữu conversation đó. Có thể kèm {@code memberUserIds} để lazy-create/join membership.
     */
    SUBSCRIBE,
    /**
     * Server -&gt; caller: SUBSCRIBE thành công, đã là subscriber hợp lệ của conversation đó.
     */
    SUBSCRIBE_OK,
    /**
     * Server -&gt; caller: SUBSCRIBE thất bại (không phải member, thiếu conversationId, node đang drain...).
     */
    SUBSCRIBE_ERROR,
    /**
     * Cả 2 chiều: một tin nhắn chat, được định tuyến (route) tới đúng pod sở hữu {@code conversationId}.
     */
    MESSAGE,
    /**
     * Server -&gt; sender: xác nhận đã nhận và forward MESSAGE thành công.
     * Đây chỉ là ACK ở mức transport (server đã chuyển tiếp), KHÔNG đảm bảo người nhận đã thực sự đọc được.
     */
    ACK,
    /**
     * Server -&gt; sender/client: request không xử lý được, xem thêm field {@code reason} trong SocketFrame.
     */
    ERROR,
    /**
     * Cả 2 chiều: probe kiểm tra liveness (còn sống hay không) của connection, dùng cho heartbeat.
     */
    PING,
    /**
     * Cả 2 chiều: phản hồi của PING.
     */
    PONG,
    /**
     * Gateway -&gt; client: gateway sắp tắt (xem {@code HarborSessionManager#drain()}) — báo trước để
     * client chủ động reconnect sang pod khác, thay vì đợi timeout TCP close.
     */
    GOAWAY,
    /**
     * Gateway -&gt; client: vừa được thêm làm member của 1 conversationId — gateway đã tự subscribe
     * ngầm hộ, frame này chỉ để client cập nhật UI (xem {@code RoutingVersionSync#onMembershipChanged}).
     */
    CONVERSATION_ADDED
}
