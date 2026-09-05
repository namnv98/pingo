package com.pingo.core.boot.start;

import com.hazelcast.config.MemberAttributeConfig;
import com.hazelcast.config.XmlConfigBuilder;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.pingo.core.boot.start.yaml.YamlConfigReader;
import com.pingo.core.common.support.UUIDUtils;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.internal.VertxInternal;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.micrometer.Label;
import io.vertx.micrometer.MetricsDomain;
import io.vertx.micrometer.MicrometerMetricsFactory;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;
import lombok.SneakyThrows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class LegoBootStart {
    protected static Logger log;

    protected static void initLog() {
        log = LoggerFactory.getLogger(LegoBootStart.class);
    }

    @SneakyThrows
    public static <T> T loadConfig(String configFilePath, Class<T> aClass) {
        return YamlConfigReader.forType(aClass).readYaml(configFilePath);
    }

    /**
     * Luôn tự embed 1 Hazelcast member (Vert.x's {@code HazelcastClusterManager} bắt buộc phải
     * vậy — gọi {@code getLocalMember()} lúc join, chỉ member mới trả lời được, Hazelcast Client
     * luôn ném {@code UnsupportedOperationException} ở đây, không có cách nào dùng Client được).
     * Khác biệt local dev vs k8s nằm hoàn toàn ở NỘI DUNG file {@code hazelcast.xml} được trỏ tới
     * (multicast cho local, tcp-ip tới backbone 3 pod ổn định + {@code lite-member} cho k8s — xem
     * thư mục {@code hazelcast/} ở root repo và {@code deploy-k3s.sh}), không phải ở code Java.
     */
    private static HazelcastInstance initHazelcast(LegoConfig1 legoConfig) throws Exception {
        var hzCnf = legoConfig.getHazelcast();
        if (hzCnf == null || hzCnf.getFile() == null || hzCnf.getFile().isBlank()) {
            return null;
        }
        var builder = new XmlConfigBuilder(hzCnf.getFile());
        if (hzCnf.getProps() != null) {
            builder.setProperties(hzCnf.getProps());
        }
        var memberAttributeConfig = new MemberAttributeConfig();
        memberAttributeConfig.setAttribute("__vertx.nodeId", UUIDUtils.timeBasedUuidAsString());
        var config = builder.build().setMemberAttributeConfig(memberAttributeConfig);
        return Hazelcast.newHazelcastInstance(config);
    }

    private static ClusterManager initClusterManager(LegoConfig1 config) throws Exception {
        var hz = initHazelcast(config);
        if (hz == null) {
            return null;
        }
        return new HazelcastClusterManager(hz);
    }

    protected static CompletionStage<Vertx> initVertx(LegoConfig1 config, MeterRegistry registry) throws Exception {
        // Vert.x 5.x: VertxOptions.setClusterManager() va MicrometerMetricsOptions.setMicrometerRegistry()
        // deu bi bo -- cluster manager va MeterRegistry ngoai (dung chung voi PrometheusMeterRegistry cua
        // getPrometheusMeterRegistry()) gio truyen qua VertxBuilder (withClusterManager/withMetrics).
        var options = new VertxOptions().setMetricsOptions(getMicrometerMetricsOptions());
        var clusterManager = initClusterManager(config);
        var builder = Vertx.builder().with(options).withMetrics(new MicrometerMetricsFactory(registry));
        if (clusterManager != null) {
            return builder.withClusterManager(clusterManager).buildClustered().toCompletionStage();
        }
        return CompletableFuture.completedStage(builder.build());
    }

    /**
     * Lay ra {@link HazelcastInstance} da duoc {@link #initClusterManager} tu tao (owner = app,
     * khong phai vertx-hazelcast -- xem {@code HazelcastClusterManager(HazelcastInstance)}: khi
     * duoc truyen instance co san nhu vay, no tu danh dau "customHazelcastCluster" va CO CHU DICH
     * KHONG goi {@code HazelcastInstance.shutdown()} trong leave() ("Do not shutdown the cluster
     * if we are not the owner." -- doc thang source), de danh trach nhiem do lai cho ai da tao ra
     * instance. Tuc la vertx.close() mot minh KHONG BAO GIO thuc su tat Hazelcast member trong setup
     * nay -- goi ham nay + {@link #shutdownHazelcastBounded} SAU vertx.close() de app tu hoan tat
     * not phan con lai, thay vi phu thuoc vao shutdown hook noi bo cua Hazelcast (da tat qua
     * property {@code hazelcast.shutdownhook.enabled=false}, xem hazelcast/helm -- neu khong tu
     * goi shutdown o day, member se khong bao gio leave cluster gracefully, chi bi halt() giet
     * cung, cluster phai tu phat hien "chet" qua heartbeat timeout).
     */
    public static HazelcastInstance getHazelcastInstance(Vertx vertx) {
        // Vert.x 5.x: package doi tu io.vertx.core.impl sang io.vertx.core.internal, method doi
        // tu getClusterManager() sang clusterManager() (bo tien to "get", khop quy uoc fluent chung).
        var clusterManager = ((VertxInternal) vertx).clusterManager();
        return clusterManager instanceof HazelcastClusterManager hcm ? hcm.getHazelcastInstance() : null;
    }

    /**
     * Tat HazelcastInstance ma app la owner (xem {@link #getHazelcastInstance}), bi chan boi timeout
     * de khong bao gio lam treo vo thoi han chuoi shutdown task cua {@code ThreadUtils.doShutdown()}
     * (van co {@code hazelcast.graceful.shutdown.max.wait=10} phia Hazelcast lam cot chinh, timeout
     * o day chi la lop phong ve them, dong bo voi cach vertx.close() dang duoc bao ve).
     */
    public static void shutdownHazelcastBounded(HazelcastInstance hazelcastInstance) {
        if (hazelcastInstance == null) {
            return;
        }
        CompletableFuture.runAsync(() -> hazelcastInstance.getLifecycleService().shutdown())
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
    }

    public static MicrometerMetricsOptions getMicrometerMetricsOptions() {
        var prometheusOptions = new VertxPrometheusOptions().setEnabled(true)
                .setStartEmbeddedServer(true)
                .setEmbeddedServerOptions(new HttpServerOptions().setPort(8081))
                .setEmbeddedServerEndpoint("/metrics");
        return new MicrometerMetricsOptions() //
                .setPrometheusOptions(prometheusOptions) //
                .setLabels(EnumSet.of(Label.EB_ADDRESS, Label.EB_SIDE, Label.EB_FAILURE, Label.HTTP_METHOD, Label.HTTP_ROUTE, Label.HTTP_CODE)) //
                .setDisabledMetricsCategories(Set.of(MetricsDomain.NAMED_POOLS.name(), MetricsDomain.DATAGRAM_SOCKET.name(), MetricsDomain.NET_CLIENT.name(), MetricsDomain.NET_SERVER.name())) //
                .setJvmMetricsEnabled(true) //
                .setEnabled(true);
    }

    public static PrometheusMeterRegistry getPrometheusMeterRegistry() {
        // Micrometer 1.10+/vertx-micrometer-metrics 5.x: package doi tu io.micrometer.prometheus
        // sang io.micrometer.prometheusmetrics, dung Prometheus client moi (io.prometheus.metrics.*,
        // thay io.prometheus.client.CollectorRegistry cu).
        PrometheusRegistry prometheusClientRegistry = PrometheusRegistry.defaultRegistry;
        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(
                        PrometheusConfig.DEFAULT, prometheusClientRegistry, Clock.SYSTEM);
        registry
                .config()
                .commonTags("app", "landlord")
                .meterFilter(
                        new MeterFilter() {
                            @Override
                            public DistributionStatisticConfig configure(
                                    Meter.Id id, DistributionStatisticConfig config) {
                                return DistributionStatisticConfig.builder()
                                        .percentiles(0.95, 0.99)
                                        .build()
                                        .merge(config);
                            }
                        });
        return registry;
    }

    private static String getFullPathResource(String path) {
        var url = LegoBootStart.class.getResource(path);
        assert url != null;
        return url.getFile();
    }
}

