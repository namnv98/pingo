package com.pingo.discovery.keeper;

import com.pingo.core.common.comp.AbstractLifeCycle;
import com.pingo.core.common.support.Disposable;
import com.pingo.discovery.router.Destination;
import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.EventEmitter;

import java.util.*;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory snapshot của destination table cho MỘT version cụ thể — mỗi lần {@code VersionVector}
 * bump version mới, nó tạo một SnapshotKeeper mới, seed lại state từ version trước đó (nếu có) rồi
 * áp thêm các thay đổi (add/remove) của version mới. Không tự gọi k8s API, không có thread nào cả —
 * chỉ giữ state + phát event khi state đổi. (Tên cũ "K8SKeeper" dễ gây hiểu lầm với
 * {@code beacon}'s K8SKeeper — class DUY NHẤT thật sự gọi k8s API để watch pod.)
 */
@Slf4j
public class SnapshotKeeper extends AbstractLifeCycle implements Keeper {

  private final SortedArray<Destination> pods =
      new SortedArray<>(Comparator.comparing(Destination::name));
  private final EventEmitter eventEmitter = EventEmitter.newEmitter();

  @Override
  public Disposable subscribe(EventConsumer consumer) {
    return eventEmitter.subscribe(consumer);
  }

  public synchronized void addDestinationChangeEvent(
      List<DestinationChangeEvent> destinationChangeEvents) {
    destinationChangeEvents.forEach(this::onDestinationChangeEvent);
  }

  @Override
  public List<Destination> listingDestinations() {
    return pods.unmodifiableValues();
  }

  @Override
  public void addDestinations(List<Destination> destinations) {
    destinations.forEach(this::upsert);
  }

  private void onDestinationChangeEvent(DestinationChangeEvent item) {
    switch (item.getChangeType()) {
      case ADD -> upsert(item.getDestination());
      case REMOVE -> removePod(item.getDestination());
    }
  }

  /**
   * Thêm mới hoặc thay thế (nếu đã có, ví dụ pod cùng tên nhưng đổi IP): remove bản cũ trước
   * (nếu có) rồi add bản mới, mỗi bước chỉ dispatch event khi thật sự có thay đổi.
   */
  private void upsert(Destination destination) {
    // Trước đây gọi pods.contains(destination) rồi mới gọi pods.remove(destination) — cả 2 đều
    // O(n) quét toàn bộ danh sách để tìm phần tử trùng, tức quét 2 lần cho cùng 1 việc. remove()
    // đã tự trả về true/false cho biết có tồn tại hay không, nên bỏ hẳn bước contains() thừa.
    if (pods.remove(destination)) {
      eventEmitter.dispatch(new DestinationChangeEvent(ChangeType.REMOVE, destination));
    }
    if (pods.add(destination)) {
      eventEmitter.dispatch(new DestinationChangeEvent(ChangeType.ADD, destination));
      log.debug("add [pod={}]", destination);
    }
  }

  private void removePod(Destination destination) {
    var target = Destination.of(destination.name());
    if (pods.remove(target)) {
      eventEmitter.dispatch(new DestinationChangeEvent(ChangeType.REMOVE, target));
      log.debug("remove [pod={}]", target);
    }
  }
}
