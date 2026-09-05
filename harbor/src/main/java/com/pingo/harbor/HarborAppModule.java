package com.pingo.harbor;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.pingo.harbor.api.HealthCheckVerticle;
import com.pingo.connector.PingoConnector;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.comp.LifeCycle;
import com.pingo.core.common.token.JwtHelper;
import com.auth0.jwt.algorithms.Algorithm;
import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import com.pingo.harbor.ws.HarborSessionManager;
import com.pingo.core.socket.LegoSocketServer;
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
   * Định danh node gateway — cùng logic resolve (HOSTNAME k8s, fallback config) như colony's
   * {@code ColonyAppModule}. Khác colony: ở đây chỉ dùng làm metadata, không đăng ký EventBus
   * consumer theo serverId, vì harbor không phải đích forward tin nhắn xuyên node.
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
  private HarborSessionManager harborSessionManager(
      PingoConnector connector, JwtHelper jwtHelper, ConversationMembershipRegistry membership) {
    return new HarborSessionManager(resolveServerId(), vertx, connector, config, jwtHelper, membership);
  }

  /**
   * Ket noi Postgres qua JdbcConnectionSupplier -- cung framework/pattern voi colony
   * (xem ColonyAppModule), o day CHI DOC (khong ghi) de auto-subscribe user vao het conversation
   * cua ho ngay sau AUTH (xem HarborSessionManager#handleAuth), khong doi client tu SUBSCRIBE tung
   * conversationId mot (voi user co hang tram/nghin conversation se can hang tram/nghin frame).
   */
  @SneakyThrows
  @Provides
  @Singleton
  private JdbcConnectionSupplier jdbcConnectionSupplier() {
    var supplier = JdbcConnectionSupplier.from(config.getDatabase().getParsedUri(), vertx);
    supplier.startSync();
    return supplier;
  }

  @Provides
  @Singleton
  private ConversationMembershipRegistry conversationMembershipRegistry(JdbcConnectionSupplier supplier) {
    return new ConversationMembershipRegistry(supplier);
  }

  /** Verify JWT lúc AUTH — PHẢI cùng {@code authTokenSecret} với colony (nơi ký token lúc /register, /login). */
  @Provides
  @Singleton
  private JwtHelper jwtHelper() {
    return new JwtHelper(Algorithm.HMAC256(config.getAuthTokenSecret()));
  }

  @Provides
  @Singleton
  private LegoSocketServer socketUpstreamHandler(HarborSessionManager sessionManager) {
    var chatSocket = config.getChatSocket();
    return LegoSocketServer.builder() //
        .path(chatSocket.getPath())
        .host(chatSocket.getHost()) //
        .port(chatSocket.getPort()) //
        .socketHandler(sessionManager::onConnection)
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
