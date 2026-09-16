package com.pingo.hall;

import com.auth0.jwt.algorithms.Algorithm;
import com.google.inject.AbstractModule;
import com.google.inject.Injector;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.hazelcast.core.HazelcastInstance;
import com.pingo.chat.domain.e2e.E2eKeyRegistry;
import com.pingo.chat.domain.e2e.MlsDeviceRegistry;
import com.pingo.chat.domain.e2e.MlsRegistry;
import com.pingo.chat.domain.e2e.RevokedDeviceRegistry;
import com.pingo.core.boot.start.LegoBootStart;
import com.pingo.chat.domain.file.FileRegistry;
import com.pingo.chat.domain.history.MessageHistoryRegistry;
import com.pingo.chat.domain.link.MessageLinkRegistry;
import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.chat.domain.notification.NotificationRegistry;
import com.pingo.chat.domain.pin.MessagePinRegistry;
import com.pingo.chat.domain.preview.LinkPreviewService;
import com.pingo.chat.domain.user.UserRegistry;
import com.pingo.core.api.registry.IApiRegistry;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.http.LegoHttpServer;
import com.pingo.core.http.config.HttpStatusErrorMapping;
import io.vertx.core.Vertx;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;

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
   * Kết nối Postgres riêng của hall qua {@link JdbcConnectionSupplier} — KHÔNG dùng chung instance
   * với {@code colony} (2 service độc lập, chạy trên pod khác nhau), chỉ dùng chung schema/database.
   * Cùng framework dùng chung cho toàn dự án (xem ARCHITECTURE.md mục 14).
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

  /**
   * Resolve og: cho {@code GET /link-preview} (xem {@code LinkPreviewRegistry}) -- client gọi ngay lúc
   * ĐANG GÕ để hiện card xem trước trong ô nhập, giống Slack {@code chat.unfurlLink}. Cùng lớp colony
   * dùng cho pha enrich sau khi lưu tin, xem {@code ColonyAppModule}. Singleton để giữ 1 WebClient chung.
   */
  @Provides
  @Singleton
  private LinkPreviewService linkPreviewService() {
    return new LinkPreviewService(vertx);
  }

  @Provides
  @Singleton
  private UserRegistry userRegistry(JdbcConnectionSupplier supplier) {
    return new UserRegistry(supplier);
  }

  /** Chỉ dùng cho {@code DELETE /conversations} (xoá notification liên quan) -- herald mới là nơi TẠO notification (offline noti), hall chỉ cần xoá. */
  @Provides
  @Singleton
  private NotificationRegistry notificationRegistry(JdbcConnectionSupplier supplier) {
    return new NotificationRegistry(supplier);
  }

  /** Metadata upload/download ảnh-video -- xem javadoc {@link FileRegistry}. */
  @Provides
  @Singleton
  private FileRegistry fileRegistry(JdbcConnectionSupplier supplier) {
    return new FileRegistry(supplier);
  }

  /** Ghim tin nhắn (chung + riêng) -- xem javadoc {@link MessagePinRegistry}. */
  @Provides
  @Singleton
  private MessagePinRegistry messagePinRegistry(JdbcConnectionSupplier supplier) {
    return new MessagePinRegistry(supplier);
  }

  /** Danh sách link đã trích từ nội dung tin nhắn -- xem javadoc {@link MessageLinkRegistry}. */
  @Provides
  @Singleton
  private MessageLinkRegistry messageLinkRegistry(JdbcConnectionSupplier supplier) {
    return new MessageLinkRegistry(supplier);
  }

  /** Khoá cho mã hoá đầu cuối (identity key/one-time prekey + hàng đợi to-device) -- xem javadoc {@link E2eKeyRegistry}. */
  @Provides
  @Singleton
  private E2eKeyRegistry e2eKeyRegistry(JdbcConnectionSupplier supplier) {
    return new E2eKeyRegistry(supplier);
  }

  /** Directory KeyPackage cho client MLS (RFC 9420) -- xem javadoc {@link MlsRegistry}. */
  @Provides
  @Singleton
  private MlsRegistry mlsRegistry(JdbcConnectionSupplier supplier) {
    return new MlsRegistry(supplier);
  }

  /**
   * Cụm Hazelcast hall ĐÃ join sẵn (xem {@code HallBoot}'s javadoc + {@code app.yaml}'s {@code
   * hazelcast:}) -- lấy lại instance {@code LegoBootStart#initVertx} đã tạo, KHÔNG tự tạo instance
   * thứ 2 (2 instance riêng sẽ không cùng cluster, {@link RevokedDeviceRegistry} sẽ không thấy được
   * thu hồi từ harbor/colony).
   */
  @Provides
  @Singleton
  private HazelcastInstance hazelcastInstance() {
    return LegoBootStart.getHazelcastInstance(vertx);
  }

  /** Registry + trạng thái thu hồi thiết bị MLS -- xem javadoc {@link MlsDeviceRegistry}. */
  @Provides
  @Singleton
  private MlsDeviceRegistry mlsDeviceRegistry(JdbcConnectionSupplier supplier) {
    return new MlsDeviceRegistry(supplier);
  }

  /**
   * Cache đồng bộ "deviceId đã revoke chưa" -- xem javadoc {@link RevokedDeviceRegistry}. Hydrate từ
   * Postgres (nguồn thật) BẤT ĐỒNG BỘ, KHÔNG {@code .join()}/block -- bug thật đã gặp: {@code
   * @Provides} method chạy trên thread gọi {@code injector.getInstance(...)} (main thread lúc boot,
   * KHÔNG phải Vert.x event loop), nhưng {@link JdbcConnectionSupplier} hoàn thành Future của nó
   * thông qua đúng Vert.x context/event loop đang bị HallBoot khởi tạo dở -- block-chờ ở đây khiến
   * event loop không bao giờ chạy tiếp để tự hoàn thành chính Future đang bị chờ -> deadlock, service
   * "start" xong (log in ra bình thường) nhưng treo cứng, không phản hồi BẤT KỲ request nào (kể cả
   * /healthcheck, không đụng gì tới bảng này). Chấp nhận 1 cửa sổ rất ngắn (vài chục ms) lúc mới boot
   * nơi cache còn rỗng/chưa kịp hydrate xong (thiết bị đã revoke trước đó tạm thời "sống lại") --
   * hiếm khi trùng đúng lúc app khởi động lại, còn hơn đánh đổi cả service treo.
   */
  @Provides
  @Singleton
  private RevokedDeviceRegistry revokedDeviceRegistry(HazelcastInstance hazelcastInstance, MlsDeviceRegistry mlsDevices) {
    var registry = new RevokedDeviceRegistry(hazelcastInstance);
    mlsDevices.listAllRevokedDeviceIds().thenAccept(ids -> ids.forEach(registry::markRevoked));
    return registry;
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
