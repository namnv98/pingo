package com.pingo.herald.push;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.WebpushConfig;
import com.pingo.chat.domain.notification.PushTokenRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Gửi push thật (FCM) cho 1 user — điểm cắm mốc thay cho dòng log {@code [STUB push]} trước đây
 * (xem {@code NotificationConsumer}), schema/API {@code GET /notifications} không đổi. Bỏ qua
 * token đã biết chắc invalid (cache {@link #invalidTokenCache}, tự dọn khỏi DB luôn qua
 * {@link PushTokenRegistry#unregister} — khác bản gốc chỉ cache trong RAM mà không dọn DB, để lâu
 * bảng {@code push_tokens} tích đầy token chết).
 *
 * <p>{@code executor} có thể {@code null} — xem {@link #disabled} (thiếu credentials Firebase,
 * vd chưa tạo k8s Secret {@code FIREBASE_SERVICE_ACCOUNT_JSON}): {@link #sendToUser} lúc đó chỉ
 * log lại chứ không crash/không chặn luồng lưu {@code notifications} phía trên nó.
 */
@Slf4j
@RequiredArgsConstructor
public class PushService {

  private static final List<MessagingErrorCode> INVALID_TOKEN_ERRORS =
      List.of(MessagingErrorCode.SENDER_ID_MISMATCH, MessagingErrorCode.UNREGISTERED);

  private final FirebaseMessageExecutor executor;
  private final PushTokenRegistry pushTokens;
  private final Cache<String, Boolean> invalidTokenCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(60)).build();
  private final WebpushConfig webpushConfig = WebpushConfig.builder().putHeader("TTL", "604800").putHeader("Urgency", "high").build();

  public static PushService disabled(PushTokenRegistry pushTokens) {
    log.warn("FIREBASE_SERVICE_ACCOUNT_JSON chua duoc cau hinh -- push notification se KHONG duoc gui that (notifications van luu DB/doc qua GET /notifications binh thuong)");
    return new PushService(null, pushTokens);
  }

  /** Best-effort — lỗi gửi push không nên làm hỏng luồng lưu {@code notifications} phía trên nó (xem {@code NotificationConsumer}). */
  public CompletionStage<Void> sendToUser(UUID userId, String title, String body, Map<String, String> data) {
    if (executor == null) {
      log.info("[push disabled] user {} co tin nhan moi: {}", userId, body);
      return CompletableFuture.completedFuture(null);
    }
    return pushTokens
        .tokensForUser(userId)
        .thenCompose(
            tokens -> {
              var validTokens = tokens.stream().filter(t -> invalidTokenCache.getIfPresent(t) == null).toList();
              if (validTokens.isEmpty()) {
                return CompletableFuture.<Void>completedFuture(null);
              }
              var payload = new HashMap<>(data);
              payload.put("title", title);
              payload.put("body", body);
              var messages =
                  validTokens.stream()
                      .map(token -> Message.builder().setToken(token).putAllData(payload).setWebpushConfig(webpushConfig).build())
                      .collect(Collectors.toList());
              return executor
                  .sendEachAsync(messages)
                  .thenAccept(response -> handleResponse(response, validTokens))
                  .exceptionally(
                      ex -> {
                        log.warn("failed to send push notification for user {}", userId, ex);
                        return null;
                      });
            });
  }

  private void handleResponse(com.google.firebase.messaging.BatchResponse response, List<String> tokens) {
    if (response == null) {
      return;
    }
    var responses = response.getResponses();
    for (var i = 0; i < responses.size(); i++) {
      var res = responses.get(i);
      if (res.isSuccessful()) {
        continue;
      }
      var token = tokens.get(i);
      var errorCode = res.getException() == null ? null : res.getException().getMessagingErrorCode();
      if (INVALID_TOKEN_ERRORS.contains(errorCode)) {
        invalidTokenCache.put(token, Boolean.TRUE);
        pushTokens
            .unregister(token)
            .exceptionally(
                ex -> {
                  log.warn("failed to unregister invalid push token", ex);
                  return null;
                });
      } else {
        log.warn("push notification failed for 1 token, errorCode={}", errorCode);
      }
    }
  }
}
