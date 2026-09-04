package com.lego.harbor.ws.backend;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.connector.RouteByConversationIdRequest;
import com.lego.namnv.connector.RouteResp;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.namnv.discovery.grpc.Frame;
import com.lego.namnv.discovery.grpc.FrameType;
import com.lego.namnv.discovery.grpc.LinkGrpc;
import com.lego.harbor.ws.dto.MessageType;
import com.lego.harbor.ws.dto.SocketFrame;
import com.lego.harbor.ws.dto.SocketFrames;
import com.lego.harbor.ws.session.BackendStream;
import com.lego.harbor.ws.session.SockjsSocket;
import io.vertx.core.Vertx;
import io.vertx.core.json.Json;
import io.vertx.core.net.SocketAddress;
import io.vertx.grpc.client.GrpcClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mọi việc liên quan tới backend gRPC stream (chặng harbor↔colony) — thay thế hoàn toàn thiết kế
 * "N-shard-link" (raw WebSocket, dùng chung theo hash session) trước đó: gRPC/HTTP2 tự multiplex
 * nhiều stream trên chung 1 connection vật lý, nên mỗi (session, pod) giờ có 1
 * {@link BackendStream} riêng thật sự — {@link #clients} (1 {@code GrpcClient}/pod, dùng CHUNG cho
 * mọi session) là nơi duy nhất tính chất "chia sẻ connection vật lý" còn tồn tại, và nó nằm ở tầng
 * transport (HTTP/2), không phải logic tự viết như trước. Xem ARCHITECTURE.md mục 12. Dùng bởi
 * {@code com.lego.harbor.ws.SockjsSocketManager} và {@code com.lego.harbor.ws.routing.RoutingVersionSync}.
 */
@Slf4j
@RequiredArgsConstructor
public class BackendLinkGateway {

  private static final long HANDSHAKE_TIMEOUT_MS = 5_000;
  private static final long MESSAGE_ACK_TIMEOUT_MS = 5_000;
  private static final long CONNECT_TIMEOUT_MS = 5_000;

  private final Vertx vertx;
  private final PingoConnector connector;
  private final LegoConfig1 config;
  private final BiConsumer<SockjsSocket, SocketFrame> relayToClient;

  /** 1 {@code GrpcClient} (= 1 pool HTTP/2 connection) dùng CHUNG cho mọi session tới cùng 1 pod colony. */
  private final Map<String, GrpcClient> clients = new ConcurrentHashMap<>();

  /** Client chủ động SUBSCRIBE 1 conversationId — mở/dùng lại stream xuống đúng pod sở hữu nó. */
  public CompletionStage<Void> subscribe(
      SockjsSocket session, String frameId, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return getRoutingIp(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureStreamAndSubscribe(session, frameId, conversationId, memberUserIds, routeResp, true));
  }

