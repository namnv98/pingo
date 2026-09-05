package com.pingo.harbor.ws.backend;

import com.pingo.connector.PingoConnector;
import com.pingo.connector.RouteByConversationIdRequest;
import com.pingo.connector.RouteResp;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.core.grpc.client.GrpcClientPool;
import com.pingo.chat.grpc.Frame;
import com.pingo.chat.grpc.FrameType;
import com.pingo.chat.grpc.LinkService;
import com.pingo.harbor.ws.dto.SocketFrame;
import com.pingo.harbor.ws.dto.SocketFrames;
import com.pingo.harbor.ws.session.BackendStream;
import com.pingo.harbor.ws.session.HarborSession;
import io.vertx.core.Vertx;
import io.vertx.core.net.SocketAddress;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quản lý backend gRPC stream (chặng harbor↔colony) — thay thế thiết kế "N-shard-link" cũ (raw
 * WebSocket dùng chung theo hash session): gRPC/HTTP2 tự multiplex nhiều stream trên 1 connection
 * vật lý, nên mỗi (session, pod) có 1 {@link BackendStream} riêng. {@link #grpcClientPool} (1
 * {@code GrpcClient}/pod, dùng chung cho mọi session, xem {@code core/grpc}) là nơi duy nhất còn
 * "chia sẻ connection vật lý" — ở tầng transport, không phải logic tự viết. Xem ARCHITECTURE.md
 * mục 12.
 */
@Slf4j
@RequiredArgsConstructor
public class BackendStreamGateway {

  private static final long HANDSHAKE_TIMEOUT_MS = 5_000;
  private static final long MESSAGE_ACK_TIMEOUT_MS = 5_000;
  private static final long CONNECT_TIMEOUT_MS = 5_000;

  private final Vertx vertx;
  private final PingoConnector connector;
  private final LegoConfig1 config;
  private final BiConsumer<HarborSession, SocketFrame> relayToClient;
  private final GrpcClientPool grpcClientPool;

  /** Client chủ động SUBSCRIBE 1 conversationId — mở/dùng lại stream xuống đúng pod sở hữu nó. */
  public CompletionStage<Void> subscribe(
      HarborSession session, String frameId, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return resolveRoute(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureStreamAndSubscribe(session, frameId, conversationId, memberUserIds, routeResp, true));
  }

  /** Gửi 1 MESSAGE của client cho đúng conversation — tự mở/dùng lại stream + auto-subscribe nếu session chưa subscribe conversation này. */
  public void sendMessage(HarborSession session, SocketFrame frame, UUID conversationId, int routingVersion) {
    var podName = session.getPodFor(conversationId);
    var stream = podName == null ? null : session.getBackendStreams().get(podName);
    if (stream != null && !stream.isClosed()) {
      forwardMessage(stream, frame);
      return;
    }
    var remembered = session.getRememberedMembers(conversationId);
    var members = remembered.isEmpty() ? extractMembers(session, frame) : remembered;
    resolveRoute(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureStreamAndSubscribe(session, null, conversationId, members, routeResp, false))
        .thenAccept(
            unused -> {
              var newPod = session.getPodFor(conversationId);
              var newStream = newPod == null ? null : session.getBackendStreams().get(newPod);
              if (newStream != null) {
                forwardMessage(newStream, frame);
              }
            })
        .exceptionally(
            ex -> {
              log.warn("failed to auto-subscribe conversation {} for session {} before sending message {}", conversationId, session.getId(), frame.getId(), ex);
              relayToClient.accept(session, SocketFrames.error(frame.getId(), "backend unavailable"));
              return null;
            });
  }

  /**
   * "Đánh thức" 1 session vừa được thêm làm member của {@code conversationId} — subscribe ngầm,
   * KHÔNG relay SUBSCRIBE_OK lên client (caller tự gửi 1 frame CONVERSATION_ADDED riêng, xem
   * {@code RoutingVersionSync#onMembershipChanged}).
   */
  public CompletionStage<Void> wakeSubscribe(HarborSession session, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return resolveRoute(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureStreamAndSubscribe(session, null, conversationId, memberUserIds, routeResp, false));
  }

