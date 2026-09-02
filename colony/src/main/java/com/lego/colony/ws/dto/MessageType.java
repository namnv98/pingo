package com.lego.colony.ws.dto;

/** Các loại frame trao đổi trên chat socket (cả 2 chặng client&lt;-&gt;gateway và gateway&lt;-&gt;chat-backend). */
public enum MessageType {
  /**
   * Khong con duoc harbor gui xuong colony nua (AUTH gio xu ly cuc bo tai harbor) -- giu lai enum
   * value nay chi de tranh xao tron thu tu/gia tri, khong co code nao con xu ly case nay.
   */
  AUTH,
  /** Server -&gt; client/gateway: AUTH thành công. */
  AUTH_OK,
  /** Server -&gt; client/gateway: AUTH thất bại (ví dụ userId không hợp lệ, không kết nối được backend). */
  AUTH_ERROR,
  /**
   * Gateway -&gt; server: đăng ký (subscribe) 1 link cho đúng 1 {@code conversationId} cụ thể — thay
   * thế vai trò cũ của AUTH trên chặng gateway&lt;-&gt;backend. Có thể mang kèm {@code memberUserIds}
   * để lazy-create/join membership (xem SocketFrame, ChannelMembershipRegistry).
   */
  SUBSCRIBE,
  /** Server -&gt; caller: SUBSCRIBE thành công, đã là subscriber hợp lệ của conversation đó. */
  SUBSCRIBE_OK,
  /** Server -&gt; caller: SUBSCRIBE thất bại (không phải member, thiếu conversationId, node đang drain...). */
  SUBSCRIBE_ERROR,
  /** Cả 2 chiều: một tin nhắn chat, được định tuyến (route) tới đúng pod sở hữu {@code conversationId}. */
  MESSAGE,
  /**
   * Server -&gt; sender: xác nhận đã nhận và forward MESSAGE thành công.
   * Đây chỉ là ACK ở mức transport (server đã chuyển tiếp), KHÔNG đảm bảo người nhận đã thực sự đọc được.
   */
  ACK,
  /** Server -&gt; sender/client: request không xử lý được, xem thêm field {@code reason} trong SocketFrame. */
  ERROR,
  /** Cả 2 chiều: probe kiểm tra liveness (còn sống hay không) của connection, dùng cho heartbeat. */
  PING,
  /** Cả 2 chiều: phản hồi của PING. */
  PONG
}
