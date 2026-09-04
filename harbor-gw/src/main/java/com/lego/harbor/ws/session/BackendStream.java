package com.lego.harbor.ws.session;

import com.lego.namnv.discovery.grpc.Frame;
import io.vertx.grpc.client.GrpcClientRequest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

/**
 * 1 gRPC bidi stream (call {@code Link.Stream}) từ gateway xuống đúng 1 pod colony — thay thế hoàn
 * toàn {@code BackendLink} (raw WebSocket, dùng chung theo N-shard) cũ. Giờ đúng 1-1 với 1 cặp
 * (session, pod): HTTP/2 tự multiplex nhiều stream như thế này trên CHUNG 1 connection vật lý tới
 * cùng pod (xem {@code BackendStreamGateway#clients}), nên không còn cần tự chia shard/dùng chung 1
 * stream cho nhiều session nữa — mỗi session có "làn riêng" thật sự.
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
   * {@code request.end()} ném {@code IllegalStateException} nếu chưa từng {@code write()} lần nào
   * (gRPC client-streaming yêu cầu ít nhất 1 message) — gặp thật khi 1 stream bị bỏ (đã timeout ở
   * {@code BackendStreamGateway#doConnect}) rồi mới resolve trễ, chưa kịp ghi gì. Dùng {@code cancel()}
   * cho case đó thay vì {@code end()}.
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
