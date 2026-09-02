package com.lego.harbor.ws.dto;

/**
 * Các loại frame trao đổi trên chat socket (cả 2 chặng client&lt;-&gt;gateway và gateway&lt;-&gt;chat-backend).
 */
public enum MessageType {
    /**
     * Client -&gt; gateway: xác định danh tính (userId) cho connection — CHỈ còn xử lý cục bộ tại
     * gateway (không còn tự động mở kết nối xuống backend nữa, xem SUBSCRIBE).
     */
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
     * Cả 2 chiều: đăng ký (subscribe) nhận/gửi tin cho đúng 1 {@code conversationId} cụ thể trên
     * link này — bước handshake thật sự mở/dùng-lại kết nối xuống đúng pod backend sở hữu
     * conversation đó, thay thế vai trò cũ của AUTH. Có thể mang kèm {@code memberUserIds} để
     * lazy-create/join membership (xem SocketFrame).
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
     * Gateway -&gt; client: gateway sắp tắt (scale down/rolling update, xem
     * {@code SockjsSocketManager#drain()}) — báo trước để client chủ động đóng và reconnect sang pod
     * khác ngay, thay vì đợi phát hiện qua timeout của TCP close. Chỉ gateway gửi — colony không
     * có khái niệm này nên không có trong bản {@code MessageType} của module đó.
     */
    GOAWAY,
    /**
     * Gateway -&gt; client: báo cho client biết vừa được thêm làm member của 1 conversationId (do
     * người khác SUBSCRIBE với memberUserIds mang tên mình, hoặc do ai đó nhắn DM cho mình lần đầu)
     * — gateway đã tự subscribe ngầm hộ (không rớt tin), frame này chỉ để client cập nhật UI (danh
     * sách hội thoại/badge...). Chỉ gateway gửi, colony không có khái niệm này (xem
     * {@code RoutingVersionSync#onMembershipChanged}).
     */
    CONVERSATION_ADDED
}
