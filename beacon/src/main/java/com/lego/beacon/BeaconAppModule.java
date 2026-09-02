package com.lego.beacon;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.lego.namnv.core.common.comp.LifeCycle;
import com.lego.namnv.discovery.k8s.K8sClientConfig;
import com.lego.namnv.discovery.router.Destination;
import com.lego.beacon.connector.LegoConnector;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Guice wiring của service beacon — chọn connector (k8s thật hay local) và khởi tạo publisher. */
@Slf4j
@AllArgsConstructor
public class BeaconAppModule extends AbstractModule {

  /** kubelet luôn tự inject biến môi trường này vào mọi container chạy trong cluster — cách chuẩn để nhận biết "đang chạy trong k8s hay không". */
  private static final String K8S_SERVICE_HOST_ENV = "KUBERNETES_SERVICE_HOST";
  private static final String HEALTHCHECK_PORT_ENV = "COLONY_HEALTHCHECK_PORT";
  private static final String HEALTHCHECK_PATH_ENV = "COLONY_HEALTHCHECK_PATH";

  private final @NonNull Vertx vertx;

  @Override
  protected void configure() {
    super.configure();
    bind(Vertx.class).toInstance(vertx);
  }

  @Provides
  @Singleton
  private RoutingGossipPublisher routingGossipPublisher(LegoConnector connector) {
    return new RoutingGossipPublisher(vertx, connector);
  }

  /**
   * Chọn connector tuỳ theo môi trường chạy: k8s thật (watch pod colony qua k8s API, có
   * healthcheck) khi chạy trong cluster, hoặc 1 destination local cố định khi chạy dev/test trên
   * máy. Trước đây chỗ này bị hardcode luôn dùng {@code newLocal} (nhánh k8s bị comment chết) —
   * nghĩa là khi deploy thật lên k8s, beacon sẽ KHÔNG BAO GIỜ phát hiện pod colony thật,
   * chỉ luôn báo đúng 1 destination giả ở 127.0.0.1. Đây là bug nghiêm trọng vì phá hỏng toàn bộ
   * mục đích tồn tại của service beacon trong môi trường multi-pod thật.
   */
  @SneakyThrows
  @Provides
  @Singleton
  LegoConnector initFiwConnector(Vertx vertx) {
    var eventBus = vertx.eventBus();
    var runningInK8s = StringUtils.isNotBlank(System.getenv(K8S_SERVICE_HOST_ENV));

    LegoConnector fiwConnector;
    if (runningInK8s) {
      var k8sClientConfig =
          K8sClientConfig.builder()
              .labelValue("colony")
              .labelKey("app")
              .namespace(StringUtils.defaultIfBlank(System.getenv("K8S_NAMESPACE"), "default"))
              .build();
      var healthcheckPort = Integer.parseInt(StringUtils.defaultIfBlank(System.getenv(HEALTHCHECK_PORT_ENV), "8085"));
      var healthcheckPath = StringUtils.defaultIfBlank(System.getenv(HEALTHCHECK_PATH_ENV), "/healthcheck");
      log.info("running in k8s, discovering colony pods in namespace {}", k8sClientConfig.getNamespace());
      fiwConnector = LegoConnector.newDefault(k8sClientConfig, eventBus, ip -> hel(vertx, ip, healthcheckPort, healthcheckPath));
    } else {
      log.info("no {} env var found — running as a single local destination for dev/test", K8S_SERVICE_HOST_ENV);
      fiwConnector = LegoConnector.newLocal(eventBus, List.of(Destination.of("eb_gossip_adm", InetAddress.getByName("127.0.0.1"))));
    }

    if (fiwConnector instanceof LifeCycle lc) {
      lc.startSync();
    }
    return fiwConnector;
  }

  /** Healthcheck HTTP GET đơn giản: coi pod là khoẻ mạnh (healthy) khi trả về status 200, lỗi/timeout đều coi là chưa sẵn sàng. */
  private CompletionStage<Boolean> hel(Vertx vertx, String ip, int port, String api) {
    HttpClient client = vertx.createHttpClient();
    return client
        .request(HttpMethod.GET, port, ip, api)
        .flatMap(HttpClientRequest::send)
        .toCompletionStage()
        .toCompletableFuture()
        .thenCompose(
            httpClientResponseAsyncResult -> {
              var statusCode = httpClientResponseAsyncResult.statusCode();
              return CompletableFuture.completedStage(statusCode == 200);
            })
        .exceptionally(throwable -> false);
  }
}
