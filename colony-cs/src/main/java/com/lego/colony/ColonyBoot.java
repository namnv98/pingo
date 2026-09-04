package com.lego.colony;

import static java.util.Objects.isNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.lego.namnv.core.boot.start.LegoBootStart;
import com.lego.namnv.core.boot.start.LegoConfig1;
import com.lego.namnv.core.common.json.LegoJsonModule;
import com.lego.namnv.core.common.support.ThreadUtils;
import io.vertx.core.json.jackson.DatabindCodec;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class ColonyBoot extends LegoBootStart {

  public static final String CONFIG_FILE_ENV = "CONFIG_FILE";
  private static final String dir = System.getProperty("user.dir");
  private static final String DEFAULT_APP_CONFIG =
      dir + "/colony/src/main/resources/app.yaml";

  public static void main(String[] args) {
    var mappers = new ObjectMapper[] {DatabindCodec.mapper(), DatabindCodec.prettyMapper()};
    LegoJsonModule.registerAllWith(mappers);

    var config =
        loadConfig(
            StringUtils.defaultIfBlank(System.getenv(CONFIG_FILE_ENV), DEFAULT_APP_CONFIG),
            LegoConfig1.class);
    if (isNull(System.getenv(CONFIG_FILE_ENV))) {
      var hazelPath = dir + "/" + config.getHazelcast().getFile();
      config =
          config.toBuilder()
              .hazelcast(config.getHazelcast().toBuilder().file(hazelPath).build())
              .build();
    }

    ColonyApp app = null;
    var meterRegistry = getPrometheusMeterRegistry();
    try {
      var vertx = initVertx(config, meterRegistry).toCompletableFuture().get();
      var hazelcastInstance = getHazelcastInstance(vertx);
      var injector = Guice.createInjector(new ColonyAppModule(vertx, config));
      app = injector.getInstance(ColonyApp.class);
      app.startSync();
      ColonyApp finalApp = app;
      ThreadUtils.registerShutdownTask(
          () -> {
            finalApp.stopSync();
            shutdownHazelcastBounded(hazelcastInstance);
          });
      log.info("*********** colony started successfully, cheer!!!");
    } catch (Throwable e) {
      log.error("*********** colony starting failed, exit!!!", e);
      try {
        if (Objects.nonNull(app)) app.stopSync();
      } catch (Exception e1) {
        log.error("*********** failed to stop colony cleanly after startup failure", e1);
      }
      ThreadUtils.sleep(1000);
      System.exit(1);
    }
  }
}
