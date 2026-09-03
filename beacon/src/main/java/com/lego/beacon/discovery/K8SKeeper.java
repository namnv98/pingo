package com.lego.beacon.discovery;

import static java.util.Objects.isNull;

import com.google.gson.reflect.TypeToken;
import com.lego.namnv.core.common.comp.AbstractLifeCycle;
import com.lego.namnv.core.common.exception.ExceptionUtils;
import com.lego.namnv.core.common.support.Disposable;
import com.lego.namnv.core.common.support.ThreadUtils;
import com.lego.namnv.discovery.k8s.K8sClientConfig;
import com.lego.namnv.discovery.keeper.ChangeType;
import com.lego.namnv.discovery.keeper.DestinationChangeEvent;
import com.lego.namnv.discovery.keeper.SortedArray;
import com.lego.namnv.discovery.router.Destination;
import com.lego.namnv98.event.EventConsumer;
import com.lego.namnv98.event.EventEmitter;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;

/**
 * {@link Keeper} thật — theo dõi (watch) trực tiếp k8s API để biết pod colony nào đang chạy,
 * healthy, và IP là gì; mỗi thay đổi được dịch thành {@link DestinationChangeEvent} và phát
 * (dispatch) ra ngoài.
 *
 * <p>Chạy trên 3 thread nền (background) song song, khởi động trong {@link #doStart()}:
 * <ul>
 *   <li>{@code pollingThread} ({@link #startWatch()}): mở 1 k8s watch stream và block đọc event
 *       (ADDED/MODIFIED/DELETED/ERROR) cho tới khi stream đứt (timeout, k8s API restart...), rồi
 *       tự retry mở lại — có backoff tăng dần 500ms mỗi lần lỗi liên tiếp để tránh spam k8s API.
 *   <li>{@code pollingThreadPre} ({@link #startWatchPre()}): mỗi 3s, thử lại {@code addPod} cho
 *       những pod đang nằm trong {@link #prePods} (đã thấy qua watch nhưng chưa có IP hoặc chưa
 *       qua healthcheck) — độc lập với watch stream ở trên, để healthcheck cũ không cần đợi có
 *       event k8s mới mới được thử lại.
 *   <li>{@code reconcileThread} ({@link #startReconcile()}): mỗi {@link #RECONCILE_INTERVAL_MS},
 *       LIST (không phải watch) toàn bộ pod hiện có rồi đối chiếu lại với {@link #pods} — tự dọn
 *       "phantom" (pod đã bị k8s xoá thật nhưng vẫn còn trong {@link #pods} vì watch lỡ mất đúng
 *       sự kiện DELETE, ví dụ giữa lúc network/k8s API chập chờn — {@code createWatchThenListen()}
 *       clear rồi dựa hoàn toàn vào watch để build lại, không có gì đảm bảo watch không bỏ sót 1
 *       sự kiện khi bị gián đoạn) và tự thêm lại pod nào watch lỡ bỏ sót ADD. Đây là lưới an toàn
 *       (safety net) độc lập với watch, không thay thế watch — watch vẫn lo phần cập nhật gần
 *       real-time, reconcile chỉ lo sửa sai lệch (drift) nếu có.
 * </ul>
 *
 * <p>Một pod chỉ thật sự được thêm vào {@link #pods} (và phát ADD event) sau khi vừa có IP vừa
 * healthcheck qua — trước đó nó nằm chờ trong {@code prePods}.
 */
@Slf4j
class K8SKeeper extends AbstractLifeCycle implements Keeper {

  private static final Type V1POD_WATCH_RESPONSE_TYPE = new V1PodResponse().getType();
  private static final long RECONCILE_INTERVAL_MS = 30_000;
  @NonNull private final K8sClientConfig config;
  private final SortedArray<Destination> pods = new SortedArray<>(Comparator.comparing(Destination::name));
  // ConcurrentHashMap vì prePods bị đọc/ghi từ nhiều thread khác nhau: thread watch k8s (onWatchResponse),
  // thread startWatchPre, và callback bất đồng bộ (async) của healthcheck chạy trên event-loop Vert.x
  // (khác thread gọi addPod). Dùng HashMap thường ở đây từng gây race condition/ConcurrentModificationException
  // khi một thread đang iterate prePods.values() còn thread khác gọi prePods.put()/remove() cùng lúc.
  private final Map<String, V1Pod> prePods = new ConcurrentHashMap<>();
  private final Function<String, CompletionStage<Boolean>> healthcheck;
  private final EventEmitter eventEmitter;
  private ApiClient client;
  private CoreV1Api api;
  private Thread pollingThread;
  private long sleepTime = 0;
  private Thread pollingThreadPre;
  private Thread reconcileThread;

