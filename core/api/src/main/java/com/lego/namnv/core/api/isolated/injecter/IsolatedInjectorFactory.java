package com.lego.namnv.core.api.isolated.injecter;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.inject.Injector;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import org.checkerframework.checker.index.qual.NonNegative;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface IsolatedInjectorFactory {

    CompletionStage<Injector> getIsolatedInjector(UUID orgId);

    default IsolatedInjectorFactory enableCache(Duration maxIdleTime) {
        return new CachedOrgInjectorFactory(this, maxIdleTime);
    }
}

class CachedOrgInjectorFactory implements IsolatedInjectorFactory {

    private final @NonNull IsolatedInjectorFactory source;
    private final AsyncCache<UUID, Injector> cache;

    CachedOrgInjectorFactory(@NonNull IsolatedInjectorFactory source, Duration maxIdleTime) {
        this.source = source;
        this.cache = Caffeine.newBuilder() //
            .expireAfterWrite(maxIdleTime)
            .evictionListener(this::onOrgInjectorRemoved)//
            .buildAsync();
    }

    private void onOrgInjectorRemoved(@Nullable UUID key, @Nullable Injector value, RemovalCause cause) {
        var emitter = value.getInstance(OrgHaltEmitter.class);
        if (emitter == null) {
            return;
        }
        emitter.emitThenClear();
    }

    @Override
    public CompletionStage<Injector> getIsolatedInjector(UUID orgId) {
        return cache.get(orgId, (k, e) -> source.getIsolatedInjector(k).toCompletableFuture());
    }

    @Override
    public IsolatedInjectorFactory enableCache(Duration maxIdleTime) {
        return new CachedOrgInjectorFactory(source, maxIdleTime);
    }

    @AllArgsConstructor
    private static class MaxIdleExpiry implements Expiry<UUID, Injector> {

        private final long maxIdleMillis;

        @Override
        public long expireAfterCreate(UUID key, Injector value, long currentTime) {
            return maxIdleMillis;
        }

        @Override
        public long expireAfterUpdate(UUID key, Injector value, long currentTime, @NonNegative long currentDuration) {
            return maxIdleMillis;
        }

        @Override
        public long expireAfterRead(UUID key, Injector value, long currentTime, @NonNegative long currentDuration) {
            return maxIdleMillis;
        }
    }
}
