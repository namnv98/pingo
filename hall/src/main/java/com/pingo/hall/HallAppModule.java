package com.pingo.hall;

import com.auth0.jwt.algorithms.Algorithm;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.pingo.colony.domain.history.MessageHistoryRegistry;
import com.pingo.colony.domain.membership.ConversationMembershipRegistry;
import com.pingo.colony.domain.user.UserRegistry;
import com.pingo.core.api.registry.IApiRegistry;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.http.LegoHttpServer;
import com.pingo.core.http.config.HttpStatusErrorMapping;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.NonNull;

@AllArgsConstructor
public class HallAppModule extends AbstractModule {

  private final @NonNull Vertx vertx;
  private final LegoConfig1 config;

  @Override
  protected void configure() {
    super.configure();
    bind(Vertx.class).toInstance(vertx);
    bind(LegoConfig1.class).toInstance(config);
  }

  /** false kể từ khi service bắt đầu drain — dùng cho readinessProbe (xem {@code HallApiHandlers}). */
  @Provides
  @Singleton
  private AtomicBoolean ready() {
    return new AtomicBoolean(true);
  }

  /**
   * Pool Postgres riêng của hall — KHÔNG dùng chung instance với {@code colony} (2 service
   * độc lập, chạy trên pod khác nhau), chỉ dùng chung schema/database. Xem {@code ColonyAppModule}
   * bên colony để so sánh — cùng cách setup, tách bản ghi vì không share process.
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
    var poolOptions = new PoolOptions().setMaxSize(8);
    return PgPool.pool(vertx, connectOptions, poolOptions);
  }

  @Provides
  @Singleton
  private ConversationMembershipRegistry conversationMembershipRegistry(PgPool pgPool) {
    return new ConversationMembershipRegistry(pgPool);
  }

  @Provides
  @Singleton
  private MessageHistoryRegistry messageHistoryRegistry(PgPool pgPool) {
    return new MessageHistoryRegistry(pgPool);
  }

  @Provides
  @Singleton
  private UserRegistry userRegistry(PgPool pgPool) {
    return new UserRegistry(pgPool);
  }

  /** Ký (POST /register, /login) và verify (PUT /users, GET /conversations) token JWT -- cùng secret dùng bên harbor (xem HarborAppModule), 2 bên PHẢI cấu hình cùng giá trị {@code authTokenSecret}. */
  @Provides
  @Singleton
  private JwtHelper jwtHelper() {
    return new JwtHelper(Algorithm.HMAC256(config.getAuthTokenSecret()));
  }

  /**
   * Quét package {@code com.pingo.hall} tìm method có {@code @RegisterHandler} (xem
   * {@code HallApiHandlers}) — thay cho việc tự viết {@code Router}/route-map tay như trước. Cần
   * chính {@code Injector} để dispatch (Guice tự cung cấp — inject {@code Injector} vào chính
   * binding của nó là pattern chuẩn).
   */
  @Provides
  @Singleton
  private IApiRegistry apiRegistry(Injector injector) {
    return IApiRegistry.scanClasspath("com.pingo.hall", injector);
  }

  /** Quét {@code @RegisterErrorMapper} trong {@code com.pingo.hall} (xem {@code HallErrorKeys}) — error key -> HTTP status code. */
  @Provides
  @Singleton
  private HttpStatusErrorMapping httpStatusErrorMapping() {
    return HttpStatusErrorMapping.scanAndCreate("com.pingo.hall");
  }

  @Provides
  @Singleton
  private LegoHttpServer legoHttpServer(IApiRegistry apiRegistry, HttpStatusErrorMapping errorMapping, Injector injector) {
    return LegoHttpServer.builder()
        .config(config.getPublicHttp())
        .apiRegistry(apiRegistry)
        .errorMapping(errorMapping)
        .injector(injector)
        .build();
  }
}
