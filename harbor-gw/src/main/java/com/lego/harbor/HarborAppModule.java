package com.lego.harbor;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.lego.harbor.api.HealthCheckVerticle;
import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.comp.LifeCycle;
import com.lego.namnv.core.common.token.JwtHelper;
import com.auth0.jwt.algorithms.Algorithm;
import com.lego.harbor.ws.HarborSessionManager;
import com.lego.harbor.ws.HarborSocketServer;
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
  private HarborSessionManager harborSessionManager(PingoConnector connector, JwtHelper jwtHelper) {
    return new HarborSessionManager(resolveServerId(), vertx, connector, config, jwtHelper);
  }

  /**
   * Verify token JWT lúc AUTH (xem HarborSessionManager#handleAuth) -- cùng secret dùng bên colony
   * để ký lúc /register /login (xem ColonyAppModule), nên 2 bên PHẢI cấu hình cùng giá trị
   * {@code authTokenSecret}. Tái dùng JwtHelper có sẵn trong core/commons-lang (transitive qua
   * core/http), trước đây chưa được dùng ở đâu trong repo.
   */
  @Provides
  @Singleton
  private JwtHelper jwtHelper() {
    return new JwtHelper(Algorithm.HMAC256(config.getAuthTokenSecret()));
  }

  @Provides
  @Singleton
  private HarborSocketServer socketUpstreamHandler(HarborSessionManager sessionManager) {
    var chatSocket = config.getChatSocket();
    return HarborSocketServer.builder() //
        .path(chatSocket.getPath())
        .host(chatSocket.getHost()) //
        .port(chatSocket.getPort()) //
        .serverId(resolveServerId()) //
        .sessionManager(sessionManager)
        .build();
  }

  @Provides
  @Singleton
  private HealthCheckVerticle healthCheckVerticle(HarborSessionManager sessionManager) {
    return new HealthCheckVerticle(config.getAdmHttp().getPort(), sessionManager::isReady);
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
