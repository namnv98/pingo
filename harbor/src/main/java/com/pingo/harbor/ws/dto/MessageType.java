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
     * xuống đúng pod backend sở hữu conversation đó. conversationId phải đã tồn tại (tạo qua
     * {@code POST /conversations}) — không còn lazy-create/join membership qua đây nữa.
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
    CONVERSATION_ADDED,
    /**
     * Client -&gt; gateway: xác nhận đã THỰC SỰ hiển thị (không chỉ nhận được qua WS) 1 tin nhắn cho
     * người dùng xem — {@code id} = id của frame MESSAGE đang được xác nhận (giống quy ước
     * correlation id cho ACK/ERROR), {@code conversationId} bắt buộc. Khác {@link #ACK} (server tự
     * gửi ngay khi forward xong, không chứng minh gì về phía người nhận) — đây là tín hiệu THẬT từ
     * phía nhận, dùng để phân biệt "socket đang mở" (xem {@code PresenceRegistry}) với "người dùng
     * đang thực sự chú ý" (vd máy khoá màn hình/tab chạy nền vẫn giữ socket sống nhưng không ai
     * nhìn). Herald dùng tín hiệu này để huỷ push cho candidate đang online (xem
     * {@code NotificationConsumer#onReadAck}) — không có ACK trong 1 khoảng ân hạn dù đang online
     * vẫn coi như chưa xem, vẫn lưu/push notification.
     */
    READ,
    /**
     * Gateway -&gt; client: 1 conversationId mình đang subscribe VỪA BỊ XOÁ HẲN (xem
     * {@code HallApiHandlers#deleteConversation}, {@code RoutingVersionSync#onConversationDeleted})
     * — client tự đóng/loại bỏ card+mục tương ứng khỏi UI, không cần đợi lần load lại danh sách kế tiếp.
     */
    CONVERSATION_DELETED,
    /**
     * Cả 2 chiều: "user X đang gõ trong conversationId Y" — client tự gửi lúc gõ (throttle vài giây
     * 1 lần, không phải mỗi keystroke), server relay lại cho MỌI subscriber khác của conversation
     * đó (xem {@code ChatSessionManager#handleTyping} bên colony). Tín hiệu TẠM THỜI, KHÔNG persist
     * — client tự hết hạn hiển thị "đang gõ" sau vài giây không nhận thêm frame nào.
     */
    TYPING,
    /**
     * Gateway -&gt; client: user {@code fromUserId} vừa đổi trạng thái online/offline (xem
     * {@code HarborSessionManager#broadcastPresenceChange}, {@code body} = {@code {"online": true/false}}).
     * Broadcast cho MỌI session đang kết nối (không lọc theo liên quan — client tự quyết định có
     * quan tâm user đó không). Chỉ báo lúc THAY ĐỔI trạng thái — muốn biết trạng thái hiện tại của
     * 1 user lúc mới mở app thì gọi {@code GET /presence} (herald).
     */
    PRESENCE,
    /**
     * Gateway -&gt; client: user {@code fromUserId} vừa THỰC SỰ xem tin nhắn {@code id} trong
     * conversationId đó (xem {@code ChatSessionManager#handleSeen} bên colony, cùng nguồn gốc với
     * {@link #READ} client tự gửi — harbor forward READ đó thành SEEN xuống colony để fan-out cho
     * MỌI subscriber khác, đặc biệt là người GỬI tin đó dù đang ở pod harbor nào). Client dùng để vẽ
     * dấu "đã xem" (✓✓) trên đúng tin nhắn mình đã gửi có {@code id} khớp.
     */
    SEEN,
    /**
     * Cả 2 chiều: đặt/đổi/huỷ reaction (emoji) trên tin nhắn {@code id} — {@code conversationId} bắt
     * buộc, {@code body} = {@code {"emoji": "👍"}} để đặt/đổi, rỗng/thiếu {@code emoji} để huỷ. Kiểu
     * Facebook: 1 người chỉ có 1 reaction/tin, chọn emoji khác THAY THẾ (không cộng dồn) — xem
     * {@code ChatSessionManager#handleReaction} bên colony (fan-out + persist bảng
     * {@code message_reactions}, sống qua reload).
     */
    REACTION,
    /**
     * Client -&gt; gateway: xoá MỀM tin nhắn {@code id} của CHÍNH MÌNH (server tự kiểm tra
     * {@code fromUserId} thật khớp người gửi gốc qua {@code from_user_id} trong DB, KHÔNG tin
     * client tự khai) — {@code conversationId} bắt buộc. Gateway -&gt; client: fan-out cho MỌI
     * subscriber khác biết {@code id} đó vừa bị xoá để tự thay bằng placeholder "tin nhắn đã bị
     * xoá" (xem {@code ChatSessionManager#handleDelete} bên colony — fan-out + persist cột
     * {@code deleted_at}, sống qua reload, khác {@link #TYPING} thuần tạm thời).
     */
    DELETE,
    /**
     * Cả 2 chiều: ghim/bỏ ghim tin nhắn {@code id} — {@code conversationId} bắt buộc, {@code body} =
     * {@code {"scope": "shared"|"private", "pinned": true|false}}. "shared" (ghim chung): CÓ fan-out
     * cho MỌI subscriber khác, ai cũng ghim/bỏ ghim được — xem {@code ChatSessionManager#handlePin}
     * bên colony (fan-out + persist bảng {@code message_pins_shared}, giống {@link #REACTION}).
     * "private" (ghim riêng): CHỈ persist (bảng {@code message_pins_private}), KHÔNG fan-out — tránh
     * lộ cho thành viên khác biết ai đang ghim riêng gì; đọc lại qua {@code GET /pins}.
     */
    PIN
}