  /** Di chuyển 1 conversation đang subscribe sang node colony đúng với routing version mới, nếu pod sở hữu đã đổi. */
  public CompletionStage<Void> reconnectConversationToVersion(HarborSession session, UUID conversationId, int newVersion) {
    return resolveRoute(newVersion, conversationId)
        .thenCompose(
            routeResp -> {
              if (routeResp.getPodName().equals(session.getPodFor(conversationId))) {
                return CompletableFuture.completedStage(null); // pod chủ sở hữu không đổi, không cần làm gì
              }
              return ensureStreamAndSubscribe(session, null, conversationId, session.getRememberedMembers(conversationId), routeResp, false);
            });
  }

  /**
   * Đóng mọi stream backend của session này — gọi lúc session đóng (xem
   * {@code HarborSessionManager#onClose}). Khác thiết kế cũ (frame {@code SESSION_CLOSED} riêng):
   * colony giờ thấy stream tự kết thúc (gRPC {@code end()}), không cần báo bằng frame nữa, vì
   * stream không còn dùng chung với session khác.
   */
  public void closeAllStreams(HarborSession session) {
    for (var stream : session.getBackendStreams().values()) {
      stream.end();
    }
    session.getBackendStreams().clear();
  }

  /** DM: suy ra 2 thành viên từ chính frame (fromUserId của session + toUserId). Group: rỗng — membership phải được thiết lập từ trước qua SUBSCRIBE tường minh. */
  private List<UUID> extractMembers(HarborSession session, SocketFrame frame) {
    var toUserId = UUIDUtils.parseOrDefault(frame.getToUserId());
    return toUserId == null ? List.of() : List.of(session.getUserId(), toUserId);
  }

  private CompletionStage<Void> ensureStreamAndSubscribe(
      HarborSession session, String frameId, UUID conversationId, List<UUID> memberUserIds, RouteResp routeResp, boolean relayResultToClient) {
    session.rememberMembers(conversationId, memberUserIds);
    var resolvedFrameId = frameId != null ? frameId : UUIDUtils.timeBasedUuidAsString();
    return ensureStream(session, routeResp)
        .thenCompose(stream -> sendSubscribeAndAwait(stream, resolvedFrameId, conversationId, memberUserIds, relayResultToClient))
        .thenAccept(unused -> session.setPodFor(conversationId, routeResp.getPodName()));
  }

  /** Dùng lại stream đang sống của (session, pod) nếu có, ngược lại mở mới — single-flight qua {@link HarborSession#getConnectingStreams()}. */
  private CompletionStage<BackendStream> ensureStream(HarborSession session, RouteResp routeResp) {
    var podName = routeResp.getPodName();
    var existing = session.getBackendStreams().get(podName);
    if (existing != null && !existing.isClosed()) {
      return CompletableFuture.completedStage(existing);
    }
    var connecting = session.getConnectingStreams();
    return connecting
        .computeIfAbsent(podName, key -> doConnect(session, routeResp).toCompletableFuture())
        .whenComplete((stream, ex) -> connecting.remove(podName));
  }

