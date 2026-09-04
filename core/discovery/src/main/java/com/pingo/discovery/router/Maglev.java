package com.pingo.discovery.router;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.jpountz.xxhash.XXHashFactory;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;

@Slf4j
class Maglev implements Serializable {
  @Serial private static final long serialVersionUID = -601215139619845983L;

  private static final XXHashFactory factory = XXHashFactory.fastestInstance();

  private final Object lock;
  private final int lookupTableSize;
  private final List<Entry> pool;
  private final HashFunc hash1;
  private final HashFunc hash2;

  @Getter(value = AccessLevel.PACKAGE)
  private Destination[] lookupTable;

  Maglev(int lookupTableSize) {
    this(lookupTableSize, Maglev::xxHash, String::hashCode);
  }

  Maglev(int lookupTableSize, HashFunc hash1, HashFunc hash2) {
    this.lock = new Object();
    if (lookupTableSize <= 0 || !isPrime(lookupTableSize)) {
      throw new IllegalArgumentException("Lookup table size must be prime number");
    }
    this.lookupTableSize = lookupTableSize;
    this.hash1 = hash1;
    this.hash2 = hash2;
    this.lookupTable = new Destination[this.lookupTableSize];
    Arrays.fill(this.lookupTable, null);
    this.pool = new LinkedList<>();
  }

  private static int xxHash(String value) {
    var bytes = value.getBytes();
    var xxHash32 = factory.newStreamingHash32(0x9747b28c);
    xxHash32.update(bytes, 0, bytes.length);
    return xxHash32.getValue();
  }

  public Destination route(RoutingKey key) {
    return this.lookupTable[positiveMod(key.hash())];
  }

  public void addDestination(Destination value) {
    synchronized (this.lock) {
      this.pool.add(toEntry(value));
      populateLookupTable();
    }
  }

  public void removeDestination(Destination value) {
    synchronized (this.lock) {
      if (this.pool.removeIf(e -> e.destination.equals(value))) {
        populateLookupTable();
      }
    }
  }

  private void populateLookupTable() {
    var poolSize = this.pool.size();
    int[] next = new int[poolSize];
    Arrays.fill(next, 0);
    var newLookupTable = new Destination[this.lookupTableSize];
    Arrays.fill(newLookupTable, null);
    if (poolSize == 0) {
      this.lookupTable = newLookupTable;
      return;
    }
    int n = 0;
    int i;
    Iterator<Entry> it;
    while (true) {
      i = 0;
      it = this.pool.iterator();
      while (it.hasNext()) {
        var entry = it.next();
        var ii = next[i];
        var c = entry.permutation.get(ii);
        while (newLookupTable[c] != null) {
          next[i] = next[i] + 1;
          c = entry.permutation.get(next[i]);
        }
        newLookupTable[c] = entry.destination;
        next[i] = next[i] + 1;
        n = n + 1;
        if (n == this.lookupTableSize) {
          this.lookupTable = newLookupTable;
          return;
        }
        ++i;
      }
    }
  }

  private Entry toEntry(Destination destination) {
    return new Entry(destination, permutation(destination));
  }

  private ArrayList<Integer> permutation(Destination destination) {
    var permutation = new ArrayList<Integer>(this.lookupTableSize);
    var offset = positiveMod(hash1.hash(destination.name()));
    var skip = positiveMod(hash2.hash(destination.name()), this.lookupTableSize - 1) + 1;
    for (int j = 0; j < this.lookupTableSize; j++) {
      permutation.add(positiveMod(offset + j * skip));
    }
    var distinctSize = permutation.stream().distinct().count();
    if (distinctSize != this.lookupTableSize) {
      log.error(
          "permutation generation failed: [offset={}], [skip={}], [name={}]",
          offset,
          skip,
          destination.name());
    }
    return permutation;
  }

  private int positiveMod(int value) {
    return positiveMod(value, this.lookupTableSize);
  }

  private int positiveMod(int value, int mod) {
    if (value < 0) {
      value = -(value - 1);
    }
    return value % mod;
  }

  private boolean isPrime(int number) {
    double upper = Math.sqrt(number);
    for (int i = 2; i < upper; ++i) {
      if (number % i == 0) {
        return false;
      }
    }
    return true;
  }

  record Entry(Destination destination, ArrayList<Integer> permutation) {}
}
