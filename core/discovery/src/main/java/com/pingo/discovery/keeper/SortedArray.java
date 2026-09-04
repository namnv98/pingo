package com.pingo.discovery.keeper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SortedArray<T> {

  private final Comparator<T> comparator;
  // Ghi (add/remove/clear) từ 1 thread, đọc (unmodifiableValues/contains) có thể từ thread khác
  // (vd: DefaultPingoConnector.startWatchPre chạy trên raw Thread riêng trong khi VersionVector
  // được cập nhật từ Vert.x event-loop) — cần volatile để đảm bảo memory visibility giữa các thread.
  private volatile List<T> data;

  public SortedArray(Comparator<T> comparator) {
    this.comparator = comparator;
    this.data = List.of();
  }

  public List<T> unmodifiableValues() {
    return data;
  }

  public boolean add(T value) {
    if (value == null) {
      return false;
    }
    var currentSize = data.size();
    if (currentSize == 0) {
      this.data = List.of(value);
      return true;
    }
    var newData = new ArrayList<T>(currentSize + 1);
    int i = 0;
    while (i < currentSize) {
      var cur = data.get(i);
      var comp = comparator.compare(cur, value);
      if (comp > 0) {
        break;
      }
      newData.add(cur);
      ++i;
    }
    newData.add(value);
    while (i < currentSize) newData.add(data.get(i++));
    this.data = List.copyOf(newData);
    return true;
  }

  public boolean remove(T value) {
    if (value == null) {
      return false;
    }
    var currentSize = data.size();
    if (currentSize == 0) {
      return false;
    }

    var res = false;
    var newData = new ArrayList<T>(currentSize);
    for (var it : data) {
      if (comparator.compare(it, value) == 0) {
        res = true;
      } else {
        newData.add(it);
      }
    }
    this.data = List.copyOf(newData);
    return res;
  }

  public void clear() {
    this.data = List.of();
  }

  public boolean contains(T t) {
    return this.data.contains(t);
  }
}
