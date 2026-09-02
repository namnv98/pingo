package com.lego.harbor.ws.session;

import io.vertx.core.http.WebSocket;

import lombok.Getter;
import lombok.Setter;

/**
 * 1 WebSocket link thuần (plain) từ gateway xuống đúng 1 pod colony — dùng CHUNG cho mọi
 * conversationId nào tình cờ hash ra cùng pod đó (xem {@link SockjsSocket#linksByPod}), thay vì
 * 1 link/conversation. {@code podIp} chỉ dùng để so sánh lúc reconnect (đổi IP = đổi pod thật sự,
 * không chỉ đổi tên).
 */
@Getter
public class BackendLink {

  private final String podName;
  private final String podIp;
  private final WebSocket socket;
  @Setter private volatile int version;
  @Setter private volatile long lastPongAt = System.currentTimeMillis();

  public BackendLink(String podName, String podIp, WebSocket socket, int version) {
    this.podName = podName;
    this.podIp = podIp;
    this.socket = socket;
    this.version = version;
  }

  public boolean isClosed() {
    return socket.isClosed();
  }

  public void close() {
    if (!socket.isClosed()) {
      socket.close();
    }
  }
}
