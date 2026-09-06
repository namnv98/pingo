package com.pingo.herald.push;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Bọc {@link ApiFuture} (kiểu Future riêng của Google API) thành {@link CompletableFuture} chuẩn — cùng cách dự án dùng {@code CompletionStage} khắp nơi khác. */
@Slf4j
@RequiredArgsConstructor
public class FirebaseMessageExecutor {

  private final FirebaseMessaging firebaseMessaging;

  public CompletableFuture<BatchResponse> sendEachAsync(List<Message> messages) {
    if (messages.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    try {
      return toCompletableFuture(firebaseMessaging.sendEachAsync(messages));
    } catch (Throwable ex) {
      log.error("error happen when send firebase message", ex);
      return CompletableFuture.failedFuture(ex);
    }
  }

  private static CompletableFuture<BatchResponse> toCompletableFuture(ApiFuture<BatchResponse> apiFuture) {
    var future = new CompletableFuture<BatchResponse>();
    ApiFutures.addCallback(
        apiFuture,
        new com.google.api.core.ApiFutureCallback<BatchResponse>() {
          @Override
          public void onFailure(Throwable throwable) {
            future.completeExceptionally(throwable);
          }

          @Override
          public void onSuccess(BatchResponse response) {
            future.complete(response);
          }
        },
        MoreExecutors.directExecutor());
    return future;
  }
}
