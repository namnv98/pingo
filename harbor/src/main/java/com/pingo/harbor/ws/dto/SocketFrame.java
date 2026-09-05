package com.pingo.harbor.ws.dto;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Envelope (frame) JSON cho chặng client&lt;-&gt;gateway (WebSocket thuần, xem
 * {@code com.pingo.core.socket.LegoSocketServer}) — chặng harbor&lt;-&gt;colony giờ dùng protobuf
 * {@code Frame} (xem {@code discovery/src/main/proto/link.proto}, ARCHITECTURE.md mục 12), không
 * còn đi qua class này nữa. Xem {@link MessageType} để biết các loại frame.
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

  /** Chỉ có ý nghĩa với AUTH: token JWT do colony cấp lúc /register hoặc /login — harbor tự verify chữ ký, không gọi lại colony. */
  private String token;

  /**
   * Id conversation (DM hoặc group) — bắt buộc với MESSAGE/SUBSCRIBE, là routing key thật sự. Luôn
   * phải tường minh (server trả về từ {@code POST /conversations} lúc tạo, xem ARCHITECTURE.md mục
   * 12) — không còn cơ chế suy tất định/lazy-create nào ở tầng này nữa.
   */
  private String conversationId;

  /** Payload tuỳ ý (opaque), chỉ có ý nghĩa với MESSAGE — server không đọc/hiểu nội dung bên trong. */
  private Object body;

  /** Lý do lỗi, dạng người đọc được, chỉ set khi type là ERROR/AUTH_ERROR. */
  private String reason;

  /**
   * Tên pod harbor đang phục vụ session này — chỉ set khi type là AUTH_OK, server tự gán (không
   * bao giờ đọc từ client). Tương đương {@code host_id} trong frame {@code hello} của Slack (xem
   * app.slack.com.har) — hữu ích khi debug (biết tin đang đi qua đúng pod nào), nhất là với hệ
   * thống có nhiều pod harbor như production thật.
   */
  private String serverId;

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