  /**
   * QUAN TRỌNG: không đợi {@code request.response()} rồi mới coi stream sẵn sàng ghi — colony chỉ
   * gửi response SAU KHI nhận frame đầu (bidi streaming), nên gate ở response() khiến 2 bên
   * deadlock (đã gặp thật: subscribe treo vô hạn, không log lỗi vì cả 2 bên đều đang "chờ" hợp lệ).
   * Resolve ngay khi có {@code request}, gắn response handler song song không chặn.
   *
   * <p>{@code CONNECT_TIMEOUT_MS} bọc quanh chính {@code client.request(...)} — Vert.x mặc định 1
   * connection HTTP/2 vật lý/pod với hàng chờ không giới hạn (không tự reject); nếu connection đó
   * kẹt (VD TCP handshake treo do sự cố mạng thoáng qua trong k3s), mọi request kế tiếp xếp hàng
   * vô thời hạn, không exception nào cả — {@link #failStream} ở nhánh response() không chạy tới vì
   * chính {@code request()} chưa từng resolve (đã gặp thật: SUBSCRIBE treo đủ 15s mà
   * {@code HANDSHAKE_TIMEOUT_MS} không hề log, vì nó chỉ áp dụng SAU khi có request). Timeout ở
   * đây giúp mọi lần treo kiểu này tự phục hồi: evict client hỏng, lần sau mở connection mới.
   */
  private CompletionStage<BackendStream> doConnect(HarborSession session, RouteResp routeResp) {
    var podName = routeResp.getPodName();
    var client = grpcClientPool.get(podName);
    var addr = SocketAddress.inetSocketAddress(config.getChatBackend().getPort(), routeResp.getIp());
    var result = new CompletableFuture<BackendStream>();
    withTimeout(result, CONNECT_TIMEOUT_MS, "timed out opening backend stream to pod " + podName, () -> grpcClientPool.evict(podName));

    client
        .request(addr, LinkService.STREAM_CLIENT)
        .map(
            request -> {
              var stream = new BackendStream(podName, session, request);
              request
                  .response()
                  .onSuccess(
                      response -> {
                        response.handler(frame -> onBackendFrame(stream, frame));
                        response.endHandler(v -> failStream(stream, "backend stream closed"));
                        response.exceptionHandler(ex -> failStream(stream, "backend stream error: " + ex.getMessage()));
                      })
                  .onFailure(ex -> failStream(stream, "backend stream response failed: " + ex.getMessage()));
              return stream;
            })
        .onSuccess(
            stream -> {
              if (result.complete(stream)) {
                var previous = session.getBackendStreams().put(podName, stream);
                if (previous != null && previous != stream && !previous.isClosed()) {
                  previous.end();
                }
              } else {
                // Da timeout truoc do roi (result da completeExceptionally) -- request() tra ve tre,
                // stream nay khong con ai cho nua, dong luon thay vi de lo ri.
                stream.end();
              }
            })
        .onFailure(
            ex -> {
              grpcClientPool.evict(podName);
              result.completeExceptionally(ex);
            });

    return result;
  }

  /**
   * Chờ {@code future} có thời hạn: hết {@code delayMs} mà chưa xong thì tự fail bằng
   * {@code timeoutMessage} và chạy {@code onTimeout} (evict client, fail cả stream...) — chỉ chạy
   * khi timer THẬT SỰ là bên hoàn thành future (không phải đã xong từ nơi khác trước đó, nhờ
   * {@code completeExceptionally} trả về false trong trường hợp đó). Xong sớm (dù thành công hay
   * lỗi) tự huỷ timer. Gộp lại pattern "gửi gì đó, chờ phản hồi có hạn, hết hạn coi là bằng chứng cụ
   * thể hỏng" đang lặp lại ở {@link #doConnect}/{@link #sendSubscribeAndAwait}.
   */
  private <T> void withTimeout(CompletableFuture<T> future, long delayMs, String timeoutMessage, Runnable onTimeout) {
    var timerId =
        vertx.setTimer(
            delayMs,
            tid -> {
              if (future.completeExceptionally(new RuntimeException(timeoutMessage))) {
                onTimeout.run();
              }
            });
    future.whenComplete((v, ex) -> vertx.cancelTimer(timerId));
  }

  /**
   * Điểm dọn dẹp DUY NHẤT khi 1 stream bị coi là chết (end/error tự nhiên, hoặc timeout ở
   * {@link #sendSubscribeAndAwait}/{@link #onMessageAckTimeout}) — báo lỗi cho MỌI SUBSCRIBE/MESSAGE
   * đang chờ trên nó (1 stream có thể gánh nhiều conversationId cùng route tới 1 pod), gỡ khỏi
   * session, evict connection nghi hỏng, đóng hẳn stream. An toàn khi gọi nhiều lần (idempotent).
   */
  private void failStream(BackendStream stream, String reason) {
    stream.getSession().getBackendStreams().remove(stream.getPodName(), stream);
    grpcClientPool.evict(stream.getPodName());
    for (var pending : stream.getPendingSubscribes().values()) {
      pending.future().completeExceptionally(new RuntimeException(reason));
    }
    stream.getPendingSubscribes().clear();
    for (var frameId : Set.copyOf(stream.getPendingAckIds())) {
      if (stream.getPendingAckIds().remove(frameId)) {
        relayToClient.accept(stream.getSession(), SocketFrames.error(frameId, reason));
      }
    }
    stream.end();
  }

