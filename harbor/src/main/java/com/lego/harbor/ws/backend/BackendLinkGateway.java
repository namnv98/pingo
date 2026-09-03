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
import java.util.HashSet;
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
 * cho MỌI client session trên node harbor này (không phải 1 link/session như trước): với mỗi pod
 * colony, giữ {@code chatBackendShardsPerPod} link song song (shard), chọn shard theo hash ổn định
 * của {@code session.getId()} (xem {@link #shardFor}) — 1 session luôn rơi vào đúng 1 shard trong
 * suốt vòng đời của nó (giữ nguyên thứ tự frame của riêng session đó), chỉ có traffic của các
 * session KHÁC mới có thể "đụng" nhau khi cùng rơi vào 1 shard (~1/N khả năng). Việc mở/dùng lại
 * link, gửi SUBSCRIBE, forward MESSAGE, relay frame từ backend lên lại đúng client (qua
 * {@code harborSessionId} trong frame — không còn suy ra được từ chính socket nữa vì socket giờ
 * dùng chung), và ping/pong kiểm tra sống của từng shard link đều nằm ở đây. Dùng bởi
 * {@code com.lego.harbor.ws.SockjsSocketManager} và {@code com.lego.harbor.ws.routing.RoutingVersionSync}.
 */
@Slf4j
@RequiredArgsConstructor
public class BackendLinkGateway {

  private static final long BACKEND_PING_INTERVAL_MS = 25_000;
  private static final long BACKEND_PONG_TIMEOUT_MS = 60_000;
  private static final long HANDSHAKE_TIMEOUT_MS = 5_000;
  private static final long MESSAGE_ACK_TIMEOUT_MS = 5_000;

  private final Vertx vertx;
  private final PingoConnector connector;
  private final LegoConfig1 config;
  /**
   * Cùng 1 map sống (live) mà {@code SockjsSocketManager} đang giữ — cần để {@link #onBackendFrame}
   * tra ra đúng session cục bộ cần relay tới, dựa trên {@code harborSessionId} của frame trả về từ
   * colony (không còn closure sẵn 1 session cố định như khi link còn thuộc riêng 1 session).
   */
  private final Map<String, SockjsSocket> sessions;
  /** Đẩy 1 frame lên lại cho client của session — do {@code SockjsSocketManager} cung cấp. */
  private final BiConsumer<SockjsSocket, SocketFrame> relayToClient;

  /** Link song song (shard) đang sống, keyed theo (pod, shardIndex) — dùng chung cho mọi session. */
  private final Map<PodShardKey, BackendLink> links = new ConcurrentHashMap<>();
  /** Single-flight: gom nhiều session cùng cần 1 shard key lần đầu vào đúng 1 lần connect. */
  private final Map<PodShardKey, CompletableFuture<BackendLink>> connecting = new ConcurrentHashMap<>();

  /**
   * Theo dõi các SUBSCRIBE đang chờ SUBSCRIBE_OK/SUBSCRIBE_ERROR, keyed theo correlation id của
   * chính frame SUBSCRIBE đó (id là UUID nên duy nhất toàn cục, không cần phân biệt thêm theo
   * link). {@code relayToClient} = true khi frame SUBSCRIBE gốc tới từ chính client (client đang
   * chờ SUBSCRIBE_OK/ERROR đúng id đó) — false khi là auto-subscribe nội bộ (lúc gửi MESSAGE lần
   * đầu cho 1 conversation chưa subscribe, hoặc lúc reconnect nền do đổi routing version).
   */
  private record PendingSubscribe(CompletableFuture<Void> future, boolean relayToClient) {}

  private final Map<String, PendingSubscribe> pendingSubscribes = new ConcurrentHashMap<>();

  /**
   * Theo dõi các MESSAGE đang chờ phản hồi (ACK/ERROR/MESSAGE echo) từ colony, keyed theo id của
   * chính frame đó — bù cho khoảng hở duy nhất còn "im lặng thật" đã tìm thấy qua resilience test
   * thật: {@code forwardMessage} (khác {@link #sendSubscribeAndAwait}) trước đây KHÔNG track gì cả
   * — nếu 1 shard link cache vẫn báo {@code isClosed()==false} nhưng backend thật đã chết, write()
   * xuống local thành công (buffer OS/Netty nhận), colony không bao giờ trả lời, và client không
   * nhận được gì — không MESSAGE, không ACK, không ERROR — cho tới khi {@link #pingSharedLinksIfDue}
   * phát hiện quá hạn PONG (60s). Giờ nếu quá {@link #MESSAGE_ACK_TIMEOUT_MS} không có phản hồi,
   * chủ động coi link đó là chết: đóng + gỡ khỏi {@link #links} ngay (không đợi đủ 60s, giảm blast
   * radius cho các session khác đang share shard đó), đồng thời báo ERROR rõ ràng cho client — biến
   * "im lặng thật" thành "lỗi rõ ràng", không còn trường hợp nào client chờ mãi không có hồi đáp.
   */
  private record PendingAck(BackendLink link, SockjsSocket session) {}

  private final Map<String, PendingAck> pendingAcks = new ConcurrentHashMap<>();

  /**
   * Shard ổn định (deterministic) cho 1 session, trong khoảng {@code [0, chatBackendShardsPerPod)}
   * — cùng 1 session luôn ra cùng 1 shard, nên mọi conversation của nó trên cùng 1 pod vẫn tiếp
   * tục dùng chung đúng 1 link vật lý như trước (giữ nguyên thứ tự), chỉ khác là link đó giờ có
   * thể dùng chung với ~1/N session khác thay vì độc chiếm.
   */
  int shardFor(String sessionId) {
    var shardCount = Math.max(1, config.getChatBackendShardsPerPod());
    return Math.floorMod(sessionId.hashCode(), shardCount);
  }

  /** Client chủ động SUBSCRIBE 1 conversationId — mở/dùng lại link xuống đúng pod sở hữu nó. */
  public CompletionStage<Void> subscribe(
      SockjsSocket session, String frameId, UUID conversationId, List<UUID> memberUserIds, int routingVersion) {
    return getRoutingIp(routingVersion, conversationId)
        .thenCompose(routeResp -> ensureLinkAndSubscribe(session, frameId, conversationId, memberUserIds, routeResp, true));
  }

  /** Gửi 1 MESSAGE của client cho đúng conversation — tự mở/dùng lại link + auto-subscribe nếu session chưa subscribe conversation này. */
  public void sendMessage(SockjsSocket session, SocketFrame frame, UUID conversationId, int routingVersion) {
    var podName = session.getPodFor(conversationId);
    var link = podName == null ? null : links.get(new PodShardKey(podName, shardFor(session.getId())));
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
              var newPod = session.getPodFor(conversationId);
              var newLink = newPod == null ? null : links.get(new PodShardKey(newPod, shardFor(session.getId())));
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

  /**
   * Kiểm tra liveness của MỌI shard link đang mở (dùng chung cho mọi session) — đóng nếu quá hạn
   * PONG, hoặc gửi PING nếu tới hạn. Quét đúng 1 lần/chu kỳ (do {@code SockjsSocketManager} gọi),
   * KHÔNG còn theo từng session như trước (1 link giờ có thể có hàng trăm session đang dùng).
   */
  public void pingSharedLinksIfDue(long now) {
    for (var entry : links.entrySet()) {
      var link = entry.getValue();
      if (now - link.getLastPongAt() > BACKEND_PONG_TIMEOUT_MS) {
        log.warn("backend link to pod {} (shard {}) timed out, dropping it", link.getPodName(), entry.getKey().shardIndex());
        links.remove(entry.getKey(), link);
        link.close();
      } else if (now - link.getLastPongAt() >= BACKEND_PING_INTERVAL_MS) {
        link.getSocket().write(SocketFrames.ping(UUIDUtils.timeBasedUuidAsString()).encode());
      }
    }
  }

  /**
   * Best-effort báo cho colony biết 1 session vừa đóng, trên MỌI shard link mà session đó đang
   * dùng — colony sẽ gỡ đúng subscriber tương ứng khỏi registry của nó (xem
   * {@code ChatSessionManager}), vì link vật lý giờ dùng chung nên TCP close của session này
   * không còn tự động kéo theo việc đóng link nữa (xem {@code SockjsSocketManager#onClose}).
   */
  public void notifySessionClosed(SockjsSocket session) {
    var pods = new HashSet<String>();
    for (var conversationId : session.subscribedConversationIds()) {
      var pod = session.getPodFor(conversationId);
      if (pod != null) {
        pods.add(pod);
      }
    }
    if (pods.isEmpty()) {
      return;
    }
    var encoded =
        SocketFrame.builder()
            .type(MessageType.SESSION_CLOSED)
            .id(UUIDUtils.timeBasedUuidAsString())
            .harborSessionId(session.getId())
            .ts(System.currentTimeMillis())
            .build()
            .encode();
    for (var pod : pods) {
      var link = links.get(new PodShardKey(pod, shardFor(session.getId())));
      if (link != null && !link.isClosed()) {
        link.getSocket()
            .write(encoded)
            .onFailure(ex -> log.debug("failed to notify pod {} that session {} closed", pod, session.getId(), ex));
      }
    }
  }

  /** DM: suy ra 2 thành viên từ chính frame (fromUserId của session + toUserId). Group: rỗng — membership phải được thiết lập từ trước qua SUBSCRIBE tường minh. */
  private List<UUID> extractMembers(SockjsSocket session, SocketFrame frame) {
    var toUserId = UUIDUtils.parseOrDefault(frame.getToUserId());
    return toUserId == null ? List.of() : List.of(session.getUserId(), toUserId);
  }

  private void forwardMessage(SockjsSocket session, BackendLink link, SocketFrame frame) {
    var outgoing = frame.toBuilder().fromUserId(session.getUserId().toString()).harborSessionId(session.getId()).build();

    pendingAcks.put(frame.getId(), new PendingAck(link, session));
    vertx.setTimer(MESSAGE_ACK_TIMEOUT_MS, tid -> onMessageAckTimeout(frame.getId()));

    link.getSocket()
        .write(outgoing.encode())
        .toCompletionStage()
        .exceptionally(
            ex -> {
              if (pendingAcks.remove(frame.getId()) != null) {
                log.warn("failed to forward message {} to backend for session {}", frame.getId(), session.getId(), ex);
                relayToClient.accept(session, SocketFrames.error(frame.getId(), "backend unavailable"));
              }
              return null;
            });
  }

  private void onMessageAckTimeout(String frameId) {
    var pending = pendingAcks.remove(frameId);
    if (pending == null) {
      return; // da co phan hoi (ACK/ERROR/MESSAGE echo) truoc do roi, timer nay het y nghia
    }
    var shardKey = new PodShardKey(pending.link().getPodName(), shardFor(pending.session().getId()));
    log.warn(
        "khong nhan duoc phan hoi cho message {} tu backend trong {}ms, coi link (pod {}, shard {}) la chet, tu don + bao loi cho client",
        frameId, MESSAGE_ACK_TIMEOUT_MS, shardKey.podName(), shardKey.shardIndex());
    if (links.remove(shardKey, pending.link()) && !pending.link().isClosed()) {
      pending.link().close();
    }
    relayToClient.accept(pending.session(), SocketFrames.error(frameId, "backend unavailable"));
  }

  private CompletionStage<RouteResp> getRoutingIp(int version, UUID conversationId) {
    return connector.routing(version, RouteByConversationIdRequest.builder().conversationId(conversationId).build());
  }

  /**
   * Cốt lõi: dùng lại shard link (pod, shard) nếu đã sống, hoặc mở mới rồi gửi SUBSCRIBE cho
   * đúng conversationId trên đó. Khác thiết kế cũ: link được commit vào registry dùng chung
   * NGAY KHI kết nối WebSocket xong (không đợi SUBSCRIBE_OK) — vì giờ link là tài nguyên dùng
   * chung, 1 lần SUBSCRIBE thất bại (vd "not a member") của riêng conversation này KHÔNG được
   * phép đóng/huỷ link, session/conversation khác vẫn có thể đang dùng tốt. Chỉ khi bản thân việc
   * connect() thất bại thì mới không có link nào để commit.
   */
  private CompletionStage<Void> ensureLinkAndSubscribe(
      SockjsSocket session, String frameId, UUID conversationId, List<UUID> memberUserIds, RouteResp routeResp, boolean relayResultToClient) {
    session.rememberMembers(conversationId, memberUserIds);
    var resolvedFrameId = frameId != null ? frameId : UUIDUtils.timeBasedUuidAsString();
    var shardKey = new PodShardKey(routeResp.getPodName(), shardFor(session.getId()));
    return connectShard(shardKey, routeResp)
        .thenCompose(link -> sendSubscribeAndAwait(session, link, resolvedFrameId, conversationId, memberUserIds, relayResultToClient))
        .thenAccept(unused -> session.setPodFor(conversationId, routeResp.getPodName()));
  }

  /** Dùng lại shard link đang sống nếu có, ngược lại connect mới — single-flight qua {@link #connecting}. */
  private CompletionStage<BackendLink> connectShard(PodShardKey shardKey, RouteResp routeResp) {
    var existing = links.get(shardKey);
    if (existing != null && !existing.isClosed()) {
      return CompletableFuture.completedStage(existing);
    }
    return connecting.computeIfAbsent(shardKey, key -> doConnect(shardKey, routeResp)).whenComplete((link, ex) -> connecting.remove(shardKey));
  }

  private CompletableFuture<BackendLink> doConnect(PodShardKey shardKey, RouteResp routeResp) {
    var chatBackend = config.getChatBackend();
    return vertx
        .createHttpClient()
        .webSocket(chatBackend.getPort(), routeResp.getIp(), chatBackend.getPath())
        .toCompletionStage()
        .thenApply(
            socket -> {
              var link = new BackendLink(routeResp.getPodName(), routeResp.getIp(), socket, routeResp.getVersion());
              socket.handler(buffer -> onBackendFrame(link, buffer));
              socket.closeHandler(any -> links.remove(shardKey, link));
              var previous = links.put(shardKey, link);
              if (previous != null && previous != link && !previous.isClosed()) {
                previous.close();
              }
              return link;
            })
        .toCompletableFuture();
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
            .harborSessionId(session.getId())
            .ts(System.currentTimeMillis())
            .build();
    link.getSocket().write(subscribeFrame.encode());

    return handshake;
  }

  /**
   * Không còn closure sẵn 1 {@code session} cố định (link giờ dùng chung) — mọi frame cần relay
   * lên client phải tự tra {@code frame.getHarborSessionId()} trong {@link #sessions} để biết đúng
   * đích cục bộ, xem {@link #relayToSession}.
   */
  private void onBackendFrame(BackendLink link, Buffer buffer) {
    var frameOpt = SocketFrame.decode(buffer);
    if (frameOpt.isEmpty()) {
      log.debug("dropping undecodable frame from backend link to pod {}", link.getPodName());
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
          relayToSession(frame);
        }
      } else {
        pending.future().completeExceptionally(new RuntimeException(frame.getReason()));
      }
      return;
    }
    // MESSAGE / ACK / ERROR được relay lên client gần như nguyên vẹn (verbatim), không sửa đổi gì
    // thêm — nhưng trước tiên huỷ pending-tracking (nếu id này đang chờ, xem forwardMessage) để
    // timer MESSAGE_ACK_TIMEOUT_MS không bắn ERROR trùng lên 1 message vừa thật sự có hồi đáp.
    pendingAcks.remove(frame.getId());
    relayToSession(frame);
  }

  private void relayToSession(SocketFrame frame) {
    var session = sessions.get(frame.getHarborSessionId());
    if (session == null) {
      log.debug("dropping frame {} for unknown/disconnected harbor session {}", frame.getId(), frame.getHarborSessionId());
      return;
    }
    relayToClient.accept(session, frame);
  }
}
