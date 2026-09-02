package com.lego.harbor.ws.backend;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.connector.RouteByConversationIdRequest;
import com.lego.namnv.connector.RouteResp;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.support.UUIDUtils;
import com.lego.harbor.ws.dto.MessageType;
import com.lego.harbor.ws.dto.SocketFrame;
import com.lego.harbor.ws.dto.SocketFrames;
import com.lego.harbor.ws.session.BackendLink;
import com.lego.harbor.ws.session.SockjsSocket;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mọi việc liên quan tới backend WebSocket link — link thuần (plain) mà gateway giữ, DÙNG CHUNG
 * cho mọi conversationId hash ra cùng 1 pod colony (không còn 1 link/session như trước): mở/dùng
 * lại link, gửi SUBSCRIBE để đăng ký 1 conversationId trên 1 link, forward MESSAGE của client
 * xuống backend, relay frame từ backend lên lại client, và ping/pong kiểm tra sống của từng link.
 * Dùng bởi {@code com.lego.harbor.ws.SockjsSocketManager} và {@code com.lego.harbor.ws.routing.RoutingVersionSync}.
 */
@Slf4j
@RequiredArgsConstructor
public class BackendLinkGateway {

  private static final long BACKEND_PING_INTERVAL_MS = 25_000;
  private static final long BACKEND_PONG_TIMEOUT_MS = 60_000;
  private static final long HANDSHAKE_TIMEOUT_MS = 5_000;

  private final Vertx vertx;
  private final PingoConnector connector;
  private final LegoConfig1 config;
  /** Đẩy 1 frame lên lại cho client của session — do {@code SockjsSocketManager} cung cấp. */
  private final BiConsumer<SockjsSocket, SocketFrame> relayToClient;

  /**
   * Theo dõi các SUBSCRIBE đang chờ SUBSCRIBE_OK/SUBSCRIBE_ERROR, keyed theo correlation id của
   * chính frame SUBSCRIBE đó (id là UUID nên duy nhất toàn cục, không cần phân biệt thêm theo
   * link). {@code relayToClient} = true khi frame SUBSCRIBE gốc tới từ chính client (client đang
   * chờ SUBSCRIBE_OK/ERROR đúng id đó) — false khi là auto-subscribe nội bộ (lúc gửi MESSAGE lần
   * đầu cho 1 conversation chưa subscribe, hoặc lúc reconnect nền do đổi routing version).
   */
  private record PendingSubscribe(CompletableFuture<Void> future, boolean relayToClient) {}

  private final Map<String, PendingSubscribe> pendingSubscribes = new ConcurrentHashMap<>();

  /** Client chủ động SUBSCRIBE 1 conversationId — mở/dùng lại link xuống đúng pod sở hữu nó. */
  public CompletionStage<Void> subscribe(
      SockjsSocket session, String frameId, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return getRoutingIp(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureLinkAndSubscribe(session, frameId, conversationId, memberUserIds, routeResp, true));
  }

