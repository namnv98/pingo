package com.pingo.core.common.jdbcpool.supplier;

import com.pingo.core.common.jdbcpool.config.JdbcHostSpec;
import com.pingo.core.common.jdbcpool.host.JdbcHost;
import com.pingo.core.common.sql.SqlQueryMeta;
import com.pingo.core.common.support.Fulfilled;
import com.pingo.core.common.comp.AbstractLifeCycle;
import com.pingo.core.common.jdbcpool.config.JdbcConfig;
import io.vertx.core.Vertx;
//import io.vertx.pgclient.PgConnection;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnection;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.isNull;

/*
  Simple connection supplier without health checking.
  First host in PgConfig is master and other host is slaves.
  All readonly queries will go to slaves.
 */
class SteadyConnectionSupplier extends AbstractLifeCycle implements JdbcConnectionSupplier {

    private final AtomicInteger readonlyCursor = new AtomicInteger(0);

    private final @NonNull Vertx vertx;
    private final @NonNull JdbcConfig config;
    private final @NonNull JdbcHost master;
    private final @NonNull JdbcHost[] slaves;

    private final boolean readMaster;
    private final int readPreferedSize;

    private final int numSlaves;

    SteadyConnectionSupplier(JdbcConfig config, Vertx vertx) {
        var poolOptions = new PoolOptions()
            .setMaxSize(config.getMaxPoolSize())
            .setMaxWaitQueueSize(config.getMaxWaitQueueSize());
        var totalHosts = config.getHostSpecs().size();
        if (config.getMasterIndex() >= totalHosts) {
            throw new IndexOutOfBoundsException("master index from 0 to host's size");
        }
        var hostSpecs = config.getHostSpecs();
        if (hostSpecs.size() < 1)
            throw new IllegalArgumentException("HostSpec must be provided at least one");

        this.vertx = vertx;
        this.config = config;

        var masterIndex = config.getMasterIndex();
        this.master = initPgHost(hostSpecs.get(masterIndex), poolOptions);
        this.slaves = slaveHosts(poolOptions, totalHosts, hostSpecs, masterIndex);

        this.numSlaves = slaves.length;
        this.readMaster = true;
        this.readPreferedSize = numSlaves + (this.readMaster ? 1 : 0);
    }

    @Override
    public CompletionStage<SqlConnection> getConnection(SqlQueryMeta queryMeta) {
        if (numSlaves == 0 || !queryMeta.isReadonly())
            return master.getConnection();

        // optimize when only one slave configured
        if (!readMaster && numSlaves == 1) {
            return slaves[0].getConnection();
        }

        var index = readonlyCursor.accumulateAndGet(1, (curr, x) -> {
            var nextValue = curr + x;
            if (nextValue < readPreferedSize)
                return nextValue;
            return 0;
        });

        if (!readMaster)
            return slaves[index].getConnection();

        if (index == 0)
            return master.getConnection();

        return slaves[index - 1].getConnection();
    }

    @Override
    protected void doStop(CompletableFuture<Void> stopFuture) {
        var futures = new CompletableFuture[numSlaves + 1];
        futures[0] = master.stop();
        for (int i = 0; i < numSlaves; i++)
            futures[i + 1] = slaves[i].stop();

        CompletableFuture.allOf(futures) //
            .handle(Fulfilled::of) //
            .thenAccept(f -> f.forward(stopFuture));
    }

    private JdbcHost[] slaveHosts(PoolOptions poolOptions, int totalHosts, List<JdbcHostSpec> hostSpecs, int masterIndex) {
        var slaveHosts = new JdbcHost[totalHosts - 1];
        var slaveIndex = 0;
        for (int i = 0; i < hostSpecs.size(); i++) {
            if (i == masterIndex) {
                continue;
            }
            slaveHosts[slaveIndex++] = initPgHost(hostSpecs.get(i), poolOptions);
        }
        return slaveHosts;
    }

    private JdbcHost initPgHost(JdbcHostSpec spec, PoolOptions poolOptions) {
        int maxPoolSize = isNull(poolOptions) ? config.getMaxPoolSize() : poolOptions.getMaxSize();
        int maxWaitQueueSize = isNull(poolOptions) ? config.getMaxWaitQueueSize() : poolOptions.getMaxWaitQueueSize();

        var builder = JdbcHost.builder() //
            .vertx(vertx) //
            .host(spec.getHost()) //
            .port(spec.getPort()) //
            .user(config.getUser()) //
            .databaseType(config.getDatabaseType())
            .password(config.getPassword()) //
            .database(config.getDatabase()) //
            .maxPoolSize(maxPoolSize) //
            .maxWaitQueueSize(maxWaitQueueSize);
        var isSsl = config.isSsl();
        if (isSsl) {
            builder
                .ssl(isSsl)
                .trustAll(config.isTrustAll());
            var pemCaPath = config.getPemRootCaPath();
            if (StringUtils.isNoneEmpty(pemCaPath)) {
                builder.pemRootCaPath(pemCaPath);
            } else {
                var pemCa = config.getPemRootCa();
                if (StringUtils.isNoneEmpty(pemCa)) {
                    builder.pemRootCa(pemCa);
                }
            }
        }
        builder.properties(config.getConnectionProperties());
        return builder.build();
    }

}