  private void forwardMessage(BackendStream stream, SocketFrame frame) {
    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.MESSAGE)
            .setFromUserId(stream.getSession().getUserId().toString())
            .setConversationId(frame.getConversationId())
            .setBodyJson(SocketFrames.encodeBackendBody(frame.getBody()))
            .setTs(System.currentTimeMillis())
            .build();

    stream.getPendingAckIds().add(frame.getId());
    vertx.setTimer(MESSAGE_ACK_TIMEOUT_MS, tid -> onMessageAckTimeout(stream, frame.getId()));
    stream.write(outgoing);
  }

  private void onMessageAckTimeout(BackendStream stream, String frameId) {
    if (!stream.getPendingAckIds().contains(frameId)) {
      return; // da co phan hoi (MESSAGE echo/ACK/ERROR) truoc do roi, timer nay het y nghia
    }
    log.warn(
        "khong nhan duoc phan hoi cho message {} tu backend (pod {}) trong {}ms, coi CA stream la chet, tu don + bao loi cho client",
        frameId, stream.getPodName(), MESSAGE_ACK_TIMEOUT_MS);
    // Bang chung manh y het SUBSCRIBE timeout -- fail ca stream, khong chi rieng message nay.
    failStream(stream, "backend unavailable");
  }

  private CompletionStage<RouteResp> resolveRoute(int version, UUID conversationId) {
    return connector.routing(version, RouteByConversationIdRequest.builder().conversationId(conversationId).build());
  }

  private CompletionStage<Void> sendSubscribeAndAwait(
      BackendStream stream, String frameId, UUID conversationId, List<UUID> memberUserIds, boolean relayResultToClient) {
    var handshake = new CompletableFuture<Void>();
    stream.getPendingSubscribes().put(frameId, new BackendStream.PendingSubscribe(handshake, relayResultToClient));
    // Khong ack trong HANDSHAKE_TIMEOUT_MS la bang chung cu the stream hong -- fail ca stream (xem
    // failStream), khong chi rieng frameId nay.
    withTimeout(handshake, HANDSHAKE_TIMEOUT_MS, "timed out waiting for SUBSCRIBE_OK from backend", () -> failStream(stream, "timed out waiting for SUBSCRIBE_OK from backend"));

    var subscribeFrame =
        Frame.newBuilder()
            .setId(frameId)
            .setType(FrameType.SUBSCRIBE)
            .setFromUserId(stream.getSession().getUserId().toString())
            .setConversationId(conversationId.toString())
            .addAllMemberUserIds(memberUserIds.stream().map(UUID::toString).toList())
            .setTs(System.currentTimeMillis())
            .build();
    stream.write(subscribeFrame);

    return handshake;
  }

  private void onBackendFrame(BackendStream stream, Frame frame) {
    stream.touch();
    switch (frame.getType()) {
      case SUBSCRIBE_OK, SUBSCRIBE_ERROR -> {
        var pending = stream.getPendingSubscribes().remove(frame.getId());
        if (pending == null) {
          log.debug("received {} for unmatched/late subscribe id {}", frame.getType(), frame.getId());
          return;
        }
        if (frame.getType() == FrameType.SUBSCRIBE_OK) {
          pending.future().complete(null);
          // Chỉ relay case THÀNH CÔNG ở đây — case lỗi để completeExceptionally() chảy lên caller
          // (nơi DUY NHẤT relay lỗi), tránh gửi trùng 2 frame lỗi cho cùng 1 lần thất bại.
          if (pending.relayToClient()) {
            relayToClient.accept(stream.getSession(), SocketFrames.fromBackendFrame(frame));
          }
        } else {
          pending.future().completeExceptionally(new RuntimeException(frame.getReason()));
        }
      }
      case MESSAGE, ACK, ERROR -> {
        // Huỷ pending-ack-tracking trước để MESSAGE_ACK_TIMEOUT_MS không bắn ERROR trùng lên message vừa có hồi đáp.
        stream.getPendingAckIds().remove(frame.getId());
        relayToClient.accept(stream.getSession(), SocketFrames.fromBackendFrame(frame));
      }
      default -> log.debug("unsupported frame type {} from backend stream (pod {})", frame.getType(), stream.getPodName());
    }
  }
}
