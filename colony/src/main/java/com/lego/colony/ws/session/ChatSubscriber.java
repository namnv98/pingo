package com.lego.colony.ws.session;

import com.lego.colony.ws.dto.SocketFrame;
import io.vertx.core.buffer.Buffer;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

/**
 * 1 subscriber logic trên node colony này — tương ứng 1:1 với 1 harbor session cụ thể (định danh
 * bởi {@code harborSessionId}, do harbor gán và gửi kèm mọi frame, xem {@code SocketFrame}), KHÔNG
 * còn tương ứng 1:1 với 1 {@link ChatLink} (connection vật lý) như trước — nhiều subscriber có thể
 * cùng "cưỡi" trên 1 link dùng chung (harbor sharded link theo pod). Mỗi pod colony chỉ thấy đúng 1
 * {@code ChatSubscriber} cho mỗi harborSessionId (registry theo từng pod), dù session đó có thể có
 * subscriber khác trên các pod colony khác cho các conversation khác.
 */
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Setter
public class ChatSubscriber implements MessageChatSocket {

  @EqualsAndHashCode.Include private final @NonNull String harborSessionId;
  private volatile @NonNull ChatLink link;
  private UUID userId;
  private int currentVersion;
  /** conversationId nào đang được subscribe bởi chính session này — xem SessionRegistry.subscribe/removeSubscriber. */
  private final Set<UUID> conversationIds = ConcurrentHashMap.newKeySet();

  public ChatSubscriber(@NonNull String harborSessionId, @NonNull ChatLink link) {
    this.harborSessionId = harborSessionId;
    this.link = link;
  }

  @Override
  public String getId() {
    return harborSessionId;
  }

  public CompletionStage<Void> send(SocketFrame frame) {
    return link.send(frame);
  }

  @Override
  public CompletionStage<Void> send(Buffer data) {
    return link.send(data);
  }

  @Override
  public CompletionStage<Void> send(String data) {
    return link.send(data);
  }

  @Override
  public String getHeader(String headerName) {
    return link.getHeader(headerName);
  }
}