  public K8SKeeper(
      K8sClientConfig config,
      Function<String, CompletionStage<Boolean>> healthcheck,
      ApiClient client) {
    this.healthcheck = healthcheck;
    this.client = client;
    this.config = config;
    this.eventEmitter = EventEmitter.newEmitter();
    initialize();
  }

  public K8SKeeper(K8sClientConfig config, Function<String, CompletionStage<Boolean>> healthcheck) {
    this(config, healthcheck, null);
  }

  @SneakyThrows(IOException.class)
  private void initialize() {
    if (isNull(client)) {
      client = Config.fromCluster();
    }
    var httpClient = client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
    this.client.setHttpClient(httpClient);
    Configuration.setDefaultApiClient(client);
    this.api = new CoreV1Api();
    this.pollingThread = new Thread(this::startWatch);
    this.pollingThreadPre = new Thread(this::startWatchPre);
    this.reconcileThread = new Thread(this::startReconcile);
  }

  @Override
  public Disposable subscribe(EventConsumer consumer) {
    return eventEmitter.subscribe(consumer);
  }

  @Override
  protected void doStart() {
    pollingThread.start();
    pollingThreadPre.start();
    reconcileThread.start();
  }

  @Override
  protected void doStop() {
    pollingThread.interrupt();
    pollingThreadPre.interrupt();
    reconcileThread.interrupt();
  }

