package com.pingo.herald;

import com.pingo.chat.domain.notification.NotificationRegistry;
import com.pingo.chat.domain.presence.PresenceRegistry;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.herald.push.PushService;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Nhận broadcast "1 tin nhắn vừa gửi tới conversation X, đây là danh sách member (trừ người gửi)"
 * từ MỌI pod colony (xem {@code ChatSessionManager#publishNotificationCandidates}) — colony không
 * tự lọc online/offline (không có {@link PresenceRegistry} riêng, tránh thêm phụ thuộc Hazelcast
 * vào đường xử lý tin nhắn nóng), việc lọc dồn hết về đây.
 *
 * <p><b>"Online" (còn socket WS mở) KHÁC "đang thực sự xem"</b> — máy khoá màn hình/tab chạy nền
 * vẫn giữ socket sống (OS/browser tự trả PONG ngầm), khiến {@link PresenceRegistry#isOnline} trả
 * {@code true} dù không ai nhìn màn hình, kết quả là những user đó KHÔNG BAO GIỜ nhận noti dù thực
 * ra đã bỏ lỡ tin. Vì vậy: candidate OFFLINE lưu ngay như cũ; candidate ONLINE KHÔNG bỏ qua hẳn
 * nữa mà cho 1 khoảng ân hạn {@link #GRACE_MS} — nếu trong lúc đó có {@link #onReadAck} thật từ
 * đúng session đó (client xác nhận đã render tin ra màn hình, xem {@code MessageType#READ} bên
 * harbor) thì huỷ, coi như đã xem; hết hạn mà chưa có ACK thì coi như "mở máy nhưng không xem",
 * vẫn lưu/push noti như candidate offline. Khác cách bản cũ (lego-new) làm: KHÔNG đoán bằng đồng
 * hồ đơn thuần — chỉ huỷ khi có ACK thật, không có state machine mập mờ không ai xác nhận được.
 *
 * <p>Timer chờ ân hạn chỉ giữ trong bộ nhớ tiến trình (không bền) — nếu pod herald này chết đúng
 * lúc đang chờ, timer mất theo, candidate đó không được lưu noti (dù đáng lẽ phải lưu sau khi hết
 * hạn). Chấp nhận được vì cùng mức best-effort với phần còn lại của module này (xem
 * {@code ChatSessionManager#publishNotificationCandidates}); muốn triệt để hơn thì cần persist
 * trạng thái "đang chờ ACK" (vd outbox pattern) thay vì timer thuần trong RAM.
 *
 * <p><b>Gộp push theo {@link #PUSH_DEBOUNCE_MS}</b> ({@link #queuePush}/{@link #flushPush}): nhiều
 * tin đến dồn dập cho CÙNG 1 user (vd offline lâu, quay lại thấy dồn) chỉ tạo ra 1 lần gọi Firebase
 * duy nhất thay vì 1 lần/tin — tránh "rung điện thoại N lần liên tiếp". Notification vẫn được LƯU
 * NGAY từng dòng riêng vào DB (không gộp, không mất dữ liệu nếu herald chết giữa chừng — chỉ có
 * bước GỬI PUSH mới gộp/trễ, xem {@code GET /notifications} luôn thấy đủ dù push có gộp hay không).
 */
@Slf4j
public class NotificationConsumer {

  /** PHẢI khớp {@code ChatSessionManager#NOTIFY_CANDIDATES_ADDRESS} bên colony. */
  private static final String NOTIFY_CANDIDATES_ADDRESS = "message_notify_candidates";
  /** PHẢI khớp {@code HarborSessionManager#READ_ACK_ADDRESS} bên harbor. */
  private static final String READ_ACK_ADDRESS = "message_read_ack";
  /** Đủ ngắn để noti không trễ quá lâu, đủ dài để không phiền người vừa mới thấy tin và sắp gửi READ. */
  private static final long GRACE_MS = 8_000;
  /** Cửa sổ gộp nhiều notification liên tiếp của CÙNG 1 user thành 1 lần gọi Firebase (xem javadoc lớp). */
  private static final long PUSH_DEBOUNCE_MS = 3_000;

  private final Vertx vertx;
  private final PresenceRegistry presence;
  private final NotificationRegistry notifications;
  private final PushService pushService;
  /** key = {@link #pendingKey} -&gt; id timer đang chờ hết hạn (xem {@link #onCandidates}/{@link #onReadAck}). */
  private final ConcurrentHashMap<String, Long> pendingGraceTimers = new ConcurrentHashMap<>();
  /** userId -&gt; các notification đang chờ gộp gửi push (xem {@link #queuePush}/{@link #flushPush}). */
  private final ConcurrentHashMap<UUID, List<PendingPush>> pendingPushesByUser = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> pendingPushTimerByUser = new ConcurrentHashMap<>();

  private record PendingPush(String bodyPreview, UUID conversationId) {}

  public NotificationConsumer(
      Vertx vertx, PresenceRegistry presence, NotificationRegistry notifications, PushService pushService) {
    this.vertx = vertx;
    this.presence = presence;
    this.notifications = notifications;
    this.pushService = pushService;
    vertx.eventBus().consumer(NOTIFY_CANDIDATES_ADDRESS, this::onCandidates);
    vertx.eventBus().consumer(READ_ACK_ADDRESS, this::onReadAck);
  }

  private void onCandidates(Message<JsonObject> message) {
    var body = message.body();
    var conversationId = UUIDUtils.parseOrDefault(body.getString("conversationId"));
    var fromUserId = UUIDUtils.parseOrDefault(body.getString("fromUserId"));
    var candidateUserIds = body.getJsonArray("candidateUserIds");
    if (conversationId == null || fromUserId == null || candidateUserIds == null) {
      return;
    }
    var bodyPreview = body.getString("bodyPreview");
    var messageId = body.getString("messageId");
    var ts = body.getLong("ts", System.currentTimeMillis());

    for (var raw : candidateUserIds) {
      var userId = UUIDUtils.parseOrDefault(String.valueOf(raw));
      if (userId == null) {
        continue;
      }
      if (!presence.isOnline(userId)) {
        persistNotification(userId, conversationId, fromUserId, bodyPreview, ts);
        continue;
      }
      if (messageId == null) {
        continue; // khong co gi de doi chieu READ -- bo qua nhu truoc, con hon la khong bao gio huy duoc
      }
      var key = pendingKey(userId, messageId);
      var timerId = vertx.setTimer(
          GRACE_MS,
          tid -> {
            pendingGraceTimers.remove(key);
            persistNotification(userId, conversationId, fromUserId, bodyPreview, ts);
          });
      pendingGraceTimers.put(key, timerId);
    }
  }

  /** Client (qua harbor) xác nhận đã thực sự xem 1 tin -- huỷ noti đang chờ ân hạn cho đúng (userId, messageId) đó nếu còn. */
  private void onReadAck(Message<JsonObject> message) {
    var body = message.body();
    var userId = UUIDUtils.parseOrDefault(body.getString("userId"));
    var messageId = body.getString("messageId");
    if (userId == null || messageId == null) {
      return;
    }
    var timerId = pendingGraceTimers.remove(pendingKey(userId, messageId));
    if (timerId != null) {
      vertx.cancelTimer(timerId);
    }
  }

  private void persistNotification(UUID userId, UUID conversationId, UUID fromUserId, String bodyPreview, long ts) {
    var notificationId = UUID.randomUUID();
    notifications
        .create(notificationId, userId, conversationId, fromUserId, bodyPreview, ts)
        .thenRun(() -> queuePush(userId, bodyPreview, conversationId))
        .exceptionally(
            ex -> {
              log.warn("failed to persist notification {} for user {}", notificationId, userId, ex);
              return null;
            });
  }

  /** Xếp hàng 1 notification đã lưu DB để gửi push -- hẹn giờ {@link #flushPush} lần đầu tiên cho user này (nếu chưa có), các tin tiếp theo trong lúc chờ chỉ cần thêm vào hàng, không hẹn giờ lại. */
  private void queuePush(UUID userId, String bodyPreview, UUID conversationId) {
    pendingPushesByUser.computeIfAbsent(userId, k -> Collections.synchronizedList(new ArrayList<>())).add(new PendingPush(bodyPreview, conversationId));
    pendingPushTimerByUser.computeIfAbsent(userId, k -> vertx.setTimer(PUSH_DEBOUNCE_MS, tid -> flushPush(userId)));
  }

  private void flushPush(UUID userId) {
    pendingPushTimerByUser.remove(userId);
    var batch = pendingPushesByUser.remove(userId);
    if (batch == null || batch.isEmpty()) {
      return;
    }
    var last = batch.get(batch.size() - 1);
    String title;
    String body;
    if (batch.size() == 1) {
      title = "Tin nhắn mới";
      body = last.bodyPreview();
    } else {
      title = "Bạn có " + batch.size() + " tin nhắn mới";
      body = last.bodyPreview();
    }
    var data = new HashMap<String, String>();
    data.put("conversationId", last.conversationId().toString());
    pushService
        .sendToUser(userId, title, body, data)
        .exceptionally(
            ex -> {
              log.warn("failed to send push notification for user {}", userId, ex);
              return null;
            });
  }

  private static String pendingKey(UUID userId, String messageId) {
    return userId + "|" + messageId;
  }
}
