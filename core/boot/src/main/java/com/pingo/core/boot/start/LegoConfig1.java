package com.pingo.core.boot.start;

import com.pingo.core.common.support.ParsedUri;
import com.pingo.core.eventbus.server.EventBusServerConfig;
import com.pingo.core.http.config.HttpServerConfig;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Properties;

@Getter
@SuperBuilder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class LegoConfig1 {
  private String basePackage;
  private HazelcastConfig hazelcast;
  private EventBusServerConfig admEventBus;
  private EventBusServerConfig msgEventBus;
  private HttpServerConfig admHttp;
  private HttpServerConfig publicHttp;
  /**
   * host/port/path của chat WebSocket endpoint của chính node này — bên harbor là endpoint
   * SockJS công khai (public); bên colony là endpoint WebSocket thuần (plain) phía backend.
   * Field {@code serviceName} được tận dụng lại làm định danh (identity) fallback cho local dev, dùng
   * trong việc routing tin nhắn xuyên node (cross-node) — xem thêm {@code ChatSessionManager} bên
   * colony để hiểu rõ tại sao cần định danh này.
   */
  private HttpServerConfig chatSocket;
  /**
   * Chỉ dùng bởi harbor: port/path để gateway dial (kết nối) tới node colony mà một
   * user được route tới. Field host ở đây không có ý nghĩa gì — vì host thực tế lấy từ kết quả routing
   * (IP của pod được resolve), chứ không lấy từ config này.
   */
  private HttpServerConfig chatBackend;
  /**
   * Secret dùng chung để ký (colony, lúc /register /login) và verify (harbor, lúc AUTH) token JWT
   * xác thực client — xem {@code com.lego.namnv.core.common.token.JwtHelper}. BẮT BUỘC harbor và
   * colony phải cấu hình CÙNG 1 giá trị, nếu không mọi AUTH sẽ fail verify chữ ký. Có default để
   * local dev không cần khai báo gì cũng chạy được (cả 2 phía cùng rơi vào default giống nhau) —
   * production thật nên override bằng giá trị riêng, bí mật.
   */
  @Builder.Default private String authTokenSecret = "dev-insecure-shared-secret-change-me";
  private DbConfig database;
  private InvalidateCacheConfig invalidateCacheConfig;
  private KafkaConfig kafka;

  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class HazelcastConfig {
    private String file;
    private Properties props;
  }

  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DbConfig {
    private ParsedUri parsedUri;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor(staticName = "of")
  public static class InvalidateCacheConfig {
    private String eventBusAddress;
  }

  @Data
  @Builder(toBuilder = true)
  @NoArgsConstructor
  @AllArgsConstructor
  public static class KafkaConfig {
    private String bootstrapServer;
    private String topics;
    private Properties properties;
    private long pollingTimeoutMillis = 100l;
  }
}
