package com.pingo.hall;

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
 * KHÔNG cluster Hazelcast (không set {@code hazelcast:} trong config) — {@code LegoBootStart#initVertx}
 * tự nhận ra và fallback về {@code Vertx.vertx(options)} thuần, xem javadoc của nó.
 */
@Slf4j
public class HallBoot extends LegoBootStart {

  public static final String CONFIG_FILE_ENV = "CONFIG_FILE";
  private static final String dir = System.getProperty("user.dir");
  private static final String DEFAULT_APP_CONFIG = dir + "/hall/src/main/resources/app.yaml";

  public static void main(String[] args) {
    var mappers = new ObjectMapper[] {DatabindCodec.mapper(), DatabindCodec.prettyMapper()};
    LegoJsonModule.registerAllWith(mappers);

    var config = loadConfig(StringUtils.defaultIfBlank(System.getenv(CONFIG_FILE_ENV), DEFAULT_APP_CONFIG), LegoConfig1.class);

    HallApp app = null;
    var meterRegistry = getPrometheusMeterRegistry();
    try {
      var vertx = initVertx(config, meterRegistry).toCompletableFuture().get();
      var injector = Guice.createInjector(new HallAppModule(vertx, config));
      app = injector.getInstance(HallApp.class);
      app.startSync();
      HallApp finalApp = app;
      ThreadUtils.registerShutdownTask(finalApp::stopSync);
      log.info("*********** hall started successfully, cheer!!!");
    } catch (Throwable e) {
      log.error("*********** hall starting failed, exit!!!", e);
      try {
        if (Objects.nonNull(app)) app.stopSync();
      } catch (Exception e1) {
        log.error("*********** failed to stop hall cleanly after startup failure", e1);
      }
      ThreadUtils.sleep(1000);
      System.exit(1);
    }
  }
}
