package com.lego.beacon.discovery;

import com.lego.namnv.core.common.comp.AbstractLifeCycle;
import com.lego.namnv.core.common.support.Disposable;
import com.lego.namnv.discovery.router.Destination;
import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.EventEmitter;

import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * Keeper chạy local, không gọi K8s API — trả về danh sách destination cố định
 * được truyền vào lúc khởi tạo. Dùng cho môi trường dev/test.
 */
@Slf4j
class LocalKeeper extends AbstractLifeCycle implements Keeper {

    private final List<Destination> destinations;
    private final EventEmitter eventEmitter = EventEmitter.newEmitter();

    LocalKeeper(List<Destination> destinations) {
        this.destinations = List.copyOf(destinations);
    }

    @Override
    protected void doStart() {
        log.info("LocalKeeper started with {} static destination(s): {}",
                destinations.size(), destinations);
    }

    @Override
    protected void doStop() {
        // no-op — không có resource nào cần dọn (không watch thread, không k8s client)
    }

    @Override
    public Disposable subscribe(EventConsumer consumer) {
        return eventEmitter.subscribe(consumer);
    }

    @Override
    public List<Destination> getAll() {
        return destinations;
    }
}