  /** Gửi 1 MESSAGE của client cho đúng conversation — tự mở/dùng lại stream + auto-subscribe nếu session chưa subscribe conversation này. */
  public void sendMessage(SockjsSocket session, SocketFrame frame, UUID conversationId, int routingVersion) {
    var podName = session.getPodFor(conversationId);
    var stream = podName == null ? null : session.getBackendStreams().get(podName);
    if (stream != null && !stream.isClosed()) {
      forwardMessage(stream, frame);
      return;
    }
    var remembered = session.getRememberedMembers(conversationId);
    var members = remembered.isEmpty() ? extractMembers(session, frame) : remembered;
    getRoutingIp(routingVersion, conversationId)
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
  public CompletionStage<Void> wakeSubscribe(SockjsSocket session, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return getRoutingIp(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureStreamAndSubscribe(session, null, conversationId, memberUserIds, routeResp, false));
  }

  /** Di chuyển 1 conversation đang subscribe sang node colony đúng với routing version mới, nếu pod sở hữu đã đổi. */
  public CompletionStage<Void> reconnectConversationToVersion(SockjsSocket session, UUID conversationId, int newVersion) {
    return getRoutingIp(newVersion, conversationId)
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
   * {@code SockjsSocketManager#onClose}). Khác thiết kế cũ (frame {@code SESSION_CLOSED} riêng):
   * colony giờ thấy stream tự kết thúc (gRPC {@code end()}), không cần báo bằng frame nữa, vì
   * stream không còn dùng chung với session khác.
   */
  public void closeAllStreams(SockjsSocket session) {
    for (var stream : session.getBackendStreams().values()) {
      stream.end();
    }
    session.getBackendStreams().clear();
  }

  /** DM: suy ra 2 thành viên từ chính frame (fromUserId của session + toUserId). Group: rỗng — membership phải được thiết lập từ trước qua SUBSCRIBE tường minh. */
  private List<UUID> extractMembers(SockjsSocket session, SocketFrame frame) {
    var toUserId = UUIDUtils.parseOrDefault(frame.getToUserId());
    return toUserId == null ? List.of() : List.of(session.getUserId(), toUserId);
  }

  private CompletionStage<Void> ensureStreamAndSubscribe(
      SockjsSocket session, String frameId, UUID conversationId, List<UUID> memberUserIds, RouteResp routeResp, boolean relayResultToClient) {
    session.rememberMembers(conversationId, memberUserIds);
    var resolvedFrameId = frameId != null ? frameId : UUIDUtils.timeBasedUuidAsString();
    return ensureStream(session, routeResp)
        .thenCompose(stream -> sendSubscribeAndAwait(stream, resolvedFrameId, conversationId, memberUserIds, relayResultToClient))
        .thenAccept(unused -> session.setPodFor(conversationId, routeResp.getPodName()));
  }

  /** Dùng lại stream đang sống của (session, pod) nếu có, ngược lại mở mới — single-flight qua {@link SockjsSocket#getConnectingStreams()}. */
  private CompletionStage<BackendStream> ensureStream(SockjsSocket session, RouteResp routeResp) {
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
   * QUAN TRỌNG: KHÔNG được đợi {@code request.response()} xong rồi mới coi stream "sẵn sàng" để
   * ghi — colony (server) chỉ thật sự gửi response headers SAU KHI nhận được frame đầu tiên từ
   * client (bidi streaming, không có gì để trả lời trước khi có request), nên nếu gate ở đây trên
   * response(), 2 phía deadlock chờ nhau (đã tự gặp bug này khi test thật: subscribe treo vô hạn,
   * không log lỗi gì cả vì cả 2 bên đều đang "chờ" hợp lệ, không phải lỗi). Resolve ngay khi có
   * {@code request} (đã ghi được), gắn response handler song song không chặn.
   *
   * <p>{@code CONNECT_TIMEOUT_MS} bọc quanh chính {@code client.request(...)} — đã tự bắt được bug
   * thật: {@code HttpClientOptions.DEFAULT_HTTP2_MAX_POOL_SIZE=1} (đúng 1 connection HTTP/2 vật lý
   * dùng chung cho mọi stream tới 1 pod, HTTP/2 tự multiplex ở tầng này) cộng
   * {@code DEFAULT_MAX_WAIT_QUEUE_SIZE=-1} (hàng chờ không giới hạn, không bao giờ tự reject) — nếu
   * connection duy nhất đó bị kẹt giữa chừng (VD 1 lần connect trước đó dở dang, hoặc TCP handshake
   * treo do sự cố mạng thoáng qua trong k3s), MỌI request kế tiếp xếp hàng chờ VÔ THỜI HẠN, không có
   * exception nào cả — {@link #failStream}/evict ở nhánh response() không bao giờ chạy tới vì
   * chính {@code request()} không bao giờ resolve. Bắt được qua {@code /tmp/diag1.mjs}: SUBSCRIBE
   * treo đủ 15s, nhưng {@code HANDSHAKE_TIMEOUT_MS} (áp dụng SAU khi có request) không hề log —
   * chứng tỏ treo ở TRƯỚC giai đoạn đó. Có timeout ở đây thì mọi lần treo kiểu này đều tự phục hồi
   * (evict client hỏng, lần sau mở connection mới) thay vì chờ mãi.
   */
  private CompletionStage<BackendStream> doConnect(SockjsSocket session, RouteResp routeResp) {
    var podName = routeResp.getPodName();
    var client = clients.computeIfAbsent(podName, key -> newClient());
    var addr = SocketAddress.inetSocketAddress(config.getChatBackend().getPort(), routeResp.getIp());
    var result = new CompletableFuture<BackendStream>();

    var timeoutTimerId =
        vertx.setTimer(
            CONNECT_TIMEOUT_MS,
            tid -> {
              if (result.completeExceptionally(new RuntimeException("timed out opening backend stream to pod " + podName))) {
                evictClient(podName);
              }
            });

    client
        .request(addr, LinkGrpc.getStreamMethod())
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
              vertx.cancelTimer(timeoutTimerId);
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
              vertx.cancelTimer(timeoutTimerId);
              evictClient(podName);
              result.completeExceptionally(ex);
            });

    return result;
  }

  /**
   * KHÔNG set {@code setHttp2KeepAliveTimeout} ở đây — đã tự bắt được bug thật khi test: đặt giá
   * trị này (kể cả 30s như dự định ban đầu) làm {@code request.response()} phía client trễ ĐÚNG
   * BẰNG số giây đó trước khi resolve, dù server (colony) đã xử lý và ghi phản hồi gần như ngay lập
   * tức — timeline đo được: colony nhận+xử lý SUBSCRIBE chỉ 148ms sau khi harbor ghi, nhưng
   * {@code response()} phía harbor không resolve cho tới ~29s sau (khớp gần đúng
   * {@code KEEP_ALIVE_TIMEOUT_SEC=30} cũ). Nghi là quirk/bug của chính vertx-grpc-client 4.5.5 khi
   * kết hợp option này với luồng bidi-streaming — không phải lỗi logic của pingo.
   *
   * <p>CŨNG KHÔNG set {@code setIdleTimeout} — thử rồi, tệ hơn: option này đóng CẢ connection (không
   * phân biệt được stream nào trên đó còn "sống") sau N giây không có traffic tầng TCP/HTTP2, mà 1
   * session chat im lặng (không ai gõ gì) lâu hơn N giây là chuyện hoàn toàn bình thường — đo được
   * thật: 2 SUBSCRIBE thành công (~100ms round-trip), rồi đúng 20000ms sau (khớp
   * {@code setIdleTimeout(20)} khi thử) cả 2 phía đồng thời log "Connection was closed", giết luôn
   * session đang sống bình thường chứ không phải zombie. Vì 1 connection dùng CHUNG cho NHIỀU
   * session (multiplex), giết nhầm kiểu này ảnh hưởng dây chuyền tới mọi session khác đang share
   * connection đó — tệ hơn hẳn so với không làm gì cả.
   *
   * <p>Dùng option mặc định hoàn toàn (không keepalive, không idle-timeout tầng transport). Để đối
   * phó với khả năng 1 pooled connection chết ngầm (VD k3s CNI/conntrack rớt 1 kết nối rảnh) mà
   * client không biết: khi 1 stream thật sự chứng minh là hỏng ({@link #failStream}, hoặc
   * SUBSCRIBE timeout ở {@link #sendSubscribeAndAwait}), chủ động evict luôn {@link GrpcClient} của
   * pod đó khỏi {@link #clients} — lần {@code ensureStream} kế tiếp sẽ mở 1 connection MỚI thay vì
   * tiếp tục tin tưởng connection cũ. Cách này chỉ phản ứng khi có bằng chứng thật (1 request cụ thể
   * fail/timeout), không đoán mò dựa trên thời gian rảnh như idle-timeout.
   */
  private GrpcClient newClient() {
    return GrpcClient.client(vertx);
  }

  /** Bỏ {@code GrpcClient} đang cache cho pod này — chỉ gọi khi đã có bằng chứng cụ thể connection hỏng. */
  private void evictClient(String podName) {
    clients.remove(podName);
  }

  /** Stream chết (end/error) — báo lỗi cho mọi SUBSCRIBE/MESSAGE đang chờ trên nó, gỡ khỏi session, evict connection nghi hỏng. */
  private void failStream(BackendStream stream, String reason) {
    stream.getSession().getBackendStreams().remove(stream.getPodName(), stream);
    evictClient(stream.getPodName());
    for (var pending : stream.getPendingSubscribes().values()) {
      pending.future().completeExceptionally(new RuntimeException(reason));
    }
    stream.getPendingSubscribes().clear();
    for (var frameId : Set.copyOf(stream.getPendingAckIds())) {
      if (stream.getPendingAckIds().remove(frameId)) {
        relayToClient.accept(stream.getSession(), SocketFrames.error(frameId, reason));
      }
    }
  }

  private void forwardMessage(BackendStream stream, SocketFrame frame) {
    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.MESSAGE)
            .setFromUserId(stream.getSession().getUserId().toString())
            .setConversationId(frame.getConversationId())
            .setBodyJson(encodeBody(frame.getBody()))
            .setTs(System.currentTimeMillis())
            .build();

    stream.getPendingAckIds().add(frame.getId());
    vertx.setTimer(MESSAGE_ACK_TIMEOUT_MS, tid -> onMessageAckTimeout(stream, frame.getId()));
    stream.write(outgoing);
  }

  private void onMessageAckTimeout(BackendStream stream, String frameId) {
    if (!stream.getPendingAckIds().remove(frameId)) {
      return; // da co phan hoi (MESSAGE echo/ACK/ERROR) truoc do roi, timer nay het y nghia
    }
    log.warn(
        "khong nhan duoc phan hoi cho message {} tu backend (pod {}) trong {}ms, coi stream la chet, tu don + bao loi cho client",
        frameId, stream.getPodName(), MESSAGE_ACK_TIMEOUT_MS);
    stream.getSession().getBackendStreams().remove(stream.getPodName(), stream);
    stream.end();
    relayToClient.accept(stream.getSession(), SocketFrames.error(frameId, "backend unavailable"));
  }

  private CompletionStage<RouteResp> getRoutingIp(int version, UUID conversationId) {
    return connector.routing(version, RouteByConversationIdRequest.builder().conversationId(conversationId).build());
  }

  private CompletionStage<Void> sendSubscribeAndAwait(
      BackendStream stream, String frameId, UUID conversationId, List<UUID> memberUserIds, boolean relayResultToClient) {
    var handshake = new CompletableFuture<Void>();
    stream.getPendingSubscribes().put(frameId, new BackendStream.PendingSubscribe(handshake, relayResultToClient));

    var timeoutTimerId =
        vertx.setTimer(
            HANDSHAKE_TIMEOUT_MS,
            tid -> {
              var pending = stream.getPendingSubscribes().remove(frameId);
              if (pending != null) {
                // SUBSCRIBE khong duoc ack trong HANDSHAKE_TIMEOUT_MS la bang chung cu the stream/connection
                // nay co van de (khong phai doan mo theo thoi gian ranh nhu idle-timeout) -- coi stream nay
                // chet, don + evict connection de lan sau mo moi thay vi tiep tuc dung 1 connection nghi hong.
                stream.getSession().getBackendStreams().remove(stream.getPodName(), stream);
                evictClient(stream.getPodName());
                pending.future().completeExceptionally(new RuntimeException("timed out waiting for SUBSCRIBE_OK from backend"));
              }
            });
    handshake.whenComplete((v, ex) -> vertx.cancelTimer(timeoutTimerId));

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
          // Chỉ relay ở đây cho case THÀNH CÔNG — case lỗi để completeExceptionally() chảy lên tận
          // caller (subscribe()/sendMessage()), nơi DUY NHẤT chịu trách nhiệm relay lỗi (kèm đúng lý
          // do thật từ colony) — tránh gửi trùng 2 frame lỗi lên client cho cùng 1 lần thất bại.
          if (pending.relayToClient()) {
            relayToClient.accept(stream.getSession(), toClientFrame(frame));
          }
        } else {
          pending.future().completeExceptionally(new RuntimeException(frame.getReason()));
        }
      }
      case MESSAGE, ACK, ERROR -> {
        // trước tiên huỷ pending-ack-tracking (nếu id này đang chờ, xem forwardMessage) để timer
        // MESSAGE_ACK_TIMEOUT_MS không bắn ERROR trùng lên 1 message vừa thật sự có hồi đáp.
        stream.getPendingAckIds().remove(frame.getId());
        relayToClient.accept(stream.getSession(), toClientFrame(frame));
      }
      default -> log.debug("unsupported frame type {} from backend stream (pod {})", frame.getType(), stream.getPodName());
    }
  }

  private static SocketFrame toClientFrame(Frame frame) {
    return SocketFrame.builder()
        .id(frame.getId())
        .type(mapType(frame.getType()))
        .fromUserId(emptyToNull(frame.getFromUserId()))
        .conversationId(emptyToNull(frame.getConversationId()))
        .body(frame.getBodyJson().isEmpty() ? null : Json.decodeValue(frame.getBodyJson()))
        .reason(emptyToNull(frame.getReason()))
        .ts(frame.getTs())
        .build();
  }

  private static MessageType mapType(FrameType type) {
    return switch (type) {
      case SUBSCRIBE_OK -> MessageType.SUBSCRIBE_OK;
      case SUBSCRIBE_ERROR -> MessageType.SUBSCRIBE_ERROR;
      case MESSAGE -> MessageType.MESSAGE;
      case ACK -> MessageType.ACK;
      case ERROR -> MessageType.ERROR;
      default -> MessageType.ERROR;
    };
  }

  private static String encodeBody(Object body) {
    return body == null ? "" : Json.encode(body);
  }

  private static String emptyToNull(String s) {
    return s == null || s.isEmpty() ? null : s;
  }
}
