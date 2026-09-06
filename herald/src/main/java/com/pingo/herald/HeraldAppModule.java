package com.pingo.herald;

import com.auth0.jwt.algorithms.Algorithm;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.hazelcast.core.HazelcastInstance;
import com.pingo.chat.domain.notification.NotificationRegistry;
import com.pingo.chat.domain.notification.PushTokenRegistry;
import com.pingo.chat.domain.presence.PresenceRegistry;
import com.pingo.core.api.registry.IApiRegistry;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.http.LegoHttpServer;
import com.pingo.core.http.config.HttpStatusErrorMapping;
import com.pingo.herald.push.FirebaseMessageExecutor;
import com.pingo.herald.push.PushService;
import io.vertx.core.Vertx;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

@AllArgsConstructor
public class HeraldAppModule extends AbstractModule {

  private final @NonNull Vertx vertx;
  private final LegoConfig1 config;
  private final @NonNull HazelcastInstance hazelcastInstance;

  @Override
  protected void configure() {
    super.configure();
    bind(Vertx.class).toInstance(vertx);
    bind(LegoConfig1.class).toInstance(config);
    bind(HazelcastInstance.class).toInstance(hazelcastInstance);
  }

  /** false kể từ khi service bắt đầu drain — dùng cho readinessProbe (xem {@code HeraldApiHandlers}). */
  @Provides
  @Singleton
  private AtomicBoolean ready() {
    return new AtomicBoolean(true);
  }

  /** Kết nối Postgres riêng của herald qua {@link JdbcConnectionSupplier} — cùng framework dùng chung cho toàn dự án (xem ARCHITECTURE.md mục 14). */
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
  private NotificationRegistry notificationRegistry(JdbcConnectionSupplier supplier) {
    return new NotificationRegistry(supplier);
  }

  @Provides
  @Singleton
  private PresenceRegistry presenceRegistry(HazelcastInstance hazelcastInstance) {
    return new PresenceRegistry(hazelcastInstance);
  }

  @Provides
  @Singleton
  private PushTokenRegistry pushTokenRegistry(JdbcConnectionSupplier supplier) {
    return new PushTokenRegistry(supplier);
  }

  /**
   * Credentials Firebase Admin SDK đọc từ {@code config.getFirebase().getServiceAccountJson()}
   * (nguyên văn nội dung file JSON service account, xem javadoc {@code LegoConfig1.FirebaseConfig})
   * — cùng cách config khác của herald (app.yaml/ConfigMap). Thiếu/rỗng giá trị này KHÔNG làm crash
   * herald — {@link PushService} tự chuyển sang chế độ no-op (notifications vẫn lưu DB/đọc qua
   * {@code GET /notifications} bình thường, chỉ riêng bước gửi push thật bị bỏ qua + log rõ).
   */
  @SneakyThrows
  @Provides
  @Singleton
  private PushService pushService(PushTokenRegistry pushTokens) {
    var serviceAccountJson = config.getFirebase() == null ? null : config.getFirebase().getServiceAccountJson();
    if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
      return PushService.disabled(pushTokens);
    }
    var inputStream = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
    var options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(inputStream)).build();
    var firebaseApp = FirebaseApp.initializeApp(options);
    var executor = new FirebaseMessageExecutor(FirebaseMessaging.getInstance(firebaseApp));
    return new PushService(executor, pushTokens);
  }

  @Provides
  @Singleton
  private NotificationConsumer notificationConsumer(PresenceRegistry presence, NotificationRegistry notifications, PushService pushService) {
    return new NotificationConsumer(vertx, presence, notifications, pushService);
  }

  /** Ký (không dùng ở herald) và verify (GET/PUT /notifications) token JWT -- cùng secret dùng bên hall/colony/harbor. */
  @Provides
  @Singleton
  private JwtHelper jwtHelper() {
    return new JwtHelper(Algorithm.HMAC256(config.getAuthTokenSecret()));
  }

  @Provides
  @Singleton
  private IApiRegistry apiRegistry(Injector injector) {
    return IApiRegistry.scanClasspath("com.pingo.herald", injector);
  }

  @Provides
  @Singleton
  private HttpStatusErrorMapping httpStatusErrorMapping() {
    return HttpStatusErrorMapping.scanAndCreate("com.pingo.herald");
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
