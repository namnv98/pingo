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
 * {@code true} dù không ai nhìn màn hình. Vì vậy grace period {@link #GRACE_MS} tồn tại — nhưng chỉ
 * để quyết định có PUSH (FCM) hay không, KHÔNG trì hoãn việc LƯU notification: row insert NGAY LẬP
 * TỨC cho mọi candidate (online lẫn offline), để {@code GET /notifications}/chuông trong app luôn
 * đúng ngay tức thì, không có độ trễ nào cả (bản trước trì hoãn cả insert theo GRACE_MS, khiến chuông
 * "chậm không rõ lý do" cho candidate online — bug thật đã gặp, sửa sai chỗ này mới đúng gốc thay vì
 * vá phía client). Nếu trong lúc chờ có {@link #onReadAck} thật từ đúng session đó (client xác nhận
 * đã render tin ra màn hình, xem {@code MessageType#READ} bên harbor) thì: huỷ lịch push VÀ tự đánh
 * dấu luôn notification vừa insert là đã đọc (xem {@code NotificationRegistry#markReadByMessageId})
 * — coi như đã xem trong app, không cần phiền thêm bằng push. Hết hạn mà chưa có ACK thì coi như "mở
 * máy nhưng không xem", vẫn push như candidate offline. Khác cách bản cũ (lego-new) làm: KHÔNG đoán
 * bằng đồng hồ đơn thuần — chỉ huỷ push khi có ACK thật, không có state machine mập mờ không ai xác
 * nhận được.
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
  /** PHẢI khớp {@code ChatSessionManager#ACTIVITY_NOTIFY_ADDRESS} bên colony. */
  private static final String ACTIVITY_NOTIFY_ADDRESS = "activity_notify";
  /** Chỉ còn quyết định lúc nào GỬI PUSH (notification đã insert ngay từ onCandidates, không chờ mốc này) -- đủ ngắn để push không trễ quá lâu, đủ dài để không phiền người vừa mới thấy tin và sắp gửi READ. */
  private static final long GRACE_MS = 3_000;
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
    vertx.eventBus().consumer(ACTIVITY_NOTIFY_ADDRESS, this::onActivityNotify);
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
    var messageUuid = UUIDUtils.parseOrDefault(messageId);
    var ts = body.getLong("ts", System.currentTimeMillis());

    for (var raw : candidateUserIds) {
      var userId = UUIDUtils.parseOrDefault(String.valueOf(raw));
      if (userId == null) {
        continue;
      }
      // LƯU NGAY bất kể online/offline -- xem javadoc lớp. Grace period (nếu áp dụng) chỉ trì hoãn
      // bước PUSH bên dưới, không đụng gì tới bước insert này.
      persistNotification(userId, conversationId, fromUserId, messageUuid, bodyPreview, ts);

      if (!presence.isOnline(userId) || messageId == null) {
        // Offline: chắc chắn bỏ lỡ, push ngay. Online nhưng thiếu messageId: không có gì để đối
        // chiếu READ ack sau này -- push ngay luôn, còn hơn treo vô thời hạn không bao giờ huỷ được.
        queuePush(userId, bodyPreview, conversationId);
        continue;
      }
      var key = pendingKey(userId, messageId);
      var timerId = vertx.setTimer(
          GRACE_MS,
          tid -> {
            pendingGraceTimers.remove(key);
            // Bọc try/catch: pushService... có thể ném exception ĐỒNG BỘ (vd pool cạn kiệt) thay vì
            // CompletionStage lỗi -- không bọc thì exception này bay thẳng ra khỏi timer callback của
            // Vert.x, KHÔNG qua .exceptionally() nào cả, im lặng mất tiêu (đã gặp thật: cả nhánh
            // "online" ngừng hẳn hoạt động cho tới khi restart pod, không 1 dòng log).
            try {
              queuePush(userId, bodyPreview, conversationId);
            } catch (Exception ex) {
              log.error("grace timer callback threw for key={}", key, ex);
            }
          });
      pendingGraceTimers.put(key, timerId);
    }
  }

  /**
   * Client (qua harbor) xác nhận đã thực sự xem 1 tin -- huỷ lịch push đang chờ ân hạn cho đúng
   * (userId, messageId) đó nếu còn, VÀ tự đánh dấu đã đọc luôn notification {@code type='message'}
   * vừa insert cho tin đó (đã lưu ngay từ {@link #onCandidates}, không đợi grace period) -- coi như
   * đã xem trong app, chuông không cần treo "chưa đọc" mãi dù push đã bị huỷ.
   */
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
    var messageUuid = UUIDUtils.parseOrDefault(messageId);
    if (messageUuid != null) {
      notifications
          .markReadByMessageId(messageUuid, userId)
          .exceptionally(
              ex -> {
                log.warn("failed to auto mark-read notification for message {} user {}", messageUuid, userId, ex);
                return null;
              });
    }
  }

  /**
   * Notification {@code type=reaction/reply/mention} vừa insert bên colony (xem {@code
   * ChatSessionManager#createNotification}) -- LUÔN LƯU bất kể online/offline nên không cần lặp lại
   * logic đó ở đây, chỉ còn việc PUSH. Người bị reply/được mention đã bị {@code
   * ChatSessionManager#publishNotificationCandidates} LOẠI KHỎI candidate "message" chung (xem
   * {@code extractSpecialNotifyUserIds} bên colony) để tránh trùng 2 noti/2 push cho cùng 1 tin --
   * nghĩa là push ở đây (reply/mention/reaction) giờ là NGUỒN PUSH DUY NHẤT cho các sự kiện này,
   * không còn nguy cơ đụng độ với push "Tin nhắn mới" nữa.
   *
   * <p><b>LUÔN push, KHÔNG check {@code presence.isOnline}</b> -- khác {@link #onCandidates} (tin
   * nhắn thường), "online" ở đây KHÔNG đủ để suy ra "đã thấy": reaction/mention chỉ hiện live qua
   * frame REACTION/MESSAGE nếu người dùng ĐANG MỞ ĐÚNG conversation đó, trong khi online chỉ nghĩa
   * "có mở app ở đâu đó" (sidebar, conversation khác...) -- coi online = đã thấy sẽ bỏ sót gần hết
   * trường hợp thật (bug thật đã gặp: test reaction/reply/mention lúc đang mở app ở màn hình khác,
   * không nhận được push nào). Tin nhắn thường né được vấn đề này nhờ GRACE_MS + READ ack thật; làm
   * y hệt cho reaction/reply/mention không đáng công (tần suất thấp hơn hẳn tin nhắn) -- đơn giản
   * nhất là bắn push luôn, nhất quán với "LUÔN LƯU" đã chọn cho phần insert DB ở colony.
   */
  private void onActivityNotify(Message<JsonObject> message) {
    var body = message.body();
    var userId = UUIDUtils.parseOrDefault(body.getString("userId"));
    var conversationId = UUIDUtils.parseOrDefault(body.getString("conversationId"));
    var type = body.getString("type");
    var bodyPreview = body.getString("bodyPreview");
    if (userId == null || conversationId == null || type == null) {
      return;
    }
    var title =
        switch (type) {
          case "reaction" -> "Có người bày tỏ cảm xúc với tin nhắn của bạn";
          case "reply" -> "Có người trả lời tin nhắn của bạn";
          case "mention" -> "Bạn được nhắc đến trong 1 tin nhắn";
          default -> "Thông báo mới";
        };
    var data = new HashMap<String, String>();
    data.put("conversationId", conversationId.toString());
    try {
      pushService
          .sendToUser(userId, title, bodyPreview, data)
          .exceptionally(
              ex -> {
                log.warn("failed to send activity push notification for user {}", userId, ex);
                return null;
              });
    } catch (Exception ex) {
      log.error("onActivityNotify threw synchronously for user {}", userId, ex);
    }
  }

  /** Insert notification NGAY LẬP TỨC -- không tự gửi push (xem {@link #onCandidates} quyết định lúc nào gọi {@link #queuePush}). */
  private void persistNotification(UUID userId, UUID conversationId, UUID fromUserId, UUID messageId, String bodyPreview, long ts) {
    var notificationId = UUID.randomUUID();
    // messageTs == ts: tin gốc CHÍNH LÀ tin vừa gửi (khác reaction bên colony, xem javadoc
    // NotificationRegistry#create) -- không có độ lệch nào cần phân biệt riêng ở đây.
    notifications
        .create(notificationId, userId, conversationId, fromUserId, messageId, "message", bodyPreview, ts, ts)
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
    try {
      pushService
          .sendToUser(userId, title, body, data)
          .exceptionally(
              ex -> {
                log.warn("failed to send push notification for user {}", userId, ex);
                return null;
              });
    } catch (Exception ex) {
      // pushService.sendToUser ném đồng bộ (vd pool cạn kiệt) thì .exceptionally() ở trên không kịp
      // gắn vào đâu cả -- bọc thêm lớp này để không mất log, cùng lý do với timer callback ở onCandidates.
      log.error("flushPush threw synchronously for user {}", userId, ex);
    }
  }

  private static String pendingKey(UUID userId, String messageId) {
    return userId + "|" + messageId;
  }
}
