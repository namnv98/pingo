package com.pingo.harbor.ws.session;

import com.pingo.chat.grpc.Frame;
import io.vertx.grpc.client.GrpcClientRequest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

/**
 * KHÔNG PHẢI session của user — xem {@code package-info.java} của package này để phân biệt với
 * {@link HarborSession}. Đây là 1 gRPC bidi stream (call {@code Link.Stream}) từ gateway xuống
 * đúng 1 pod colony — kết nối NỘI BỘ, user không biết tới sự tồn tại của nó — đúng 1-1 với 1 cặp
 * (HarborSession, pod). HTTP/2 tự multiplex nhiều stream trên chung 1 connection vật lý tới cùng
 * pod (xem {@code BackendStreamGateway#clients}), nên mỗi session có "làn riêng" thật sự.
 */
@Getter
public class BackendStream {

  private final String podName;
  private final HarborSession session;
  private final GrpcClientRequest<Frame, Frame> request;
  private volatile boolean closed;
  private volatile boolean written;
  private volatile long lastActivityAt = System.currentTimeMillis();

  /** SUBSCRIBE đang chờ SUBSCRIBE_OK/SUBSCRIBE_ERROR trên CHÍNH stream này, keyed theo frame id. */
  public record PendingSubscribe(CompletableFuture<Void> future, boolean relayToClient) {}

  private final Map<String, PendingSubscribe> pendingSubscribes = new ConcurrentHashMap<>();
  /** id của các MESSAGE đã gửi trên stream này, chưa có phản hồi (MESSAGE echo/ACK/ERROR). */
  private final Set<String> pendingAckIds = ConcurrentHashMap.newKeySet();

  public BackendStream(String podName, HarborSession session, GrpcClientRequest<Frame, Frame> request) {
    this.podName = podName;
    this.session = session;
    this.request = request;
  }

  public void write(Frame frame) {
    lastActivityAt = System.currentTimeMillis();
    written = true;
    request.write(frame);
  }

  public void touch() {
    lastActivityAt = System.currentTimeMillis();
  }

  /**
   * {@code request.end()} ném {@code IllegalStateException} nếu chưa từng {@code write()} (gRPC
   * client-streaming yêu cầu ít nhất 1 message) — gặp thật khi stream đã timeout ở
   * {@code BackendStreamGateway#doConnect} rồi mới resolve trễ, chưa kịp ghi gì. Dùng {@code cancel()} cho case đó.
   */
  public void end() {
    if (!closed) {
      closed = true;
      if (written) {
        request.end();
      } else {
        request.cancel();
      }
    }
  }

  public boolean isClosed() {
    return closed;
  }
}
