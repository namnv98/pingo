package com.pingo.chat.domain.presence;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryExpiredListener;
import com.hazelcast.multimap.MultiMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Theo dõi user nào đang online (có ít nhất 1 session WebSocket sống ở BẤT KỲ pod harbor nào) —
 * dùng 1 {@code MultiMap} Hazelcast dùng chung toàn cluster ({@code userId -> tập sessionId}), vì
 * harbor là nơi DUY NHẤT biết chính xác quan hệ 1-1 (session, userId) — từ khi harbor↔colony
 * chuyển sang stream dùng chung (xem ARCHITECTURE.md mục 12), {@code ChatSession} bên colony không
 * còn field userId nữa, không thể tự trả lời "user X có đang online không".
 *
 * <p>Chỉ HARBOR được ghi ({@link #markOnline}/{@link #markOffline}, lúc AUTH_OK/session đóng) —
 * colony/herald CHỈ ĐỌC ({@link #isOnline}), không ai khác ghi. Dùng {@code MultiMap} (không phải
 * {@code Map<userId, count>}) để tự xử lý đúng trường hợp 1 user mở nhiều tab/thiết bị cùng lúc —
 * chỉ thật sự offline khi tập session rỗng.
 *
 * <p><b>Chống rò rỉ entry khi pod harbor chết đột ngột</b> (OOMKilled, crash cứng — không đi qua
 * {@code onClose}/{@link #markOffline}, không giống đóng WS gọn gàng): {@code MultiMap} không có
 * TTL theo từng entry, nên nếu chỉ dùng nó thì 1 session "chết không kèn không trống" sẽ kẹt lại
 * mãi mãi trong {@code onlineSessions}, khiến user đó bị coi là online vĩnh viễn. Dùng thêm 1
 * {@code IMap} phụ ({@code sessionHeartbeats}, key = sessionId, value = userId) có TTL thật per-entry
 * ({@link #HEARTBEAT_TTL_SECONDS}), được "touch" lại định kỳ bởi harbor ({@link #touch}) — khớp đúng
 * chu kỳ {@code HarborSessionManager#heartbeatSweep} đã có sẵn, không cần thêm cơ chế polling mới.
 * Khi entry đó tự hết hạn (nghĩa là harbor đã ngừng touch — dấu hiệu session/pod đã chết), listener
 * {@link #onSessionExpired} tự dọn luôn entry tương ứng bên {@code onlineSessions}.
 */
public class PresenceRegistry {

  private static final String MULTIMAP_NAME = "online_sessions";
  private static final String HEARTBEAT_MAP_NAME = "online_session_heartbeats";
  /** 3x chu kỳ {@code HEARTBEAT_SWEEP_INTERVAL_MS} (15s) bên harbor — đủ dung sai cho 1-2 nhịp touch bị trễ/mất mà không báo offline oan. */
  private static final long HEARTBEAT_TTL_SECONDS = 45;

  private final MultiMap<String, String> onlineSessions;
  private final IMap<String, String> sessionHeartbeats;

  public PresenceRegistry(HazelcastInstance hazelcastInstance) {
    this.onlineSessions = hazelcastInstance.getMultiMap(MULTIMAP_NAME);
    this.sessionHeartbeats = hazelcastInstance.getMap(HEARTBEAT_MAP_NAME);
    this.sessionHeartbeats.addEntryListener((EntryExpiredListener<String, String>) this::onSessionExpired, true);
  }

  public void markOnline(UUID userId, String sessionId) {
    onlineSessions.put(userId.toString(), sessionId);
    touch(userId, sessionId);
  }

  public void markOffline(UUID userId, String sessionId) {
    onlineSessions.remove(userId.toString(), sessionId);
    sessionHeartbeats.remove(sessionId);
  }

  /**
   * Gọi định kỳ (mỗi nhịp {@code heartbeatSweep} bên harbor) cho MỌI session đã AUTH_OK còn sống —
   * làm mới TTL để entry không hết hạn trong lúc session vẫn còn sống thật.
   */
  public void touch(UUID userId, String sessionId) {
    sessionHeartbeats.put(sessionId, userId.toString(), HEARTBEAT_TTL_SECONDS, TimeUnit.SECONDS);
  }

  public boolean isOnline(UUID userId) {
    return !onlineSessions.get(userId.toString()).isEmpty();
  }

  private void onSessionExpired(com.hazelcast.core.EntryEvent<String, String> event) {
    var sessionId = event.getKey();
    var userId = event.getOldValue();
    if (userId != null) {
      onlineSessions.remove(userId, sessionId);
    }
  }
}
