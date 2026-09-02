package com.lego.namnv.connector;

import java.nio.ByteBuffer;
import java.util.UUID;
import lombok.Getter;
import net.jpountz.xxhash.XXHashFactory;

/** Implementation detail of {@link RouteByUserIdRequest#hash()} — not part of this module's public API. */
class UUIDsHashHelper {

  @Getter private static final UUIDsHashHelper instance = new UUIDsHashHelper();

  private final XXHashFactory factory = XXHashFactory.fastestInstance();
  private ThreadLocal<ByteBuffer> byteBufferLocal =
      ThreadLocal.withInitial(() -> ByteBuffer.allocate(Long.BYTES * 4));

  public int hash(UUID i1, UUID i2) {
    var buffer = byteBufferLocal.get().clear();
    buffer.putLong(i1.getMostSignificantBits());
    buffer.putLong(i1.getLeastSignificantBits());
    buffer.putLong(i2.getMostSignificantBits());
    buffer.putLong(i2.getLeastSignificantBits());
    return hash(buffer.array());
  }

  public int hash(UUID i1) {
    var buffer = byteBufferLocal.get().clear();
    buffer.putLong(i1.getMostSignificantBits());
    buffer.putLong(i1.getLeastSignificantBits());
    return hash(buffer.array());
  }

  private int hash(byte[] bytes) {
    var xxHash32 = factory.newStreamingHash32(0x9747b28c);
    xxHash32.update(bytes, 0, bytes.length);
    return xxHash32.getValue();
  }
}
