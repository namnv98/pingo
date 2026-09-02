package com.lego.colony;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.lego.colony.api.RestApiVerticle;
import com.lego.namnv.connector.PingoConnector;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.comp.LifeCycle;
import com.lego.colony.ws.ChatSessionManager;
import com.lego.colony.ws.ChatSocketServer;
import com.lego.colony.ws.membership.ChannelMembershipRegistry;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
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
   * Định danh routing của chính node này: tên pod k8s khi chạy production (kubelet luôn export biến
   * môi trường {@code HOSTNAME} bằng đúng tên pod), hoặc giá trị fallback cấu hình sẵn khi chạy
   * local/single-node dev (chỉ có 1 node, không cần phân biệt pod nào với pod nào).
   */
  private String resolveServerId() {
    var hostname = System.getenv("HOSTNAME");
    if (hostname != null && !hostname.isBlank()) {
      return hostname;
    }
    return config.getChatSocket().getServiceName();
  }

  /**
   * Pool ket noi Postgres, dung PgPool (reactive, khong block) thay vi framework JDBC pool co san
   * trong core/commons-lang (jdbcpool/sql) -- framework do chua tung duoc dung o dau trong repo
   * (zero precedent), va ban chat blocking (can boc executeBlocking) khong hop voi phong cach
   * hoan toan async cua phan con lai code base nay. vertx-pg-client la client chinh thuc cua
   * Vert.x, khop tu nhien voi CompletionStage dang dung xuyen suot.
   */
  @Provides
  @Singleton
  private PgPool pgPool() {
    var db = config.getDatabase().getParsedUri();
    var address = db.getAddresses().get(0);
    var sepIndex = address.indexOf(':');
    var host = sepIndex == -1 ? address : address.substring(0, sepIndex);
    var port = sepIndex == -1 ? 5432 : Integer.parseInt(address.substring(sepIndex + 1));
    var connectOptions = new PgConnectOptions()
        .setHost(host)
        .setPort(port)
        .setDatabase(db.getPath())
        .setUser(db.getUser())
        .setPassword(db.getPassword());
    // maxSize co dinh, khong doc tu config.params -- gia tri do bi parse ra null bat thuong qua
    // Jackson (containsKey true nhung get tra ve null, chua ro nguyen nhan chinh xac tu
    // LegoJsonModule), khong dang de debug them cho 1 con so mac dinh don gian.
    var poolOptions = new PoolOptions().setMaxSize(8);
    return PgPool.pool(vertx, connectOptions, poolOptions);
  }

  @Provides
  @Singleton
  private ChannelMembershipRegistry channelMembershipRegistry(PgPool pgPool) {
    return new ChannelMembershipRegistry(pgPool);
  }

  @Provides
  @Singleton
  private ChatSessionManager chatSessionManager(PingoConnector pingoConnector, ChannelMembershipRegistry membership) {
    return new ChatSessionManager(resolveServerId(), vertx, pingoConnector, membership);
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
  private RestApiVerticle restApiVerticle(ChatSessionManager sessionManager) {
    return new RestApiVerticle(config.getPublicHttp().getPort(), sessionManager::isReady);
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
