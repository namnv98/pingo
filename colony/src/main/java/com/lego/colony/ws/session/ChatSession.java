package com.lego.colony.ws.session;

import com.lego.namnv.discovery.grpc.Frame;
import io.vertx.grpc.server.GrpcServerResponse;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

/**
 * 1 subscriber logic = đúng 1 gRPC call ({@code Link.Stream}) đang sống từ 1 pod harbor cụ thể.
 * Thay thế hoàn toàn split {@code ChatLink}/{@code ChatSubscriber} cũ (thời N-shard-link, khi 1
 * connection vật lý còn mang nhiều subscriber): từ khi mỗi (harbor session, colony pod) có 1 gRPC
 * stream RIÊNG (HTTP/2 tự multiplex ở tầng dưới, không còn dùng chung ở tầng ứng dụng nữa — xem
 * ARCHITECTURE.md mục 12), 1 call = đúng 1 subscriber, không cần tách 2 lớp.
 */
@Getter
public class ChatSession {

  private final String id;
  private final GrpcServerResponse<Frame, Frame> response;
  @Setter private volatile long lastSeenAt = System.currentTimeMillis();
  @Setter private UUID userId;
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
