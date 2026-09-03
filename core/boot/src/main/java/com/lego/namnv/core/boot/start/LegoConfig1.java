package com.lego.namnv.core.boot.start;

import com.lego.namnv.core.common.support.ParsedUri;
import com.lego.namnv.core.eventbus.server.EventBusServerConfig;
import com.lego.namnv.core.http.config.HttpServerConfig;
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
   * Chỉ dùng bởi harbor: số link song song (shard) cho MỖI pod colony, dùng CHUNG cho mọi client
   * session trên node harbor này (thay vì 1 link/session như trước) — xem
   * {@code BackendLinkGateway#shardFor}. Giảm mạnh tổng số connection vật lý harbor&lt;-&gt;colony, đổi
   * lại 1 session ồn ào có thể ảnh hưởng tới ~1/N session khác đang rơi vào cùng shard.
   */
  @Builder.Default private int chatBackendShardsPerPod = 4;
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
