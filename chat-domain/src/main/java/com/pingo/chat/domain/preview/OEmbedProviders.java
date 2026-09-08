package com.pingo.chat.domain.preview;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.codec.BodyCodec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Danh bạ endpoint oEmbed CHÍNH THỨC của chuẩn oEmbed -- tải từ {@code https://oembed.com/providers.json}
 * (chính oEmbed.org công bố và duy trì), rồi match URL người dùng dán vào {@code schemes[]} của từng
 * provider để suy ra endpoint cần gọi.
 *
 * <p>Đây là lý do KHÔNG phải hardcode theo host: thêm Youtube/TikTok/Spotify/Twitter... vào hệ thống
 * CHỈ là việc danh bạ đó có thêm 1 entry (oEmbed.org tự cập nhật, mình tải lại định kỳ) -- không sửa 1
 * dòng code nào. Hiện danh bạ phủ ~200 provider.
 *
 * <p>Vì sao phải có tầng này (không chỉ scrape og:): các trang video/social lớn (Youtube, Twitter/X,
 * TikTok, Instagram) CHẶN request scrape HTML từ IP datacenter -- trả 302 sang trang captcha
 * {@code google.com/sorry} thay vì HTML (đã gặp thật khi gọi {@code /watch} từ pod: HTTP 302, 387 byte).
 * Endpoint oEmbed của họ thì công khai, nhẹ (vài KB thay vì 1.2MB) và được phép gọi thường xuyên --
 * đúng cách Slack/Discord/Telegram hiện preview video.
 *
 * <p>Không tải được danh bạ (mất mạng lúc khởi động) thì {@link #match} trả null -- caller rơi xuống
 * tầng scrape og:/JSON-LD bình thường, không chết. Tự retry mỗi {@value #RETRY_INTERVAL_MS} ms tới khi
 * tải được lần đầu.
 */
@Slf4j
public class OEmbedProviders {

  private static final String REGISTRY_URL = "https://oembed.com/providers.json";
  private static final long REFRESH_INTERVAL_MS = 24 * 3600 * 1000L;
  private static final long RETRY_INTERVAL_MS = 60_000;
  private static final int FETCH_TIMEOUT_MS = 8_000;
  private static final int MAX_BYTES = 512 * 1024;

  /** 1 provider đã biên dịch sẵn: tên + endpoint + các scheme đã thành regex. */
  private record Provider(String name, String endpoint, List<Pattern> schemes) {}

  private final Vertx vertx;
  private final WebClient webClient;

  /** Snapshot bất biến, thay nguyên khối khi refresh -- đọc không cần lock. */
  private final AtomicReference<List<Provider>> providers = new AtomicReference<>(List.of());
  private final AtomicLong lastAttemptAt = new AtomicLong();

  public OEmbedProviders(Vertx vertx, WebClient webClient) {
    this.vertx = vertx;
    this.webClient = webClient;
  }

  /** Tải danh bạ lúc khởi động, tự refresh mỗi 24h. Không block caller. */
  public void start() {
    refresh();
    vertx.setPeriodic(REFRESH_INTERVAL_MS, tid -> refresh());
  }

  private void refresh() {
    lastAttemptAt.set(System.currentTimeMillis());
    webClient
        .getAbs(REGISTRY_URL)
        .putHeader("User-Agent", "PingoLinkPreview/1.0")
        .timeout(FETCH_TIMEOUT_MS)
        .as(BodyCodec.buffer())
        .send()
        .onSuccess(
            resp -> {
              if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("oembed registry http {} -- giữ {} provider đang có, thử lại sau", resp.statusCode(), providers.get().size());
                scheduleRetryIfEmpty();
                return;
              }
              var parsed = compile(toStringUtf8(resp.body()));
              if (parsed.isEmpty()) {
                log.warn("oembed registry rỗng/không parse được -- giữ danh bạ cũ");
                scheduleRetryIfEmpty();
                return;
              }
              providers.set(parsed);
              log.info("oembed registry loaded: {} providers", parsed.size());
            })
        .onFailure(
            ex -> {
              log.warn("không tải được oembed registry: {} -- giữ {} provider đang có", ex.getMessage(), providers.get().size());
              scheduleRetryIfEmpty();
            });
  }

  /**
   * Chưa có provider nào (lần tải đầu thất bại, vd DNS chưa sẵn sàng lúc pod khởi động) thì hẹn thử lại
   * -- KHÔNG để trống vĩnh viễn tới tận lần refresh 24h kế tiếp.
   */
  private void scheduleRetryIfEmpty() {
    if (providers.get().isEmpty()) {
      vertx.setTimer(RETRY_INTERVAL_MS, tid -> refresh());
    }
  }

  /**
   * Tìm endpoint oEmbed cho {@code url}: trả URL đầy đủ đã gắn {@code url=} + {@code format=json}, hoặc
   * null nếu không provider nào nhận URL này (caller tự rơi xuống scrape HTML).
   */
  public String match(String url) {
    if (url == null) {
      return null;
    }
    for (var p : providers.get()) {
      for (var scheme : p.schemes()) {
        if (scheme.matcher(url).matches()) {
          var sep = p.endpoint().contains("?") ? "&" : "?";
          return p.endpoint() + sep + "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8) + "&format=json";
        }
      }
    }
    return null;
  }

  public int size() {
    return providers.get().size();
  }

  /**
   * providers.json: {@code [{provider_name, endpoints:[{schemes:["https://youtube.com/watch?v=*"], url}]}]}
   * -- 1 provider có thể có nhiều endpoint, 1 endpoint nhiều scheme. {@code *} trong scheme là wildcard
   * (chuẩn oEmbed), dịch thành {@code .*}.
   */
  private static List<Provider> compile(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    JsonArray root;
    try {
      root = new JsonArray(json);
    } catch (Exception e) {
      return List.of();
    }
    var result = new ArrayList<Provider>();
    for (var item : root) {
      if (!(item instanceof JsonObject entry)) {
        continue;
      }
      var name = entry.getString("provider_name", "?");
      var endpoints = entry.getJsonArray("endpoints");
      if (endpoints == null) {
        continue;
      }
      for (var ep : endpoints) {
        if (!(ep instanceof JsonObject endpoint)) {
          continue;
        }
        var url = endpoint.getString("url");
        if (url == null || url.isBlank()) {
          continue;
        }
        var schemes = new ArrayList<Pattern>();
        var rawSchemes = endpoint.getJsonArray("schemes");
        if (rawSchemes != null) {
          for (var s : rawSchemes) {
            if (s instanceof String pattern && !pattern.isBlank()) {
              var regex = toRegex(pattern.strip());
              if (regex != null) {
                schemes.add(regex);
              }
            }
          }
        }
        // Endpoint KHÔNG khai schemes[] nghĩa là "nhận mọi URL của provider này" -- bỏ qua: match mù sẽ
        // gọi sai endpoint cho phần lớn URL, thà để tầng scrape og: lo phần đó.
        if (!schemes.isEmpty()) {
          result.add(new Provider(name, url.strip(), List.copyOf(schemes)));
        }
      }
    }
    return List.copyOf(result);
  }

  /** {@code https://www.youtube.com/watch?v=*} -> regex khớp đúng URL đó với đuôi bất kỳ. */
  private static Pattern toRegex(String scheme) {
    try {
      var sb = new StringBuilder("^");
      for (var i = 0; i < scheme.length(); i++) {
        var c = scheme.charAt(i);
        sb.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
      }
      return Pattern.compile(sb.append('$').toString(), Pattern.CASE_INSENSITIVE);
    } catch (Exception e) {
      return null;
    }
  }

  private static String toStringUtf8(Buffer buffer) {
    if (buffer == null) {
      return "";
    }
    var bytes = buffer.length() > MAX_BYTES ? buffer.slice(0, MAX_BYTES).getBytes() : buffer.getBytes();
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
