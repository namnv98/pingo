package com.pingo.colony.ws.delivery;

import com.google.protobuf.InvalidProtocolBufferException;
import com.pingo.connector.PingoConnector;
import com.pingo.connector.RouteByConversationIdRequest;
import com.pingo.namnv.discovery.grpc.Frame;
import com.pingo.namnv.discovery.grpc.FrameType;
import com.pingo.colony.ws.session.SessionRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Đưa 1 MESSAGE frame tới đúng conversation: deliver thẳng cho subscriber cục bộ nếu có trên node
 * này, hoặc forward qua EventBus sang node sở hữu conversation đó (theo Maglev hash của
 * conversationId — DM chỉ là 1 conversation 2 thành viên, dùng chung model với group). Cũng là nơi
 * nhận MESSAGE do node khác forward tới (xem {@link #onRoutedMessage}). Payload EventBus là bytes
 * protobuf ({@code Frame.toByteArray()}) — cùng envelope {@code Frame} dùng cho chặng harbor↔colony,
 * không còn định dạng riêng cho forward xuyên node.
 */
@Slf4j
@RequiredArgsConstructor
public class MessageDelivery {

  private final SessionRegistry registry;
  private final PingoConnector connector;

  /** Push MESSAGE tới mọi session cục bộ (local) đang subscribe conversation này trên node này. Trả về false nếu node này không có subscriber nào. */
  public boolean deliverLocally(Frame frame) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(frame.getConversationId());
    } catch (IllegalArgumentException e) {
      log.warn("dropping message {} with invalid conversationId {}", frame.getId(), frame.getConversationId());
      return true; // conversationId sai định dạng, không phải lỗi do routing — không forward đi node khác nữa
    }
    var localSubscribers = registry.subscribersOfConversation(conversationId);
    if (localSubscribers.isEmpty()) {
      return false;
    }
    for (var session : localSubscribers) {
      try {
        session.send(frame);
      } catch (Exception ex) {
        log.warn("failed to deliver message {} to session {}", frame.getId(), session.getId(), ex);
      }
    }
    return true;
  }

  /**
   * Route MESSAGE sang node đang sở hữu conversation này, theo {@code routingVersion} hiện hành.
   * Hedge sang version ngay trước đó nếu khác pod — xem giải thích chi tiết trong ARCHITECTURE.md
   * mục 10 (race condition khi routing table đổi version giữa lúc gateway đang migrate).
   */
  public void forwardToOwningNode(Frame frame, int routingVersion) {
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

  private void hedgeToPreviousVersionIfDifferent(UUID conversationId, Frame frame, int routingVersion) {
    if (routingVersion <= 1) {
      return;
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
              log.debug("skipping hedge-to-previous-version for message {}: {}", frame.getId(), ex.getMessage());
              return null;
            });
  }

  private void send(UUID conversationId, Frame frame, int routingVersion) {
    var request =
        RouteByConversationIdRequest.builder()
            .conversationId(conversationId)
            .body(Buffer.buffer(frame.toByteArray()))
            .headers(Map.of()) // EventBusRpcSender lặp headers khong check null -- bat buoc map rong, khong duoc null
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
    Frame frame;
    try {
      frame = Frame.parseFrom(message.body().getBytes());
    } catch (InvalidProtocolBufferException e) {
      log.warn("received an undecodable routed message", e);
      return;
    }
    if (frame.getType() != FrameType.MESSAGE) {
      return;
    }
    if (!deliverLocally(frame)) {
      log.warn("routed message {} for conversation {} has no local subscriber on this node", frame.getId(), frame.getConversationId());
    }
  }
}
