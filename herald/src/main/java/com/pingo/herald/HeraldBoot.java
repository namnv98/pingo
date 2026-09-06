package com.pingo.herald;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.pingo.core.boot.start.LegoBootStart;
import com.pingo.core.boot.start.LegoConfig1;
import com.pingo.core.common.json.LegoJsonModule;
import com.pingo.core.common.support.ThreadUtils;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * CÓ cluster Hazelcast (set {@code hazelcast:} trong config) — cần để nhận broadcast từ colony
 * (EventBus) và đọc {@code PresenceRegistry} (MultiMap Hazelcast dùng chung toàn cluster), cùng lý
 * do hall phải chuyển sang cluster (xem HallBoot).
 */
@Slf4j
public class HeraldBoot extends LegoBootStart {

  public static final String CONFIG_FILE_ENV = "CONFIG_FILE";
  private static final String dir = System.getProperty("user.dir");
  private static final String DEFAULT_APP_CONFIG = dir + "/herald/src/main/resources/app.yaml";

  public static void main(String[] args) {
    // Vert.x 5.x bo DatabindCodec.prettyMapper() (chi con 1 mapper dung chung), khong con
    // 2 instance rieng nua.
    var mappers = new ObjectMapper[] {DatabindCodec.mapper()};
    LegoJsonModule.registerAllWith(mappers);

    var config = loadConfig(StringUtils.defaultIfBlank(System.getenv(CONFIG_FILE_ENV), DEFAULT_APP_CONFIG), LegoConfig1.class);

    HeraldApp app = null;
    var meterRegistry = getPrometheusMeterRegistry();
    try {
      var vertx = initVertx(config, meterRegistry).toCompletableFuture().get();
      var hazelcastInstance = getHazelcastInstance(vertx);
      var injector = Guice.createInjector(new HeraldAppModule(vertx, config, hazelcastInstance));
      app = injector.getInstance(HeraldApp.class);
      app.startSync();
      HeraldApp finalApp = app;
      ThreadUtils.registerShutdownTask(
          () -> {
            finalApp.stopSync();
            shutdownHazelcastBounded(hazelcastInstance);
          });
      log.info("*********** herald started successfully, cheer!!!");
    } catch (Throwable e) {
      log.error("*********** herald starting failed, exit!!!", e);
      try {
        if (Objects.nonNull(app)) app.stopSync();
      } catch (Exception e1) {
        log.error("*********** failed to stop herald cleanly after startup failure", e1);
      }
      ThreadUtils.sleep(1000);
      System.exit(1);
    }
  }
}