  private void startWatch() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        log.info("Start loopingListen");
        createWatchThenListen();
        if (MapUtils.isNotEmpty(prePods)) {
          for (var pod : prePods.values()) {
            addPod(pod);
          }
        }
      } catch (ApiException apiExp) {
        var cause = ExceptionUtils.extractMeaningfulCause(apiExp);
        log.warn("k8s api exception: {}, sleeping for {} ms", cause.getMessage(), sleepTime);
        this.sleepTime = sleepTime + 500;
        if (!ThreadUtils.sleepSilence(sleepTime)) {
          log.error("cannot sleep busy spin thread to create new watcher");
          return;
        }
      } catch (Exception e) {
        // Trước đây gọi log.error(msg, e, sleepTime): "{}" trong message chỉ có 1 chỗ nhưng lại
        // truyền 2 vararg (e, sleepTime) — SLF4J sẽ nhét "e" vào chỗ "{}" (in ra toString(), KHÔNG
        // có stack trace) và bỏ qua sleepTime. Đổi thứ tự để sleepTime điền vào "{}" còn Throwable
        // đứng cuối cùng thì SLF4J mới nhận diện đúng và in đầy đủ stack trace.
        // Đồng thời tăng sleepTime ở nhánh này luôn (trước đây chỉ tăng ở nhánh ApiException),
        // tránh bị busy-spin retry liên tục khi sleepTime vẫn đang là 0.
        this.sleepTime = sleepTime + 500;
        log.error("Cannot create new k8s watcher, sleeping for {} ms", sleepTime, e);
        if (!ThreadUtils.sleepSilence(sleepTime)) {
          log.error("cannot sleep busy spin thread to create new watcher");
          return;
        }
      }
    }
  }

  private void startWatchPre() {
    while (true) {
      // sleepSilence trả về false khi thread bị interrupt (doStop() gọi pollingThreadPre.interrupt()) —
      // trước đây return value này bị bỏ qua nên interrupt() không hề dừng được vòng lặp này (thread rò rỉ khi stop).
      if (!ThreadUtils.sleepSilence(3000)) {
        return;
      }
      if (MapUtils.isNotEmpty(prePods)) {
        for (var pod : prePods.values()) {
          addPod(pod);
        }
      }
      if (log.isDebugEnabled()) {
        log.debug("list pods: {}", pods.unmodifiableValues());
        if (MapUtils.isNotEmpty(prePods)) {
          log.debug(
              "list pre-pods (đang chờ IP hoặc chưa healthcheck qua): {}",
              prePods.values().stream().map(p -> p.getMetadata().getName()).toList());
        }
      }
    }
  }

  private void startReconcile() {
    while (true) {
      if (!ThreadUtils.sleepSilence(RECONCILE_INTERVAL_MS)) {
        return;
      }
      try {
        reconcileOnce();
      } catch (Exception e) {
        // Chi 1 lan LIST that bai (vd k8s API dang cham/loi tam thoi) -- khong lam gi them, vong
        // lap se tu thu lai sau RECONCILE_INTERVAL_MS, khong can retry/backoff rieng nhu watch.
        log.warn("reconcile dinh ky voi k8s API that bai, se thu lai sau {}ms: {}", RECONCILE_INTERVAL_MS, e.getMessage());
      }
    }
  }

  /**
   * LIST (khong phai watch) toan bo pod hien co roi doi chieu voi {@link #pods} — day la luoi an
   * toan doc lap voi watch stream o {@link #startWatch()}, tu sua sai lech (drift) neu watch lo
   * bo sot 1 su kien (vd DELETE) luc bi gian doan, thay vi giu "phantom" vinh vien cho toi khi
   * watch tinh co duoc mo lai dung luc.
   */
  private void reconcileOnce() throws ApiException {
    var labelSelector = config.getLabelKey() + "=" + config.getLabelValue();
    var list = api.listNamespacedPod(config.getNamespace(), null, null, null, null, labelSelector, null, null, null, null, false);

    var trulyLiveNames = list.getItems().stream()
        .filter(this::isRunning)
        .map(pod -> pod.getMetadata().getName())
        .collect(Collectors.toSet());

    for (var destination : pods.unmodifiableValues()) {
      if (!trulyLiveNames.contains(destination.name())) {
        log.warn(
            "reconcile: pod {} khong con ton tai o k8s nhung van dang trong routing table (phantom -- watch co the da lo mat su kien DELETE luc bi gian doan) -- tu don",
            destination.name());
        removePodByName(destination.name());
      }
    }
    for (var pod : list.getItems()) {
      if (isRunning(pod)) {
        addPod(pod); // no-op neu da co san (pods.add trong addPod tra ve false, khong ban ADD event trung)
      }
    }
  }

  private synchronized void createWatchThenListen() throws Exception {
    var labelSelector = config.getLabelKey() + "=" + config.getLabelValue();
    var theCall =
        api.listNamespacedPodCall(
            config.getNamespace(),
            null,
            null,
            null,
            null,
            labelSelector,
            null,
            null,
            null,
            null,
            true,
            null);

    try (var watch = Watch.<V1Pod>createWatch(client, theCall, V1POD_WATCH_RESPONSE_TYPE)) {
      this.pods.clear();
      sleepTime = 0;
      watch.forEach(this::onWatchResponse);
    } catch (IOException e) {
      log.error("Error when close k8s watcher", e);
    }
  }

  private void onWatchResponse(Watch.Response<V1Pod> item) {
    switch (item.type) {
      case "MODIFIED" -> onModified(item.object);
      case "ADDED" -> onAdd(item.object);
      case "ERROR" -> onError(item.object);
      case "DELETED" -> onDeleted(item.object);
    }
  }

  private void onModified(V1Pod pod) {
    if (isRunning(pod)) {
      addPod(pod);
    }
    if (isDeleting(pod)) {
      removePod(pod);
    }
  }

  private void onAdd(V1Pod pod) {
    if (isRunning(pod)) {
      addPod(pod);
    }
  }

  private void onDeleted(V1Pod pod) {
    removePod(pod);
  }

  private void onError(V1Pod pod) {
    removePod(pod);
  }

  private void addPod(V1Pod pod) {
    var status = pod.getStatus();
    var podName = pod.getMetadata().getName();
    var ip = status.getPodIP();
    if (isNull(ip)) {
      // Pod mới ADD/MODIFIED nhưng chưa được k8s gán IP (còn đang ContainerCreating/Pending) —
      // chưa gọi healthcheck vội (healthcheck implementation không chắc chịu được host null),
      // cứ giữ lại trong prePods, các vòng retry sau (startWatch/startWatchPre) sẽ thử lại.
      prePods.put(podName, pod);
      return;
    }
    healthcheck
        .apply(ip)
        .thenAccept(
            aBoolean -> {
              if (!aBoolean) {
                prePods.put(podName, pod);
              } else {
                try {
                  // ip chắc chắn không null ở đây — đã guard/return sớm ở đầu addPod() rồi.
                  var destination = Destination.of(podName, InetAddress.getByName(ip));
                  if (pods.add(destination)) {
                    eventEmitter.dispatch(
                        new DestinationChangeEvent(ChangeType.ADD, destination));
                    prePods.remove(podName);
                    log.info("phat hien pod moi (ADD) [pod={}], tong hien co {} destination", destination, pods.unmodifiableValues().size());
                  }
                } catch (UnknownHostException ex) {
                  throw new RuntimeException(ex);
                }
              }
            });
  }

  private void removePod(V1Pod pod) {
    removePodByName(pod.getMetadata().getName());
  }

  private void removePodByName(String podName) {
    var destination = Destination.of(podName);
    prePods.remove(destination.name());
    if (pods.remove(destination)) {
      eventEmitter.dispatch(new DestinationChangeEvent(ChangeType.REMOVE, destination));
      log.info("phat hien pod mat (REMOVE) [pod={}], con lai {} destination", destination, pods.unmodifiableValues().size());
    }
  }

  private boolean isRunning(V1Pod pod) {
    return "Running".equalsIgnoreCase(pod.getStatus().getPhase()) && !isDeleting(pod);
  }

  private boolean isDeleting(V1Pod pod) {
    return pod.getMetadata().getDeletionTimestamp() != null;
  }

  @Override
  public List<Destination> getAll() {
    return pods.unmodifiableValues();
  }

  private static class V1PodResponse extends TypeToken<Watch.Response<V1Pod>> {}
}
