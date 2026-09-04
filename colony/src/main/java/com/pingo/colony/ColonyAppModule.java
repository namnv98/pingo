package com.pingo.colony;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.pingo.colony.api.HealthCheckVerticle;
import com.pingo.connector.PingoConnector;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.comp.LifeCycle;
import com.pingo.colony.ws.ChatSessionManager;
import com.pingo.colony.ws.ChatSocketServer;
import com.pingo.colony.domain.history.MessageHistoryRegistry;
import com.pingo.colony.domain.membership.ConversationMembershipRegistry;
import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
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
   * {@code database.parsedUri} trong config (databaseType/scheme/addresses...) đã đúng hình dạng
   * {@code JdbcConfig.from(ParsedUri)} cần — dùng thẳng {@link JdbcConnectionSupplier} (core/commons-lang,
   * đã async sẵn qua {@code CompletionStage}, có thêm master/slave routing + health-sensing) thay vì
   * tự tay dựng {@code PgPool} như trước (xem ARCHITECTURE.md mục 14).
   */
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
  private ChatSocketServer socketUpstreamHandler(ChatSessionManager sessionManager) {
    var chatSocket = config.getChatSocket();
    return ChatSocketServer.builder() //
        .path(chatSocket.getPath()) //
        .host(chatSocket.getHost()) //
        .port(chatSocket.getPort()) //
        .serverId(resolveServerId()) //
        .sessionManager(sessionManager)
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
