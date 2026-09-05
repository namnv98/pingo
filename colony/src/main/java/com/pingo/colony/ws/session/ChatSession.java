package com.pingo.colony.ws.session;

import com.pingo.chat.grpc.Frame;
import io.vertx.grpc.server.GrpcServerResponse;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

/**
 * 1 gRPC call ({@code Link.Stream}) đang sống — đại diện 1 kết nối DÙNG CHUNG từ 1 pod harbor
 * (không còn 1-1 với 1 user: harbor giờ mở CHUNG 1 stream/pod colony cho mọi user cục bộ của nó,
 * đúng cách Slack làm, xem ARCHITECTURE.md mục 12). KHÔNG có field userId — danh tính người gửi lấy
 * trực tiếp từ {@code from_user_id} của MỖI frame (harbor là bên trusted set field này đúng, xem
 * {@code ChatSessionManager#handleMessage}), vì 1 session giờ có thể mang nhiều user khác nhau.
 */
@Getter
public class ChatSession {

  private final String id;
  private final GrpcServerResponse<Frame, Frame> response;
  @Setter private volatile long lastSeenAt = System.currentTimeMillis();
  private final Set<UUID> conversationIds = ConcurrentHashMap.newKeySet();

  public ChatSession(String id, GrpcServerResponse<Frame, Frame> response) {
    this.id = id;
    this.response = response;
  }

  public void send(Frame frame) {
    response.write(frame);
  }

  public void close() {
    response.end();
  }
}
