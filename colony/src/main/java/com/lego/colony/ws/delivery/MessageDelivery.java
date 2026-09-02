package com.lego.colony.ws.delivery;

import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.connector.RouteByConversationIdRequest;
import com.lego.colony.ws.dto.MessageType;
import com.lego.colony.ws.dto.SocketFrame;
import com.lego.colony.ws.session.SessionRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Đưa 1 MESSAGE frame tới đúng conversation: deliver thẳng tới mọi subscriber cục bộ (local) nếu
 * conversation đó có subscriber trên node này, hoặc forward qua EventBus sang đúng node đang sở
 * hữu conversation đó (theo Maglev hash của {@code conversationId} — DM và group dùng chung 1 model,
 * DM chỉ là 1 conversation 2 thành viên). Đồng thời là nơi nhận MESSAGE được node khác forward tới
 * đây (xem {@link #onRoutedMessage}). Dùng bởi {@code com.lego.colony.ws.ChatSessionManager}.
 */
@Slf4j
@RequiredArgsConstructor
public class MessageDelivery {

  private final SessionRegistry registry;
  private final PingoConnector connector;

  /** Push MESSAGE tới mọi session cục bộ (local) đang subscribe conversation này trên node này. Trả về false nếu node này không có subscriber nào. */
  public boolean deliverLocally(SocketFrame frame) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException e) {
      log.warn("dropping message {} with invalid conversationId {}", frame.getId(), frame.getConversationId());
      return true; // conversationId sai định dạng, không phải lỗi do routing — không forward đi node khác nữa
    }
    var localSubscribers = registry.sessionsOfConversation(conversationId);
    if (localSubscribers.isEmpty()) {
      return false;
    }
    for (var subscriberSession : localSubscribers) {
      subscriberSession
          .send(frame)
          .exceptionally(
              ex -> {
                log.warn("failed to deliver message {} to session {}", frame.getId(), subscriberSession.getId(), ex);
                return null;
              });
    }
    return true;
  }

  /**
   * Route MESSAGE sang node đang sở hữu conversation này, theo {@code routingVersion} hiện hành.
   *
   * <p>Ngay sau khi routing table đổi version, một số session vẫn còn đang ở node CŨ một lúc —
   * gateway cần thời gian thật (network round-trip) để mở lại backend link, không phải tức thời
   * (xem {@code BackendLinkGateway.reconnectConversationToVersion}). Nếu chỉ gửi theo version mới
   * nhất, tin có thể tới đúng node theo bảng mới nhưng node đó CHƯA có subscriber vì gateway chưa
   * migrate xong — tin bị rơi. Để tránh khoảng hở đó: tính thêm node đích theo version ngay TRƯỚC
   * đó; nếu khác node hiện tại, gửi thêm 1 bản dự phòng sang đó. An toàn không sợ trùng: subscriber
   * thật chỉ còn sống ở đúng 1 trong 2 node tại một thời điểm (gateway chỉ đóng link cũ SAU KHI link
   * mới SUBSCRIBE_OK — xem {@code SockjsSocket.putLink} bên harbor), nên node còn lại chỉ log
   * "không có subscriber cục bộ" (xem {@link #onRoutedMessage}) rồi bỏ qua, không phải lỗi.
   */
  public void forwardToOwningNode(SocketFrame frame, int routingVersion) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException e) {
      log.warn("cannot forward message {}: invalid conversationId {}", frame.getId(), frame.getConversationId());
      return;
    }
    send(conversationId, frame, routingVersion);
    hedgeToPreviousVersionIfDifferent(conversationId, frame, routingVersion);
  }

  private void hedgeToPreviousVersionIfDifferent(UUID conversationId, SocketFrame frame, int routingVersion) {
    if (routingVersion <= 1) {
      return; // không có version nào trước đó để so sánh
    }
    var previousVersion = routingVersion - 1;
    var probeRequest = RouteByConversationIdRequest.builder().conversationId(conversationId).build();
    connector
        .routing(routingVersion, probeRequest)
        .thenCompose(currentRoute -> connector.routing(previousVersion, probeRequest).thenAccept(previousRoute -> {
          if (!previousRoute.getPodName().equals(currentRoute.getPodName())) {
            log.debug(
                "routing target for {} changed between v{} and v{} ({} -> {}) — hedging message {} to the previous owner too",
                conversationId, previousVersion, routingVersion, previousRoute.getPodName(), currentRoute.getPodName(), frame.getId());
            send(conversationId, frame, previousVersion);
          }
        }))
        .exceptionally(
            ex -> {
              // Không tra được version cũ (vd chưa từng có, hoặc lỗi routing tạm thời) — bỏ qua,
              // không phải lỗi nghiêm trọng, bản gửi theo version hiện tại ở trên vẫn đã đi.
              log.debug("skipping hedge-to-previous-version for message {}: {}", frame.getId(), ex.getMessage());
              return null;
            });
  }

  private void send(UUID conversationId, SocketFrame frame, int routingVersion) {
    var request =
        RouteByConversationIdRequest.builder()
            .conversationId(conversationId)
            .body(frame.encode())
            .headers(Map.of()) // EventBusRpcSender luôn lặp qua headers (foreach) không check null — nên bắt buộc phải truyền map rỗng, không được để null
            .build();
    connector
        .send(routingVersion, request)
        .exceptionally(
            ex -> {
              log.warn("failed to forward message {} to the node owning conversation {} (version {})", frame.getId(), conversationId, routingVersion, ex);
              return null;
            });
  }

  /** Nhận MESSAGE được forward tới đây từ một node khác, vì node này đang giữ subscriber(s) của conversation đó. */
  public void onRoutedMessage(Message<Buffer> message) {
    SocketFrame.decode(message.body())
        .filter(frame -> frame.getType() == MessageType.MESSAGE)
        .ifPresentOrElse(
            frame -> {
              if (!deliverLocally(frame)) {
                log.warn(
                    "routed message {} for conversation {} has no local subscriber on this node",
                    frame.getId(),
                    frame.getConversationId());
              }
            },
            () -> log.warn("received an undecodable routed message"));
  }
}
