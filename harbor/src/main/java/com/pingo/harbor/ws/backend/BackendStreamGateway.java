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
import com.pingo.harbor.ws.session.HarborSession;
import io.vertx.core.Vertx;
import io.vertx.core.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Quản lý backend gRPC stream (chặng harbor↔colony) — ĐÚNG CÁCH SLACK LÀM: mỗi pod harbor chỉ mở
 * DUY NHẤT 1 {@link BackendStream} (gRPC call {@code Link.Stream}) tới mỗi pod colony, DÙNG CHUNG
 * cho mọi {@link HarborSession} cần nói chuyện với pod đó — không phải 1 stream/session (bản trước
 * của lớp này làm sai điều này, tưởng nhầm là "gRPC/HTTP2 tự multiplex" đã đủ tương đương, thực ra
 * Slack's Gateway Server không mở 1 kết nối/user mà 1 kết nối/channel-server dùng chung, xem
 * ARCHITECTURE.md mục 12 — bài viết gốc: "GS subscribes to all channel servers... asynchronously").
 *
 * <p>Hệ quả: mọi state trước đây gắn trực tiếp với {@code HarborSession}/{@code BackendStream} 1-1
 * giờ phải tách theo đúng phạm vi của nó — xem javadoc {@link BackendStream}. Một tác dụng phụ CÓ
 * LỢI: 1 message timeout không còn được phép giết cả stream nữa (trước đây {@code failStream} coi
 * timeout của 1 message = bằng chứng cả stream hỏng, chấp nhận được vì blast radius chỉ 1 user —
 * giờ 1 stream phục vụ CẢ POD, làm vậy sẽ ngắt oan mọi user khác đang dùng chung, xem
 * {@link #onMessageAckTimeout}/{@link #failPendingSubscribe}). {@code failStream} giờ chỉ dùng cho
 * tín hiệu tầng TRANSPORT thật sự (connect lỗi, response end/error) — vẫn hợp lý vì đó là bằng
 * chứng cả stream vật lý chết, ảnh hưởng mọi user dùng chung là đúng bản chất.
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

  /** 1 stream DÙNG CHUNG/pod colony, cho TOÀN BỘ pod harbor này (không phải theo session). */
  private final Map<String, BackendStream> sharedStreams = new ConcurrentHashMap<>();
  /** Single-flight: nhiều session cùng cần mở stream tới 1 pod lần đầu chỉ connect đúng 1 lần. */
  private final Map<String, CompletableFuture<BackendStream>> connectingStreams = new ConcurrentHashMap<>();

  /** Client chủ động SUBSCRIBE 1 conversationId — dùng lại stream chung tới đúng pod sở hữu nó. */
  public CompletionStage<Void> subscribe(HarborSession session, String frameId, UUID conversationId, int routingVersion) {
    return resolveRoute(routingVersion, conversationId)
        .thenCompose(routeResp -> subscribeOneConversation(session, frameId, conversationId, routeResp, true));
  }

  /** Gửi 1 MESSAGE của client cho đúng conversation — tự mở/dùng lại stream chung + auto-subscribe nếu chưa subscribe conversation này. */
  public void sendMessage(HarborSession session, SocketFrame frame, UUID conversationId, int routingVersion) {
    var podName = session.getPodFor(conversationId);
    var stream = podName == null ? null : sharedStreams.get(podName);
    if (stream != null && !stream.isClosed()) {
      forwardMessage(stream, session, frame);
      return;
    }
    resolveRoute(routingVersion, conversationId)
        .thenCompose(routeResp -> subscribeOneConversation(session, null, conversationId, routeResp, false))
        .thenAccept(
            unused -> {
              var newPod = session.getPodFor(conversationId);
              var newStream = newPod == null ? null : sharedStreams.get(newPod);
              if (newStream != null) {
                forwardMessage(newStream, session, frame);
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
  public CompletionStage<Void> wakeSubscribe(HarborSession session, UUID conversationId, int routingVersion) {
    return resolveRoute(routingVersion, conversationId)
        .thenCompose(routeResp -> subscribeOneConversation(session, null, conversationId, routeResp, false));
  }

  /**
   * Tự subscribe session vào TOÀN BỘ conversation user đã là thành viên, gọi 1 lần ngay sau AUTH
   * (xem {@code HarborSessionManager#autoSubscribeAllConversations}). {@code resolveRoute} là tra
   * cứu LOCAL (consistent-hash-ring trong bộ nhớ, không phải RPC — xem
   * {@code DefaultPingoConnector#routing}), nên gọi N lần (N = số conversation) không tốn kém; sau
   * khi gom theo pod đích, mỗi pod chỉ gửi ĐÚNG 1 frame {@code SUBSCRIBE_BULK} cho những
   * conversationId CHƯA từng subscribe qua stream chung tới pod đó — conversationId nào đã có sẵn
   * (do 1 session khác trên CÙNG pod harbor này subscribe trước) chỉ cần đăng ký fan-out cục bộ,
   * không tốn round-trip xuống colony nữa. Chạy ngầm hoàn toàn — lỗi 1 pod chỉ log cảnh báo, không
   * ảnh hưởng AUTH_OK đã gửi trước đó hay các pod khác.
   */
  public CompletionStage<Void> autoSubscribeAll(HarborSession session, List<UUID> conversationIds, int routingVersion) {
    if (conversationIds.isEmpty()) {
      return CompletableFuture.completedStage(null);
    }
    var resolved =
        conversationIds.stream()
            .map(
                id ->
                    resolveRoute(routingVersion, id)
                        .thenApply(routeResp -> Map.entry(id, routeResp))
                        .toCompletableFuture())
            .toList();
    return CompletableFuture.allOf(resolved.toArray(CompletableFuture[]::new))
        .handle((unused, ignoredEx) -> resolved) // 1 conversation resolve loi khong duoc lam hong ca lo, loc o duoi
        .thenCompose(
            futures -> {
              var idsByPod = new LinkedHashMap<String, List<UUID>>();
              var routeByPod = new LinkedHashMap<String, RouteResp>();
              for (var f : futures) {
                if (f.isCompletedExceptionally()) {
                  continue;
                }
                var entry = f.join();
                var podName = entry.getValue().getPodName();
                idsByPod.computeIfAbsent(podName, key -> new ArrayList<>()).add(entry.getKey());
                routeByPod.putIfAbsent(podName, entry.getValue());
              }
              var perPod = new ArrayList<CompletableFuture<?>>();
              for (var podEntry : idsByPod.entrySet()) {
                var podName = podEntry.getKey();
                var ids = podEntry.getValue();
                var routeResp = routeByPod.get(podName);
                perPod.add(
                    ensureSharedStream(routeResp)
                        .thenCompose(stream -> subscribeManyOnStream(stream, session, ids))
                        .thenAccept(v -> ids.forEach(id -> session.setPodFor(id, podName)))
                        .exceptionally(
                            ex -> {
                              log.warn(
                                  "auto-subscribe-bulk failed for session {} pod {} ({} conversation(s))",
                                  session.getId(), podName, ids.size(), ex);
                              return null;
                            })
                        .toCompletableFuture());
              }
              return CompletableFuture.allOf(perPod.toArray(CompletableFuture[]::new));
            });
  }

  /**
   * Đăng ký session làm subscriber cục bộ cho MỌI conversationId trong {@code conversationIds}
   * ({@code ids} luôn đã cùng route tới pod của {@code stream}); tách riêng phần ĐÃ subscribe trên
   * backend (chỉ cần đăng ký cục bộ, không tốn round-trip) khỏi phần CHƯA (gom vào 1 frame
   * SUBSCRIBE_BULK duy nhất).
   */
  private CompletionStage<Void> subscribeManyOnStream(BackendStream stream, HarborSession session, List<UUID> ids) {
    var needBackendSubscribe = new ArrayList<UUID>();
    for (var id : ids) {
      registerLocalSubscriber(stream, id, session);
      if (!stream.getBackendSubscribedConversationIds().contains(id)) {
        needBackendSubscribe.add(id);
      }
    }
    if (needBackendSubscribe.isEmpty()) {
      return CompletableFuture.completedStage(null);
    }
    return sendSubscribeBulkAndAwait(stream, session, needBackendSubscribe)
        .thenAccept(unused -> stream.getBackendSubscribedConversationIds().addAll(needBackendSubscribe));
  }

  private CompletionStage<Void> sendSubscribeBulkAndAwait(BackendStream stream, HarborSession session, List<UUID> conversationIds) {
    var frameId = UUIDUtils.timeBasedUuidAsString();
    var handshake = new CompletableFuture<Void>();
    // requester=null: ket qua khong relay cho 1 session cu the nao (relayToClient=false, xem PendingSubscribe),
    // ket qua chi anh huong toi viec danh dau backendSubscribedConversationIds o tren.
    stream.getPendingSubscribes().put(frameId, new BackendStream.PendingSubscribe(null, handshake, false));
    withTimeout(
        handshake, HANDSHAKE_TIMEOUT_MS, "timed out waiting for SUBSCRIBE_BULK_OK from backend",
        () -> failPendingSubscribe(stream, frameId, "timed out waiting for SUBSCRIBE_BULK_OK from backend"));

    // Quen set fromUserId la bug that da gap: colony tu choi voi ERROR "missing/invalid fromUserId",
    // nhung harbor lai khong xu ly ERROR cho 1 pending SUBSCRIBE (chi xu ly cho pending MESSAGE, xem
    // onBackendFrame case ACK, ERROR), nen request cu treo toi khi het han HANDSHAKE_TIMEOUT_MS.
    var frame =
        Frame.newBuilder()
            .setId(frameId)
            .setType(FrameType.SUBSCRIBE_BULK)
            .setFromUserId(session.getUserId().toString())
            .addAllConversationIds(conversationIds.stream().map(UUID::toString).toList())
            .setTs(System.currentTimeMillis())
            .build();
    stream.write(frame);
    return handshake;
  }

  /** Di chuyển 1 conversation đang subscribe sang node colony đúng với routing version mới, nếu pod sở hữu đã đổi. */
  public CompletionStage<Void> reconnectConversationToVersion(HarborSession session, UUID conversationId, int newVersion) {
    return resolveRoute(newVersion, conversationId)
        .thenCompose(
            routeResp -> {
              if (routeResp.getPodName().equals(session.getPodFor(conversationId))) {
                return CompletableFuture.completedStage(null); // pod chủ sở hữu không đổi, không cần làm gì
              }
              return subscribeOneConversation(session, null, conversationId, routeResp, false);
            });
  }

  /**
   * Gỡ session này khỏi MỌI conversation nó đang là subscriber cục bộ — gọi lúc session đóng (xem
   * {@code HarborSessionManager#onClose}). KHÔNG đóng stream chung (còn session khác trên pod này
   * có thể đang dùng) — stream chỉ tự đóng khi transport thật sự chết (xem {@link #failStream}).
   */
  public void closeAllStreams(HarborSession session) {
    for (var stream : sharedStreams.values()) {
      for (var conversationId : session.subscribedConversationIds()) {
        var localSubs = stream.getLocalSubscribersByConversation().get(conversationId);
        if (localSubs != null) {
          localSubs.remove(session);
        }
      }
    }
  }

  private void registerLocalSubscriber(BackendStream stream, UUID conversationId, HarborSession session) {
    stream.getLocalSubscribersByConversation().computeIfAbsent(conversationId, key -> ConcurrentHashMap.newKeySet()).add(session);
  }

  private CompletionStage<Void> subscribeOneConversation(
      HarborSession session, String frameId, UUID conversationId, RouteResp routeResp, boolean relayResultToClient) {
    var resolvedFrameId = frameId != null ? frameId : UUIDUtils.timeBasedUuidAsString();
    return ensureSharedStream(routeResp)
        .thenCompose(stream -> subscribeOneOnStream(stream, session, resolvedFrameId, conversationId, relayResultToClient))
        .thenAccept(unused -> session.setPodFor(conversationId, routeResp.getPodName()));
  }

  /**
   * ConversationId này đã được subscribe trên CHÍNH stream chung này rồi (do 1 session cục bộ khác
   * làm trước) → chỉ cần đăng ký fan-out cục bộ, KHÔNG hỏi lại colony (đúng tinh thần Slack: N user
   * cùng pod cùng channel chỉ tốn 1 lần subscribe thật). Ngược lại mới thật sự gửi SUBSCRIBE xuống
   * backend và chờ handshake. Không còn mang {@code memberUserIds} — membership giờ luôn đã có sẵn
   * trong DB từ trước (tạo qua {@code POST /conversations}), colony chỉ CHECK không còn ghi.
   */
  private CompletionStage<Void> subscribeOneOnStream(
      BackendStream stream, HarborSession session, String frameId, UUID conversationId, boolean relayResultToClient) {
    registerLocalSubscriber(stream, conversationId, session);
    if (stream.getBackendSubscribedConversationIds().contains(conversationId)) {
      if (relayResultToClient) {
        relayToClient.accept(session, SocketFrames.subscribeOk(frameId, conversationId.toString()));
      }
      return CompletableFuture.completedStage(null);
    }
    var handshake = new CompletableFuture<Void>();
    stream.getPendingSubscribes().put(frameId, new BackendStream.PendingSubscribe(session, handshake, relayResultToClient));
    // Timeout ở đây CHỈ fail đúng request nay (khong con giet ca stream chung -- xem javadoc class).
    withTimeout(handshake, HANDSHAKE_TIMEOUT_MS, "timed out waiting for SUBSCRIBE_OK from backend",
        () -> failPendingSubscribe(stream, frameId, "timed out waiting for SUBSCRIBE_OK from backend"));

    var subscribeFrame =
        Frame.newBuilder()
            .setId(frameId)
            .setType(FrameType.SUBSCRIBE)
            .setFromUserId(session.getUserId().toString())
            .setConversationId(conversationId.toString())
            .setTs(System.currentTimeMillis())
            .build();
    stream.write(subscribeFrame);
    return handshake.thenAccept(unused -> stream.getBackendSubscribedConversationIds().add(conversationId));
  }

  /** Dùng lại stream chung đang sống của pod này nếu có, ngược lại mở mới — single-flight qua {@link #connectingStreams}. */
  private CompletionStage<BackendStream> ensureSharedStream(RouteResp routeResp) {
    var podName = routeResp.getPodName();
    var existing = sharedStreams.get(podName);
    if (existing != null && !existing.isClosed()) {
      return CompletableFuture.completedStage(existing);
    }
    return connectingStreams
        .computeIfAbsent(podName, key -> doConnect(routeResp).toCompletableFuture())
        .whenComplete((stream, ex) -> connectingStreams.remove(podName));
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
  private CompletionStage<BackendStream> doConnect(RouteResp routeResp) {
    var podName = routeResp.getPodName();
    var client = grpcClientPool.get(podName);
    var addr = SocketAddress.inetSocketAddress(config.getChatBackend().getPort(), routeResp.getIp());
    var result = new CompletableFuture<BackendStream>();
    withTimeout(result, CONNECT_TIMEOUT_MS, "timed out opening backend stream to pod " + podName, () -> grpcClientPool.evict(podName));

    client
        .request(addr, LinkService.STREAM_CLIENT)
        .map(
            request -> {
              var stream = new BackendStream(podName, request);
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
                var previous = sharedStreams.put(podName, stream);
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
   * {@code timeoutMessage} và chạy {@code onTimeout} — chỉ chạy khi timer THẬT SỰ là bên hoàn
   * thành future (không phải đã xong từ nơi khác trước đó, nhờ {@code completeExceptionally} trả
   * về false trong trường hợp đó). Xong sớm (dù thành công hay lỗi) tự huỷ timer.
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
   * 1 SUBSCRIBE/SUBSCRIBE_BULK cụ thể không có phản hồi kịp thời — CHỈ fail đúng request đó (relay
   * lỗi cho đúng session đã yêu cầu, nếu có), KHÔNG đụng tới các request/local-subscriber khác đang
   * dùng chung stream này. Khác {@link #failStream}: đây là bằng chứng "1 thao tác cụ thể chậm/lỗi",
   * không phải bằng chứng "cả stream vật lý đã chết".
   */
  private void failPendingSubscribe(BackendStream stream, String frameId, String reason) {
    var pending = stream.getPendingSubscribes().remove(frameId);
    if (pending == null) {
      return;
    }
    pending.future().completeExceptionally(new RuntimeException(reason));
  }

  /**
   * Điểm dọn dẹp DUY NHẤT khi cả stream chung bị coi là chết (end/error tự nhiên ở tầng transport,
   * hoặc connect timeout ở {@link #doConnect}) — báo lỗi cho MỌI SUBSCRIBE đang chờ VÀ mọi message
   * đang chờ ACK trên nó, ảnh hưởng tới TẤT CẢ session cục bộ đang dùng chung stream này (đúng bản
   * chất: cả pod harbor mất kết nối tới pod colony đó). Lần SUBSCRIBE/MESSAGE kế tiếp sẽ tự
   * {@link #ensureSharedStream} mở lại. An toàn khi gọi nhiều lần (idempotent).
   */
  private void failStream(BackendStream stream, String reason) {
    sharedStreams.remove(stream.getPodName(), stream);
    grpcClientPool.evict(stream.getPodName());
    for (var pending : stream.getPendingSubscribes().values()) {
      pending.future().completeExceptionally(new RuntimeException(reason));
    }
    stream.getPendingSubscribes().clear();
    for (var entry : Set.copyOf(stream.getPendingSends().entrySet())) {
      if (stream.getPendingSends().remove(entry.getKey(), entry.getValue())) {
        relayToClient.accept(entry.getValue(), SocketFrames.error(entry.getKey(), reason));
      }
    }
    stream.end();
  }

  private void forwardMessage(BackendStream stream, HarborSession session, SocketFrame frame) {
    var outgoing =
        Frame.newBuilder()
            .setId(frame.getId())
            .setType(FrameType.MESSAGE)
            .setFromUserId(session.getUserId().toString())
            .setConversationId(frame.getConversationId())
            .setBodyJson(SocketFrames.encodeBackendBody(frame.getBody()))
            .setTs(System.currentTimeMillis())
            .build();

    stream.getPendingSends().put(frame.getId(), session);
    vertx.setTimer(MESSAGE_ACK_TIMEOUT_MS, tid -> onMessageAckTimeout(stream, frame.getId()));
    stream.write(outgoing);
  }

  /**
   * 1 message cụ thể không có phản hồi kịp thời — CHỈ báo lỗi cho đúng session đã gửi nó, KHÔNG
   * còn giết cả stream chung nữa (khác bản trước 1 stream/session: lúc đó blast radius chỉ 1 user
   * nên "coi cả stream chết" chấp nhận được; giờ 1 stream phục vụ cả pod, làm vậy sẽ ngắt oan mọi
   * user khác không liên quan).
   */
  private void onMessageAckTimeout(BackendStream stream, String frameId) {
    var session = stream.getPendingSends().remove(frameId);
    if (session == null) {
      return; // da co phan hoi (MESSAGE echo/ACK/ERROR) truoc do roi, timer nay het y nghia
    }
    log.warn("khong nhan duoc phan hoi cho message {} tu backend (pod {}) trong {}ms", frameId, stream.getPodName(), MESSAGE_ACK_TIMEOUT_MS);
    relayToClient.accept(session, SocketFrames.error(frameId, "backend unavailable"));
  }

  private CompletionStage<RouteResp> resolveRoute(int version, UUID conversationId) {
    return connector.routing(version, RouteByConversationIdRequest.builder().conversationId(conversationId).build());
  }

  private void onBackendFrame(BackendStream stream, Frame frame) {
    stream.touch();
    switch (frame.getType()) {
      case SUBSCRIBE_OK, SUBSCRIBE_ERROR, SUBSCRIBE_BULK_OK -> {
        var pending = stream.getPendingSubscribes().remove(frame.getId());
        if (pending == null) {
          log.debug("received {} for unmatched/late subscribe id {}", frame.getType(), frame.getId());
          return;
        }
        if (frame.getType() == FrameType.SUBSCRIBE_ERROR) {
          pending.future().completeExceptionally(new RuntimeException(frame.getReason()));
        } else {
          pending.future().complete(null);
          // Chỉ relay case THÀNH CÔNG ở đây — case lỗi để completeExceptionally() chảy lên caller
          // (nơi DUY NHẤT relay lỗi), tránh gửi trùng 2 frame lỗi cho cùng 1 lần thất bại.
          // pending.requester() null (nhanh SUBSCRIBE_BULK nội bộ) hoặc relayToClient=false thì bỏ qua.
          if (pending.relayToClient() && pending.requester() != null) {
            relayToClient.accept(pending.requester(), SocketFrames.fromBackendFrame(frame));
          }
        }
      }
      // ACK/ERROR la phan hoi RIENG cho dung 1 lan gui (khong phai broadcast) -- route qua
      // pendingSends, KHONG fan-out cho ca conversation.
      case ACK, ERROR -> {
        var session = stream.getPendingSends().remove(frame.getId());
        if (session != null) {
          relayToClient.accept(session, SocketFrames.fromBackendFrame(frame));
        } else {
          log.debug("received {} for unmatched/late message id {}", frame.getType(), frame.getId());
        }
      }
      // MESSAGE la broadcast that su cho conversation -- fan-out cho MOI session cuc bo dang subscribe
      // no tren pod nay (bao gom ca chinh nguoi gui, neu ho cung la subscriber -- giu dung hanh vi cu:
      // nguoi gui thay lai tin cua minh qua kenh MESSAGE, tach biet voi ACK rieng).
      case MESSAGE -> {
        var conversationId = UUIDUtils.parseOrDefault(frame.getConversationId());
        var localSubscribers = conversationId == null ? null : stream.getLocalSubscribersByConversation().get(conversationId);
        if (localSubscribers == null || localSubscribers.isEmpty()) {
          log.debug("received MESSAGE for conversation {} with no local subscriber on this pod", frame.getConversationId());
          return;
        }
        var socketFrame = SocketFrames.fromBackendFrame(frame);
        for (var session : Set.copyOf(localSubscribers)) {
          relayToClient.accept(session, socketFrame);
        }
      }
      // Colony chu dong PING khi stream sap idle (chi nhan tin, khong tu sinh hoat dong) -- PONG
      // lai ngay tren chinh stream nay de colony cap nhat lastSeenAt, tranh bi dong oan (xem
      // ChatSessionManager#sweepIdleSessions ben colony).
      case PING -> stream.write(Frame.newBuilder().setId(frame.getId()).setType(FrameType.PONG).setTs(System.currentTimeMillis()).build());
      default -> log.debug("unsupported frame type {} from backend stream (pod {})", frame.getType(), stream.getPodName());
    }
  }
}
