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
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
   * File secret Firebase Admin SDK khi chạy trên k3s -- mount qua k8s Secret {@code herald-firebase}
   * (xem herald/helm/templates/deployment.yml, volume "firebase-secret", {@code optional: true} nên
   * THIẾU secret KHÔNG làm crash pod, chỉ file không tồn tại). Dùng file thay vì nhét JSON vào
   * ConfigMap (biến {@code config.getFirebase().getServiceAccountJson()} bên dưới) vì JSON chứa
   * dấu nháy kép + PEM nhiều dòng rất dễ vỡ escape lúc nhúng vào YAML/env var -- đọc thẳng file
   * mount tránh hẳn vấn đề đó.
   */
  private static final String FIREBASE_SERVICE_ACCOUNT_FILE = "/secrets/firebase/serviceAccountJson.json";

  /**
   * Credentials Firebase Admin SDK -- ưu tiên đọc file mount {@link #FIREBASE_SERVICE_ACCOUNT_FILE}
   * (cách dùng lúc chạy trên k3s, xem javadoc field đó); không có file thì fallback về
   * {@code config.getFirebase().getServiceAccountJson()} (nguyên văn nội dung JSON đặt thẳng trong
   * config -- dùng lúc chạy local bằng {@code mvn exec:java} với {@code app.local.yaml} riêng,
   * KHÔNG commit, xem app.yaml). Thiếu/rỗng cả 2 nguồn KHÔNG làm crash herald -- {@link PushService}
   * tự chuyển sang chế độ no-op (notifications vẫn lưu DB/đọc qua {@code GET /notifications} bình
   * thường, chỉ riêng bước gửi push thật bị bỏ qua + log rõ).
   */
  @SneakyThrows
  @Provides
  @Singleton
  private PushService pushService(PushTokenRegistry pushTokens) {
    var serviceAccountJson = resolveFirebaseServiceAccountJson();
    if (serviceAccountJson == null || serviceAccountJson.isBlank()) {
      return PushService.disabled(pushTokens);
    }
    var inputStream = new ByteArrayInputStream(serviceAccountJson.getBytes(StandardCharsets.UTF_8));
    var options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(inputStream)).build();
    var firebaseApp = FirebaseApp.initializeApp(options);
    var executor = new FirebaseMessageExecutor(FirebaseMessaging.getInstance(firebaseApp));
    return new PushService(executor, pushTokens);
  }

  private String resolveFirebaseServiceAccountJson() {
    var file = new java.io.File(FIREBASE_SERVICE_ACCOUNT_FILE);
    if (file.isFile()) {
      try {
        return java.nio.file.Files.readString(file.toPath(), StandardCharsets.UTF_8);
      } catch (java.io.IOException e) {
        log.warn("failed to read firebase service account file at {} -- falling back to inline config", FIREBASE_SERVICE_ACCOUNT_FILE, e);
      }
    }
    return config.getFirebase() == null ? null : config.getFirebase().getServiceAccountJson();
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
