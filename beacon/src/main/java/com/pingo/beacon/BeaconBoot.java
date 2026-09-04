package com.pingo.beacon;

import static java.util.Objects.isNull;

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

@Slf4j
public class BeaconBoot extends LegoBootStart {

  public static final String CONFIG_FILE_ENV = "CONFIG_FILE";
  private static final String dir = System.getProperty("user.dir");
  private static final String DEFAULT_APP_CONFIG = dir + "/beacon/src/main/resources/app.yaml";

  public static void main(String[] args) {
    var mappers = new ObjectMapper[] {DatabindCodec.mapper(), DatabindCodec.prettyMapper()};
    LegoJsonModule.registerAllWith(mappers);

    var config = loadConfig(StringUtils.defaultIfBlank(System.getenv(CONFIG_FILE_ENV), DEFAULT_APP_CONFIG), LegoConfig1.class);
    if (isNull(System.getenv(CONFIG_FILE_ENV))) {
      var hazelPath = dir + "/" + config.getHazelcast().getFile();
      config = config.toBuilder().hazelcast(config.getHazelcast().toBuilder().file(hazelPath).build()).build();
    }

    BeaconApp app = null;
    var meterRegistry = getPrometheusMeterRegistry();
    try {
      var vertx = initVertx(config, meterRegistry).toCompletableFuture().get();
      var hazelcastInstance = getHazelcastInstance(vertx);
      var injector = Guice.createInjector(new BeaconAppModule(vertx));
      app = injector.getInstance(BeaconApp.class);
      app.startSync();
      BeaconApp finalApp = app;
      ThreadUtils.registerShutdownTask(
          () -> {
            finalApp.stopSync();
            shutdownHazelcastBounded(hazelcastInstance);
          });
      log.info("*********** beacon started successfully, cheer!!!");
    } catch (Throwable e) {
      log.error("*********** beacon starting failed, exit!!!", e);
      try {
        if (Objects.nonNull(app)) app.stopSync();
      } catch (Exception e1) {
        log.error("*********** failed to stop beacon app cleanly after startup failure", e1);
      }
      ThreadUtils.sleep(1000);
      System.exit(1);
    }
  }
}
