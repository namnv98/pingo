package com.lego.harbor.ws.dto;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Envelope (frame) JSON cho chặng client&lt;-&gt;gateway (SockJS/WebSocket) — chặng harbor&lt;-&gt;colony
 * giờ dùng protobuf {@code Frame} (xem {@code discovery/src/main/proto/link.proto},
 * ARCHITECTURE.md mục 12), không còn đi qua class này nữa. Xem {@link MessageType} để biết các loại frame.
 */
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SocketFrame {

  /** Correlation id: client tự sinh cho AUTH/MESSAGE, server echo (trả) lại nguyên id đó trong ACK/ERROR để client đối chiếu. */
  private String id;

  private MessageType type;

  /** User id của người gửi. Do server tự gán khi xử lý MESSAGE — không bao giờ tin giá trị client tự gửi lên (tránh giả mạo). */
  private String fromUserId;

  /**
   * Chỉ có ý nghĩa với AUTH: token JWT do colony cấp lúc /register hoặc /login — harbor verify chữ
   * ký tại chỗ, không đụng tới colony (xem SockjsSocketManager#handleAuth). Đây là DTO client<->
   * harbor duy nhất (chặng harbor<->colony giờ dùng protobuf {@code Frame}, xem
   * ARCHITECTURE.md mục 12), nên không còn rủi ro lệch schema giữa 2 module như trước nữa.
   */
  private String token;

  /**
   * User id của người nhận — chỉ còn mang tính tiện lợi cho client (hiển thị/tương thích cũ) với
   * MESSAGE dạng DM, KHÔNG còn dùng để route nữa (xem {@link #conversationId}). Không bắt buộc nếu
   * đã có {@code conversationId}.
   */
  private String toUserId;

  /**
   * Id của conversation (DM hoặc group) mà frame này thuộc về — bắt buộc với MESSAGE và SUBSCRIBE
   * (là routing key thật sự, thay cho {@code toUserId} trước đây). Với DM, harbor tự suy ra bằng
   * {@code ConversationIds.dmId(fromUserId, toUserId)} nếu client không tự gửi field này lên.
   */
  private String conversationId;

  /**
   * Chỉ có ý nghĩa với SUBSCRIBE: danh sách userId cần union vào membership của conversation này —
   * cơ chế lazy-create/join, vì hệ thống chưa có flow "tạo group" thật (xem ARCHITECTURE.md mục 8).
   */
  private List<String> memberUserIds;

  /** Payload tuỳ ý (opaque), chỉ có ý nghĩa với MESSAGE — server không đọc/hiểu nội dung bên trong. */
  private Object body;

  /** Lý do lỗi, dạng người đọc được, chỉ set khi type là ERROR/AUTH_ERROR. */
  private String reason;

  /** Timestamp (epoch millis) do server đóng dấu, không lấy từ client. */
  private Long ts;

  public Buffer encode() {
    return JsonObject.mapFrom(this).toBuffer();
  }

  /** Parse Buffer thành SocketFrame; trả về Optional.empty() nếu JSON không hợp lệ hoặc thiếu field type. */
  public static Optional<SocketFrame> decode(Buffer buffer) {
    try {
      var frame = buffer.toJsonObject().mapTo(SocketFrame.class);
      return frame.getType() == null ? Optional.empty() : Optional.of(frame);
    } catch (DecodeException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
