package com.lego.harbor;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.lego.harbor.api.HealthCheckVerticle;
import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.comp.LifeCycle;
import com.lego.harbor.ws.SockjsSocketManager;
import com.lego.harbor.ws.SockjsSocketServer;
import io.vertx.core.Vertx;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

@AllArgsConstructor
public class HarborAppModule extends AbstractModule {

  private final @NonNull Vertx vertx;
  private final LegoConfig1 config;

  @Override
  protected void configure() {
    super.configure();
    bind(Vertx.class).toInstance(vertx);
    bind(LegoConfig1.class).toInstance(config);
  }

  /**
   * Định danh của chính node gateway này — cùng logic resolve (HOSTNAME k8s, fallback config cho local
   * dev) như colony's {@code ColonyAppModule}, để giữ nhất quán (consistent) giữa 2 module.
   * Lưu ý: khác với colony, bên harbor giá trị này chỉ dùng làm metadata (không đăng ký consumer
   * EventBus theo serverId), vì harbor không phải là đích (destination) của việc forward tin nhắn xuyên node.
   */
  private String resolveServerId() {
    var hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname;
    }
    return config.getChatSocket().getServiceName();
  }

  @Provides
  @Singleton
  private SockjsSocketManager sockjsSocketManager(PingoConnector connector) {
    return new SockjsSocketManager(resolveServerId(), vertx, connector, config);
  }

  @Provides
  @Singleton
  private SockjsSocketServer socketUpstreamHandler(SockjsSocketManager socketManager) {
    var chatSocket = config.getChatSocket();
    return SockjsSocketServer.builder() //
        .path(chatSocket.getPath())
        .host(chatSocket.getHost()) //
        .port(chatSocket.getPort()) //
        .serverId(resolveServerId()) //
        .socketManager(socketManager)
        .build();
  }

  @Provides
  @Singleton
  private HealthCheckVerticle healthCheckVerticle(SockjsSocketManager socketManager) {
    return new HealthCheckVerticle(config.getAdmHttp().getPort(), socketManager::isReady);
  }

  @SneakyThrows
  @Provides
  @Singleton
  PingoConnector initFiwConnector(Vertx vertx) {
    var eventBus = vertx.eventBus();
    var fiwConnector = PingoConnector.newDefault(eventBus);
    if (fiwConnector instanceof LifeCycle lc) lc.startSync();
    return fiwConnector;
  }
}
