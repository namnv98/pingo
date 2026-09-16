package com.pingo.chat.domain.e2e;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import java.util.UUID;

/**
 * Cache Hazelcast (đồng bộ, dùng chung cluster với beacon/colony/harbor/hall -- xem {@code
 * PresenceRegistry} cho cùng 1 pattern) trả lời NGAY "deviceId này còn sống hay đã bị thu hồi" --
 * dùng ở MỌI đường verify JWT ({@code HallApiHandlers#resolveUserId}, {@code
 * HarborSessionManager#handleAuth}) TRƯỚC khi cho request đi tiếp.
 *
 * <p>Lý do cần cache riêng, không tra thẳng Postgres mỗi request: {@code
 * requireAuthenticatedUserId} hiện là hàm ĐỒNG BỘ, gọi ở ĐẦU gần như mọi handler (hàng chục chỗ) --
 * đổi nó thành bất đồng bộ để await 1 query DB sẽ là refactor xuyên suốt cả file, rủi ro cao. IMap
 * Hazelcast cho phép giữ nguyên chữ ký đồng bộ, độ trễ đọc gần như 0 (network round-trip trong cùng
 * cluster, thường dưới 1ms) mà vẫn thấy được thay đổi từ MỌI pod ngay lập tức (không phải TTL/eventually
 * consistent) -- {@link MlsDeviceRegistry} (Postgres) mới là nguồn thật (durable), IMap chỉ là cache
 * suy ra từ đó, được hydrate lại lúc app khởi động (xem {@code HallAppModule}/{@code
 * HarborAppModule}) để không mất trạng thái thu hồi nếu CẢ cụm Hazelcast khởi động lại cùng lúc.
 */
public class RevokedDeviceRegistry {

  private static final String MAP_NAME = "mls_revoked_devices";

  private final IMap<String, Boolean> revoked;

  public RevokedDeviceRegistry(HazelcastInstance hazelcastInstance) {
    this.revoked = hazelcastInstance.getMap(MAP_NAME);
  }

  public void markRevoked(UUID deviceId) {
    revoked.put(deviceId.toString(), Boolean.TRUE);
  }

  public boolean isRevoked(UUID deviceId) {
    return deviceId != null && revoked.containsKey(deviceId.toString());
  }
}
