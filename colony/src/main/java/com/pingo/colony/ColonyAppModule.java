package com.pingo.colony;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.pingo.colony.api.HealthCheckVerticle;
import com.pingo.connector.PingoConnector;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.comp.LifeCycle;
import com.pingo.colony.ws.ChatSessionManager;
import com.pingo.chat.domain.history.MessageHistoryRegistry;
import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import com.pingo.core.grpc.server.LegoGrpcServer;
import com.pingo.chat.grpc.LinkService;
import io.vertx.core.Vertx;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

@AllArgsConstructor
public class ColonyAppModule extends AbstractModule {

  private final @NonNull Vertx vertx;
  private final LegoConfig1 config;

  @Override
  protected void configure() {
    super.configure();
    bind(Vertx.class).toInstance(vertx);
    bind(LegoConfig1.class).toInstance(config);
  }

  /**
   * Định danh routing của node này: tên pod k8s khi chạy production ({@code HOSTNAME} do kubelet
   * export), hoặc giá trị fallback cấu hình sẵn khi chạy local/single-node dev.
   */
  private String resolveServerId() {
    var hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname;
    }
    return config.getChatSocket().getServiceName();
  }

  /**
   * Ket noi Postgres qua JdbcConnectionSupplier (core/commons-lang, vertx-jdbc-client) -- framework
   * dung chung cho ca du an thay vi tung service tu mo pool rieng (xem ARCHITECTURE.md muc 14).
   * config.getDatabase().getParsedUri() dung dung shape ma JdbcConfig.from() can (scheme: steady,
   * databaseType: postgres, addresses/user/password).
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

  @Provides
  @Singleton
  private MessageHistoryRegistry messageHistoryRegistry(JdbcConnectionSupplier supplier) {
    return new MessageHistoryRegistry(supplier);
  }

  @Provides
  @Singleton
  private ChatSessionManager chatSessionManager(
      PingoConnector pingoConnector, ConversationMembershipRegistry membership, MessageHistoryRegistry history) {
    return new ChatSessionManager(resolveServerId(), vertx, pingoConnector, membership, history);
  }

  @Provides
  @Singleton
  private LegoGrpcServer socketUpstreamHandler(ChatSessionManager sessionManager) {
    var chatSocket = config.getChatSocket();
    return LegoGrpcServer.builder() //
        .host(chatSocket.getHost()) //
        .port(chatSocket.getPort()) //
        .registrar(grpcServer -> grpcServer.callHandler(LinkService.STREAM_SERVER, sessionManager::onConnection))
        .build();
  }

  @Provides
  @Singleton
  private HealthCheckVerticle healthCheckVerticle(ChatSessionManager sessionManager) {
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
