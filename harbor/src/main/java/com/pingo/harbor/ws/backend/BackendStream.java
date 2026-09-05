package com.pingo.harbor.ws.backend;

import com.pingo.chat.grpc.Frame;
import com.pingo.harbor.ws.session.HarborSession;
import io.vertx.grpc.client.GrpcClientRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

/**
 * 1 gRPC bidi stream (call {@code Link.Stream}) tới 1 pod colony — DÙNG CHUNG cho MỌI
 * {@link HarborSession} trên pod harbor này cần nói chuyện với đúng pod colony đó, không còn 1-1
 * với 1 session như trước (xem ARCHITECTURE.md mục 12, viết lại theo đúng cách Slack làm: "GS
 * subscribes to all channel servers... asynchronously" — GS chỉ subscribe/mở kết nối MỘT LẦN mỗi
 * channel server, dù bên trong có bao nhiêu user, rồi tự fan-out cục bộ, xem
 * {@code BackendStreamGateway}).
 *
 * <p>Vì nhiều {@code HarborSession} dùng chung 1 stream này, mọi state trước đây gắn với "session"
 * giờ phải tự track theo từng {@code conversationId}/{@code frameId} cụ thể:
 * <ul>
 *   <li>{@link #backendSubscribedConversationIds}: conversationId nào đã SUBSCRIBE thành công trên
 *   CHÍNH stream này rồi — session cục bộ thứ 2 trở đi join cùng conversationId này không cần hỏi
 *   lại colony nữa.</li>
 *   <li>{@link #localSubscribersByConversation}: để biết 1 MESSAGE đến (broadcast) cần fan-out cho
 *   những session cục bộ nào.</li>
 *   <li>{@link #pendingSends}: để biết 1 ACK/ERROR (phản hồi RIÊNG cho 1 lần gửi) phải trả về đúng
 *   session nào đã gửi, không phải broadcast.</li>
 * </ul>
 */
@Getter
public class BackendStream {

  private final String podName;
  private final GrpcClientRequest<Frame, Frame> request;
  private volatile boolean closed;
  private volatile boolean written;
  private volatile long lastActivityAt = System.currentTimeMillis();

  private final Set<UUID> backendSubscribedConversationIds = ConcurrentHashMap.newKeySet();
  private final Map<UUID, Set<HarborSession>> localSubscribersByConversation = new ConcurrentHashMap<>();

  /** SUBSCRIBE/SUBSCRIBE_BULK đang chờ phản hồi trên CHÍNH stream này, keyed theo frame id — {@code requester} là session cục bộ đã khởi tạo request đó (để relay đúng người). */
  public record PendingSubscribe(HarborSession requester, CompletableFuture<Void> future, boolean relayToClient) {}

  private final Map<String, PendingSubscribe> pendingSubscribes = new ConcurrentHashMap<>();
  /** frame id của MESSAGE đang gửi trên stream này → session cục bộ đã gửi nó (để route đúng ACK/ERROR). */
  private final Map<String, HarborSession> pendingSends = new ConcurrentHashMap<>();

  public BackendStream(String podName, GrpcClientRequest<Frame, Frame> request) {
    this.podName = podName;
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
