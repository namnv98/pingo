package com.pingo.colony.ws.session;

import com.pingo.namnv.discovery.grpc.Frame;
import io.vertx.grpc.server.GrpcServerResponse;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;
import lombok.Setter;

/**
 * 1 subscriber logic = đúng 1 gRPC call ({@code Link.Stream}) đang sống từ 1 pod harbor. Thay thế
 * hoàn toàn split {@code ChatLink}/{@code ChatSubscriber} cũ (thời N-shard-link, khi 1 connection
 * vật lý mang nhiều subscriber) — giờ mỗi (harbor session, colony pod) có 1 gRPC stream riêng
 * (HTTP/2 tự multiplex ở tầng dưới), nên 1 call = đúng 1 subscriber.
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