  /** Gửi 1 MESSAGE của client cho đúng conversation — tự mở/dùng lại link + auto-subscribe nếu session chưa subscribe conversation này. */
  public void sendMessage(SockjsSocket session, SocketFrame frame, UUID conversationId, int routingVersion) {
    var podName = session.getPodFor(conversationId);
    var link = podName == null ? null : session.getLinkForPod(podName);
    if (link != null && !link.isClosed()) {
      forwardMessage(session, link, frame);
      return;
    }
    var remembered = session.getRememberedMembers(conversationId);
    var members = remembered.isEmpty() ? extractMembers(session, frame) : remembered;
    getRoutingIp(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureLinkAndSubscribe(session, null, conversationId, members, routeResp, false))
        .thenAccept(
            unused -> {
              var newLink = session.getLinkForPod(session.getPodFor(conversationId));
              if (newLink != null) {
                forwardMessage(session, newLink, frame);
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
   * {@code RoutingVersionSync#onMembershipChanged}). {@code memberUserIds} nên là FULL danh sách
   * hiện tại (không chỉ phần mới) để session này cũng "nhớ" đủ cho lần reconnect sau (xem
   * {@code SockjsSocket#rememberMembers}).
   */
  public CompletionStage<Void> wakeSubscribe(SockjsSocket session, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return getRoutingIp(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureLinkAndSubscribe(session, null, conversationId, memberUserIds, routeResp, false));
  }

  /** Di chuyển 1 conversation đang subscribe sang node colony đúng với routing version mới, nếu pod sở hữu đã đổi. */
  public CompletionStage<Void> reconnectConversationToVersion(SockjsSocket session, UUID conversationId, int newVersion) {
    return getRoutingIp(newVersion, conversationId)
        .thenCompose(
            routeResp -> {
              if (routeResp.getPodName().equals(session.getPodFor(conversationId))) {
                return CompletableFuture.completedStage(null); // pod chủ sở hữu không đổi, không cần làm gì
              }
              // Gửi lại memberUserIds đã nhớ (không phải List.of()): pod MỚI (nếu pod cũ vừa
              // restart/scale) hoàn toàn không có membership trong ChannelMembershipRegistry của
              // riêng nó (in-memory theo pod) — không gửi lại sẽ bị SUBSCRIBE_ERROR "not a member".
              return ensureLinkAndSubscribe(session, null, conversationId, session.getRememberedMembers(conversationId), routeResp, false);
            });
  }

  /** Kiểm tra liveness của mọi backend link đang mở: đóng nếu quá hạn PONG, hoặc gửi PING nếu tới hạn. */
  public void pingIfDue(SockjsSocket session, long now) {
    for (var link : session.allLinks()) {
      if (now - link.getLastPongAt() > BACKEND_PONG_TIMEOUT_MS) {
        log.warn("backend link to pod {} for session {} timed out, dropping it", link.getPodName(), session.getId());
        session.closeLink(link.getPodName());
      } else if (now - link.getLastPongAt() >= BACKEND_PING_INTERVAL_MS) {
        link.getSocket().write(SocketFrames.ping(UUIDUtils.timeBasedUuidAsString()).encode());
      }
    }
  }

  /** DM: suy ra 2 thành viên từ chính frame (fromUserId của session + toUserId). Group: rỗng — membership phải được thiết lập từ trước qua SUBSCRIBE tường minh. */
  private List<UUID> extractMembers(SockjsSocket session, SocketFrame frame) {
    var toUserId = UUIDUtils.parseOrDefault(frame.getToUserId());
    return toUserId == null ? List.of() : List.of(session.getUserId(), toUserId);
  }

  private void forwardMessage(SockjsSocket session, BackendLink link, SocketFrame frame) {
    var outgoing = frame.toBuilder().fromUserId(session.getUserId().toString()).build();
    link.getSocket()
        .write(outgoing.encode())
        .toCompletionStage()
        .exceptionally(
            ex -> {
              log.warn("failed to forward message {} to backend for session {}", frame.getId(), session.getId(), ex);
              relayToClient.accept(session, SocketFrames.error(frame.getId(), "backend unavailable"));
              return null;
            });
  }

  private CompletionStage<RouteResp> getRoutingIp(int version, UUID conversationId) {
    return connector.routing(version, RouteByConversationIdRequest.builder().conversationId(conversationId).build());
  }

  /**
   * Cốt lõi: dùng lại link cùng pod nếu đã có (chỉ gửi thêm SUBSCRIBE trên đó), hoặc mở link mới +
   * đợi SUBSCRIBE_OK mới chính thức gắn vào session — giữ nguyên an toàn "không đóng link cũ tới
   * khi link mới được xác nhận xong" (xem {@code SockjsSocket#putLink}), giờ áp dụng theo pod thay
   * vì theo session (1 session có thể giữ nhiều link, mỗi link phục vụ N conversation cùng pod).
   */
  private CompletionStage<Void> ensureLinkAndSubscribe(
      SockjsSocket session, String frameId, UUID conversationId, List<UUID> memberUserIds, RouteResp routeResp, boolean relayResultToClient) {
    session.rememberMembers(conversationId, memberUserIds);
    var resolvedFrameId = frameId != null ? frameId : UUIDUtils.timeBasedUuidAsString();
    var existing = session.getLinkForPod(routeResp.getPodName());
    if (existing != null && !existing.isClosed()) {
      return sendSubscribeAndAwait(session, existing, resolvedFrameId, conversationId, memberUserIds, relayResultToClient)
          .thenAccept(unused -> session.setPodFor(conversationId, routeResp.getPodName()));
    }
    var chatBackend = config.getChatBackend();
    return vertx
        .createHttpClient()
        .webSocket(chatBackend.getPort(), routeResp.getIp(), chatBackend.getPath())
        .toCompletionStage()
        .thenCompose(
            socket -> {
              var link = new BackendLink(routeResp.getPodName(), routeResp.getIp(), socket, routeResp.getVersion());
              socket.handler(buffer -> onBackendFrame(session, link, buffer));
              return sendSubscribeAndAwait(session, link, resolvedFrameId, conversationId, memberUserIds, relayResultToClient)
                  .whenComplete(
                      (v, ex) -> {
                        if (ex == null) {
                          session.putLink(routeResp.getPodName(), link);
                          session.setPodFor(conversationId, routeResp.getPodName());
                        } else if (!link.isClosed()) {
                          link.close(); // handshake thất bại — dọn link mới, KHÔNG đụng link cũ (nếu có) của session tới pod khác
                        }
                      });
            });
  }

  private CompletionStage<Void> sendSubscribeAndAwait(
      SockjsSocket session, BackendLink link, String frameId, UUID conversationId, List<UUID> memberUserIds, boolean relayResultToClient) {
    var handshake = new CompletableFuture<Void>();
    pendingSubscribes.put(frameId, new PendingSubscribe(handshake, relayResultToClient));

    var timeoutTimerId =
        vertx.setTimer(
            HANDSHAKE_TIMEOUT_MS,
            tid -> {
              var pending = pendingSubscribes.remove(frameId);
              if (pending != null) {
                pending.future().completeExceptionally(new RuntimeException("timed out waiting for SUBSCRIBE_OK from backend"));
              }
            });
    handshake.whenComplete((v, ex) -> vertx.cancelTimer(timeoutTimerId));

    var subscribeFrame =
        SocketFrame.builder()
            .type(MessageType.SUBSCRIBE)
            .id(frameId)
            .fromUserId(session.getUserId().toString())
            .conversationId(conversationId.toString())
            .memberUserIds(memberUserIds.isEmpty() ? null : memberUserIds.stream().map(UUID::toString).toList())
            .ts(System.currentTimeMillis())
            .build();
    link.getSocket().write(subscribeFrame.encode());

    return handshake;
  }

  private void onBackendFrame(SockjsSocket session, BackendLink link, Buffer buffer) {
    var frameOpt = SocketFrame.decode(buffer);
    if (frameOpt.isEmpty()) {
      log.debug("dropping undecodable frame from backend for session {}", session.getId());
      return;
    }
    var frame = frameOpt.get();
    if (frame.getType() == MessageType.PONG) {
      link.setLastPongAt(System.currentTimeMillis());
      return;
    }
    if (frame.getType() == MessageType.PING) {
      link.getSocket().write(SocketFrames.pong(frame.getId()).encode());
      return;
    }
    if (frame.getType() == MessageType.SUBSCRIBE_OK || frame.getType() == MessageType.SUBSCRIBE_ERROR) {
      var pending = pendingSubscribes.remove(frame.getId());
      if (pending == null) {
        log.debug("received {} for unmatched/late subscribe id {}", frame.getType(), frame.getId());
        return;
      }
      if (frame.getType() == MessageType.SUBSCRIBE_OK) {
        pending.future().complete(null);
        // Chỉ relay ở đây cho case THÀNH CÔNG — case lỗi để completeExceptionally() chảy lên tận
        // caller (subscribe()/sendMessage()), nơi DUY NHẤT chịu trách nhiệm relay lỗi (kèm đúng lý
        // do thật từ colony) — tránh gửi trùng 2 frame lỗi lên client cho cùng 1 lần thất bại.
        if (pending.relayToClient()) {
          relayToClient.accept(session, frame);
        }
      } else {
        pending.future().completeExceptionally(new RuntimeException(frame.getReason()));
      }
      return;
    }
    // MESSAGE / ACK / ERROR được relay lên client gần như nguyên vẹn (verbatim), không sửa đổi gì thêm.
    relayToClient.accept(session, frame);
  }
}
