package com.pingo.chat.domain.preview;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolve "link preview" của 1 URL bất kỳ -- đặt ở {@code chat-domain} vì CẢ 2 nơi dùng: {@code hall}
 * expose ra REST {@code GET /link-preview} cho client xem trước ngay lúc ĐANG GÕ (pha compose, giống
 * Slack {@code chat.unfurlLink}), còn {@code colony} tự enrich những tin mà client KHÔNG gửi kèm
 * {@code body.preview} (client cũ, hoặc client resolve thất bại) rồi ghi ngược lại {@code messages.body}
 * -- xem {@code ChatSessionManager#enrichLinkPreview}.
 *
 * <p>Kết quả là {@link JsonObject} {@code {title?, description?, image?, domain}} -- trả {@code null}
 * (KHÔNG phải exception) cho MỌI trường hợp không dùng được: URL sai định dạng, host nội bộ, trang
 * không khai báo gì, timeout, bị chặn... Gọi luôn best-effort, không được để hỏng đường gửi tin.
 *
 * <p><b>Thứ tự 4 tầng, 100% theo chuẩn mở -- KHÔNG hardcode theo host.</b> Thêm site mới KHÔNG bao giờ
 * phải sửa code ở đây:
 * <ol>
 *   <li><b>oEmbed qua danh bạ chuẩn</b> ({@link OEmbedProviders}, tải từ {@code oembed.com/providers.json})
 *       -- CHẠY TRƯỚC, trước cả khi tải HTML. Đây là cách Slack/Discord/Telegram làm với video/social:
 *       endpoint oEmbed công khai, trả JSON gọn (~1KB) thay vì phải scrape trang 1.2MB, và KHÔNG bị các
 *       trang lớn chặn bot. Phủ ~200 provider (Youtube, Twitter/X, TikTok, Vimeo, Spotify, Flickr,
 *       Imgur, Twitch, Reddit...) và tự mở rộng khi oEmbed.org cập nhật danh bạ.</li>
 *   <li><b>{@code <meta>} og:/twitter:</b> -- chuẩn phổ biến nhất, báo chí/trang thường đều có.</li>
 *   <li><b>JSON-LD</b> ({@code <script type="application/ld+json">}) -- {@code headline/name/image},
 *       nhiều trang thương mại/doanh nghiệp dùng thay og:.</li>
 *   <li><b>oEmbed discovery</b> -- {@code <link rel="alternate" type="application/json+oembed">} mà chính
 *       trang khai: bắt được provider KHÔNG nằm trong danh bạ.</li>
 * </ol>
 *
 * <p><b>Vì sao tầng 1 phải chạy trước chứ không phải "thử scrape rồi mới fallback":</b> các trang video
 * lớn CHẶN scrape HTML từ IP datacenter -- Youtube trả {@code 302 -> google.com/sorry} (trang captcha,
 * 387 byte) thay vì HTML, đã gặp thật khi gọi {@code /watch} từ pod. Scrape trước thì luôn trắng tay với
 * mấy trang đó dù code parse đúng hoàn toàn. oEmbed thì vẫn trả JSON bình thường.
 *
 * <p><b>Cache ({@value #CACHE_TTL_SUCCESS_MS} ms thành công / {@value #CACHE_TTL_EMPTY_MS} ms rỗng):</b>
 * 1 link dán vào nhóm 100 người KHÔNG được thành 100 lần fetch ra internet -- vừa tốn băng thông vừa là
 * lý do chính khiến IP bị rate-limit. Cache cũng làm tin cũ reload lại không phải resolve lần nữa.
 *
 * <p><b>Giới hạn độ dài HTML ({@value #MAX_HTML_BYTES} byte):</b> KHÔNG được cắt thấp hơn dung lượng
 * thật của trang heavy -- og: của Youtube nằm ở byte ~700.000 của file 1,2MB, cắt 512KB (mốc cũ) là MẤT
 * HẲN og: dù HTTP 200 và regex parse hoàn toàn đúng (đã gặp thật, trông như "Youtube không có og:").
 *
 * <p><b>SSRF:</b> URL do người dùng đưa vào, server tự đi fetch -- bắt buộc chặn host phân giải về dải
 * nội bộ (loopback / RFC1918 / link-local gồm metadata 169.254.169.254 / ULA IPv6), chặn CẢ với endpoint
 * oEmbed suy ra từ danh bạ và CẢ sau mỗi lần redirect: {@code setFollowRedirects(false)} rồi tự follow
 * từng chặng, vì để WebClient tự follow thì 1 {@code Location: http://127.0.0.1:8080/} vẫn bị fetch mà
 * guard không kịp nhìn thấy.
 *
 * <p><b>Giới hạn đã biết:</b> check DNS xong rồi mới {@code getAbs(hostname)} -- giữa 2 bước đó server
 * đích có thể đổi bản ghi DNS (DNS rebinding) để lần phân giải thứ 2 ra IP nội bộ. Chặn tuyệt đối cần
 * pin IP đã resolve vào connection (kèm SNI/Host riêng cho HTTPS) -- chưa làm ở đây.
 */
@Slf4j
public class LinkPreviewService {

  /**
   * UA trình duyệt THƯỜNG cho tầng scrape, không phải UA bot: nhiều trang trả HTML khác hẳn (hoặc không
   * có og:) khi thấy UA bot. Tầng oEmbed thì dùng UA riêng ngắn gọn -- endpoint đó là API công khai.
   */
  private static final String USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";
  private static final String OEMBED_USER_AGENT = "PingoLinkPreview/1.0 (+https://pingo.chat/bot)";

  private static final int CONNECT_TIMEOUT_MS = 3_000;
  /**
   * Trần cho trọn 1 request kể cả đọc body. Phải đủ rộng cho trang ~1MB qua mạng quốc tế (Youtube
   * thường 2-4s) -- call này KHÔNG nằm trên đường ACK (colony enrich sau khi đã ACK + persist, client
   * thì chỉ dùng cho khung xem trước lúc gõ nên chậm thì không hiện, không chặn gửi tin).
   */
  private static final int FETCH_TIMEOUT_MS = 8_000;
  private static final int MAX_REDIRECTS = 2;
  private static final int MAX_HTML_BYTES = 1_200_000;
  private static final int MAX_OEMBED_BYTES = 64 * 1024;
  private static final int MAX_URL_LENGTH = 2048;
  /** Tin "chỉ chứa đúng 1 link" -- khớp đúng {@code extractSoleUrl()} bên demo.html, 2 bên PHẢI giống nhau. */
  private static final Pattern SOLE_URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);

  private static final long CACHE_TTL_SUCCESS_MS = 24 * 3600 * 1000L;
  /** Rỗng cũng cache (ngắn hơn): trang không có metadata thì lần sau đừng fetch lại liên tục. */
  private static final long CACHE_TTL_EMPTY_MS = 10 * 60 * 1000L;
  private static final int CACHE_MAX_ENTRIES = 5_000;

  private record CacheEntry(JsonObject preview, long expiresAt) {}

  private final Vertx vertx;
  private final OEmbedProviders oEmbedProviders;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
  private WebClient webClient;

  public LinkPreviewService(Vertx vertx) {
    this.vertx = vertx;
    var client = client();
    this.oEmbedProviders = new OEmbedProviders(vertx, client);
    this.oEmbedProviders.start();
  }

  /**
   * Tin này có nên resolve preview không -- CHỈ khi body là object có trường {@code message} là
   * 1 URL trần (không kèm chữ khác, không đính kèm file) và CHƯA có sẵn {@code preview}. Tách ra
   * static để colony (quyết định enrich) và caller khác dùng CHUNG 1 định nghĩa, không mỗi nơi tự viết
   * 1 regex lệch nhau.
   */
  public static String soleUrlToPreview(Object body) {
    if (!(body instanceof JsonObject json)) {
      return null;
    }
    if (json.getValue("preview") instanceof JsonObject existing && !existing.isEmpty()) {
      return null; // client đã tự resolve trước khi gửi (pha compose) -- không fetch lại lần 2
    }
    if (json.getValue("files") != null || json.containsKey("fileUrl")) {
      return null; // tin đính kèm: phần chữ là caption, không phải "tin chỉ có 1 link"
    }
    return soleUrl(json.getString("message"));
  }

  /** {@code text} có phải ĐÚNG 1 URL http(s) không (đã trim) -- null nếu không phải. */
  public static String soleUrl(String text) {
    if (text == null) {
      return null;
    }
    var trimmed = text.strip();
    return SOLE_URL.matcher(trimmed).matches() ? trimmed : null;
  }

  /**
   * Resolve 1 URL thành metadata preview. Không bao giờ fail -- {@code null} nghĩa là "không có
   * preview", caller tự quyết định vẽ gì (demo.html rơi về chữ link trần).
   */
  public CompletionStage<JsonObject> fetch(String url) {
    var target = soleUrl(url);
    if (target == null || target.length() > MAX_URL_LENGTH) {
      return CompletableFuture.completedStage(null);
    }
    var host = hostOf(target);
    if (host == null || !isHttp(target)) {
      return CompletableFuture.completedStage(null);
    }
    var cached = cache.get(target);
    if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
      return CompletableFuture.completedStage(cached.preview());
    }
    var out = new CompletableFuture<JsonObject>();
    checkHostPublicAsync(host)
        .whenComplete(
            (allowed, ex) -> {
              if (ex != null || !Boolean.TRUE.equals(allowed)) {
                if (ex == null) {
                  log.debug("link-preview: host {} not public, skip fetch", host);
                }
                out.complete(null);
                return;
              }
              resolve(target).whenComplete((preview, resolveEx) -> out.complete(remember(target, preview)));
            });
    return out;
  }

  /**
   * 4 tầng theo đúng thứ tự ưu tiên (xem javadoc lớp). Mỗi tầng trả {@code null} thì rơi xuống tầng kế;
   * KHÔNG dừng ở tầng 1 chỉ vì endpoint oEmbed tồn tại -- provider có thể trả lỗi cho URL cụ thể đó
   * (video private/gỡ bỏ), lúc ấy scrape og: vẫn có thể có ích.
   */
  private CompletionStage<JsonObject> resolve(String target) {
    return fetchViaOEmbedRegistry(target)
        .thenCompose(
            viaRegistry -> {
              if (viaRegistry != null) {
                return CompletableFuture.completedStage(viaRegistry);
              }
              return fetchHtml(target, MAX_REDIRECTS)
                  .thenCompose(
                      html -> {
                        var page = html == null ? "" : toStringUtf8(html);
                        var fromPage = parse(page, target);
                        if (fromPage != null) {
                          return CompletableFuture.completedStage(fromPage);
                        }
                        // Tầng cuối: oEmbed do CHÍNH TRANG khai trong <link> (provider ngoài danh bạ).
                        return fetchViaDiscoveredOEmbed(target, page);
                      });
            })
        .exceptionally(
            ex -> {
              log.debug("link-preview: resolve {} failed: {}", target, ex.getMessage());
              return null;
            });
  }

  private JsonObject remember(String url, JsonObject preview) {
    var ttl = preview == null ? CACHE_TTL_EMPTY_MS : CACHE_TTL_SUCCESS_MS;
    evictIfFull();
    cache.put(url, new CacheEntry(preview, System.currentTimeMillis() + ttl));
    return preview;
  }

  /**
   * Dọn entry hết hạn trước; vẫn quá trần thì xoá sạch (demo/internal scale, đơn giản hơn LRU mà không
   * sai kết quả -- chỉ tốn 1 lần fetch lại cho những link còn nóng).
   */
  private void evictIfFull() {
    if (cache.size() < CACHE_MAX_ENTRIES) {
      return;
    }
    var now = System.currentTimeMillis();
    cache.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
    if (cache.size() >= CACHE_MAX_ENTRIES) {
      cache.clear();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Tầng 1 + 4: oEmbed (danh bạ chuẩn, rồi tới link do chính trang khai)
  // ---------------------------------------------------------------------------------------------

  private CompletionStage<JsonObject> fetchViaOEmbedRegistry(String pageUrl) {
    var endpoint = oEmbedProviders.match(pageUrl);
    return endpoint == null ? CompletableFuture.completedStage(null) : callOEmbed(endpoint, pageUrl);
  }

  private CompletionStage<JsonObject> fetchViaDiscoveredOEmbed(String pageUrl, String html) {
    var endpoint = discoveredOEmbedEndpoint(html);
    if (endpoint == null) {
      return CompletableFuture.completedStage(null);
    }
    // href trong <link> có thể là tương đối -- ghép về tuyệt đối theo chính trang vừa đọc.
    return callOEmbed(resolveAbsolute(pageUrl, endpoint), pageUrl);
  }

  /**
   * Gọi 1 endpoint oEmbed: CHẶN SSRF lại lần nữa (endpoint này có thể do nội dung trang đích chỉ định ở
   * tầng discovery, tức là do người khác kiểm soát -- không được tin như danh bạ), rồi parse JSON chuẩn
   * oEmbed {@code {title, author_name, thumbnail_url}}.
   */
  private CompletionStage<JsonObject> callOEmbed(String endpoint, String pageUrl) {
    if (endpoint == null || endpoint.length() > MAX_URL_LENGTH || !isHttp(endpoint)) {
      return CompletableFuture.completedStage(null);
    }
    var host = hostOf(endpoint);
    if (host == null) {
      return CompletableFuture.completedStage(null);
    }
    var out = new CompletableFuture<JsonObject>();
    checkHostPublicAsync(host)
        .whenComplete(
            (allowed, ex) -> {
              if (ex != null || !Boolean.TRUE.equals(allowed)) {
                log.debug("link-preview: oembed endpoint {} blocked", endpoint);
                out.complete(null);
                return;
              }
              client()
                  .getAbs(endpoint)
                  .putHeader("User-Agent", OEMBED_USER_AGENT)
                  .putHeader("Accept", "application/json")
                  .timeout(FETCH_TIMEOUT_MS)
                  .as(BodyCodec.buffer())
                  .send()
                  .onSuccess(
                      resp -> {
                        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                          out.complete(null);
                          return;
                        }
                        out.complete(toPreviewFromOEmbed(toStringUtf8(truncate(resp.body(), MAX_OEMBED_BYTES)), pageUrl));
                      })
                  .onFailure(fetchEx -> out.complete(null));
            });
    return out;
  }

  private static String discoveredOEmbedEndpoint(String html) {
    if (html == null || html.isBlank()) {
      return null;
    }
    var tag = Pattern.compile("<link\\b([^>]*)/?>", Pattern.CASE_INSENSITIVE).matcher(html);
    while (tag.find()) {
      var attrs = tag.group(1);
      var rel = attribute(attrs, "rel");
      var type = attribute(attrs, "type");
      if (rel == null || !"alternate".equalsIgnoreCase(rel.strip())) {
        continue;
      }
      if (type == null || !type.toLowerCase().contains("oembed")) {
        continue;
      }
      // Ưu tiên JSON; thẻ XML cũng nhận (parse XML thì chưa cần, đa số provider có cả 2).
      if (!type.toLowerCase().contains("json")) {
        continue;
      }
      var href = attribute(attrs, "href");
      if (href != null && !href.isBlank()) {
        return decode(href);
      }
    }
    return null;
  }

  private static JsonObject toPreviewFromOEmbed(String json, String pageUrl) {
    if (json == null || json.isBlank() || json.strip().charAt(0) != '{') {
      return null; // "Bad Request" text/plain của Youtube cũng rơi vào đây
    }
    JsonObject oembed;
    try {
      oembed = new JsonObject(json);
    } catch (Exception e) {
      return null;
    }
    return build(
        pageUrl,
        oembed.getString("title"),
        oembed.getString("author_name"),
        oembed.getString("thumbnail_url", oembed.getString("url")));
  }

  // ---------------------------------------------------------------------------------------------
  // Tầng 2 + 3: scrape HTML (og:/twitter: rồi JSON-LD)
  // ---------------------------------------------------------------------------------------------

  /**
   * Đọc HTML của {@code url}, TỰ follow redirect để kiểm tra lại TỪNG chặng (xem javadoc lớp về SSRF).
   * {@code null} = không lấy được gì dùng được (lỗi mạng, 4xx/5xx, hết lượt redirect, chặng sau không an toàn).
   */
  private CompletionStage<Buffer> fetchHtml(String url, int redirectsLeft) {
    return fetchBody(url, redirectsLeft, MAX_HTML_BYTES);
  }

  private CompletionStage<Buffer> fetchBody(String url, int redirectsLeft, int maxBytes) {
    var out = new CompletableFuture<Buffer>();
    client()
        .getAbs(url)
        .putHeader("User-Agent", USER_AGENT)
        .putHeader("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5")
        .putHeader("Accept-Language", "vi,en;q=0.8")
        .timeout(FETCH_TIMEOUT_MS)
        .as(BodyCodec.buffer())
        .send()
        .onSuccess(
            resp -> {
              var status = resp.statusCode();
              if (status >= 200 && status < 300) {
                out.complete(truncate(resp.body(), maxBytes));
                return;
              }
              if (status >= 300 && status < 400 && redirectsLeft > 0) {
                if (followRedirect(url, resp.getHeader("Location"), redirectsLeft, maxBytes, out)) {
                  return;
                }
              }
              log.debug("link-preview: {} -> http {}", url, status);
              out.complete(null);
            })
        .onFailure(
            ex -> {
              log.debug("link-preview: {} fetch error {}", url, ex.getMessage());
              out.complete(null);
            });
    return out;
  }

  /**
   * Xử lý 1 chặng redirect: ghép {@code Location} về tuyệt đối, kiểm tra lại scheme/host (kể cả DNS
   * nếu là tên miền) rồi mới fetch tiếp. Trả false nếu chặng này KHÔNG hợp lệ (caller tự complete null).
   */
  private boolean followRedirect(String from, String location, int redirectsLeft, int maxBytes, CompletableFuture<Buffer> out) {
    if (location == null || location.isBlank()) {
      return false;
    }
    var next = resolveAbsolute(from, location);
    if (next == null || next.length() > MAX_URL_LENGTH || !isHttp(next)) {
      return false;
    }
    var host = hostOf(next);
    if (host == null) {
      return false;
    }
    checkHostPublicAsync(host)
        .whenComplete(
            (allowed, ex) -> {
              if (ex != null || !Boolean.TRUE.equals(allowed)) {
                log.debug("link-preview: redirect {} -> {} blocked (host {} not public)", from, next, host);
                out.complete(null);
                return;
              }
              fetchBody(next, redirectsLeft - 1, maxBytes).whenComplete((body, fetchEx) -> out.complete(fetchEx != null ? null : body));
            });
    return true;
  }

  /**
   * {@code {title?, description?, image?, domain}} -- luôn có ít nhất {@code domain}. Trả {@code null}
   * khi trang KHÔNG khai báo gì đủ dùng: vẽ 1 card chỉ có mỗi tên domain thì rỗng thông tin mà vẫn
   * chiếm chỗ, thà để client vẽ chữ link trần còn hơn (demo.html cũng đang fallback đúng như vậy).
   */
  private static JsonObject parse(String html, String url) {
    if (html == null || html.isBlank()) {
      return null;
    }
    var title = metaContent(html, "property", "og:title");
    if (title == null) {
      title = metaContent(html, "name", "twitter:title");
    }
    var description = metaContent(html, "property", "og:description");
    if (description == null) {
      description = metaContent(html, "name", "description");
    }
    if (description == null) {
      description = metaContent(html, "name", "twitter:description");
    }
    var image = metaContent(html, "property", "og:image");
    if (image == null) {
      image = metaContent(html, "name", "twitter:image");
    }
    if (title == null || description == null || image == null) {
      var jsonLd = jsonLd(html);
      if (jsonLd != null) {
        if (title == null) {
          title = jsonLdText(jsonLd, "headline", "name");
        }
        if (description == null) {
          description = jsonLdText(jsonLd, "description");
        }
        if (image == null) {
          image = jsonLdImage(jsonLd);
        }
      }
    }
    if (title == null) {
      title = titleTag(html);
    }
    if (image != null) {
      image = resolveAbsolute(url, image);
    }
    return build(url, title, description, image);
  }

  private WebClient client() {
    if (webClient == null) {
      webClient =
          WebClient.create(
              vertx,
              new WebClientOptions()
                  .setConnectTimeout(CONNECT_TIMEOUT_MS)
                  .setFollowRedirects(false) // tự follow để kiểm tra từng chặng, xem fetchBody
                  .setMaxRedirects(MAX_REDIRECTS)
                  .setDecompressionSupported(true) // nhiều trang nén cả HTML; không gỡ thì regex chạy trên rác
                  .setKeepAlive(true));
    }
    return webClient;
  }

  /**
   * Host này có phân giải ra IP public không. {@link InetAddress#getAllByName} là blocking nên chạy
   * trên worker pool ({@code executeBlocking}) -- KHÔNG được giữ event-loop, nhất là khi DNS server chậm.
   * IP literal thì kiểm tra ngay, không cần round-trip.
   */
  private CompletionStage<Boolean> checkHostPublicAsync(String host) {
    if (isInetAddressLiteral(host)) {
      return CompletableFuture.completedStage(isPublicAddressLiteral(host));
    }
    var out = new CompletableFuture<Boolean>();
    vertx
        .<Boolean>executeBlocking(() -> isPublicHost(host))
        .onSuccess(out::complete)
        .onFailure(out::completeExceptionally);
    return out;
  }

  private static boolean isInetAddressLiteral(String host) {
    // URI.getHost() đã bỏ ngoặc [] của IPv6 literal -- InetAddress tự hiểu cả 2 dạng.
    return host.indexOf(':') >= 0 || host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
  }

  private static boolean isPublicAddressLiteral(String host) {
    try {
      return !isBlockedAddress(InetAddress.getByName(host));
    } catch (Exception e) {
      return false;
    }
  }

  /** Kiểm tra TẤT CẢ bản ghi phân giải được (không chỉ bản ghi đầu) -- trộn 1 IP nội bộ vào nhiều A record vẫn phải chặn. */
  private static boolean isPublicHost(String host) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (Exception e) {
      return false;
    }
    for (var addr : addresses) {
      if (isBlockedAddress(addr)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isBlockedAddress(InetAddress addr) {
    return addr.isLoopbackAddress()
        || addr.isAnyLocalAddress()
        || addr.isLinkLocalAddress() // 169.254/16 -- gồm cả metadata endpoint của cloud
        || addr.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
        || addr.isMulticastAddress()
        || isUniqueLocalIpv6(addr); // fc00::/7 -- isSiteLocalAddress() của Java KHÔNG bắt dải này
  }

  private static boolean isUniqueLocalIpv6(InetAddress addr) {
    var bytes = addr.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
  }

  private static boolean isHttp(String url) {
    var scheme = schemeOf(url);
    return "http".equals(scheme) || "https".equals(scheme);
  }

  private static Buffer truncate(Buffer body, int maxBytes) {
    if (body == null || body.length() == 0) {
      return null;
    }
    return body.length() > maxBytes ? body.slice(0, maxBytes) : body;
  }

  private static String toStringUtf8(Buffer buffer) {
    return buffer == null ? "" : new String(buffer.getBytes(), StandardCharsets.UTF_8);
  }

  /**
   * Đọc {@code content} của thẻ {@code <meta>} được định danh bởi 1 thuộc tính bất kỳ ({@code property}
   * cho og:/twitter:, {@code name} cho chuẩn HTML).
   *
   * <p>VIẾT LẠI toàn bộ so với regex cũ vì regex cũ ({@code content=[\"']([^\"']+)[\"']}) loại dấu ngoặc
   * đơn NGAY TRONG value, nên mọi title có dấu phẩy đơn kiểu "Brazil's golden ball" bị coi là không
   * match -- im lặng mất metadata trên rất nhiều bài báo. Ở đây tách riêng việc quét thẻ {@code <meta>}
   * và việc đọc attribute, tôn trọng đúng loại ngoặc của từng attribute, và KHÔNG phụ thuộc thứ tự
   * attribute (có trang đặt {@code content} trước {@code property}).
   */
  private static String metaContent(String html, String attrName, String attrValue) {
    var tag = Pattern.compile("<meta\\b([^>]*)/?>", Pattern.CASE_INSENSITIVE).matcher(html);
    while (tag.find()) {
      var attrs = tag.group(1);
      if (!attrValue.equalsIgnoreCase(attribute(attrs, attrName))) {
        continue;
      }
      var content = attribute(attrs, "content");
      if (content != null && !content.isBlank()) {
        return decode(content);
      }
    }
    return null;
  }

  /** Giá trị 1 attribute trong chuỗi thuộc tính của thẻ HTML: {@code "..."}, {@code '...'} hoặc không ngoặc. */
  private static String attribute(String attrs, String name) {
    var m =
        Pattern
            .compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))", Pattern.CASE_INSENSITIVE)
            .matcher(attrs);
    if (!m.find()) {
      return null;
    }
    return m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
  }

  /** JSON-LD đầu tiên đọc được trong trang (nhiều trang nhúng 1 khối {@code @graph}). */
  private static JsonObject jsonLd(String html) {
    var m =
        Pattern
            .compile("<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html);
    while (m.find()) {
      var raw = m.group(1).trim();
      if (raw.isEmpty() || raw.charAt(0) != '{') {
        continue; // mảng đứng riêng hoặc rác -- không cần tới, bỏ qua cho đơn giản
      }
      try {
        return new JsonObject(raw);
      } catch (Exception e) {
        // JSON-LD bị chèn script nội bộ/lỗi cú pháp -- bỏ qua khối này, thử khối kế tiếp
      }
    }
    return null;
  }

  /** Tìm field dạng chuỗi trong JSON-LD, lặn qua {@code @graph}/{@code mainEntity} 1 tầng. */
  private static String jsonLdText(JsonObject node, String... fields) {
    for (var field : fields) {
      if (node.getValue(field) instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    for (var wrap : new String[] {"@graph", "mainEntity", "itemListElement"}) {
      var found = jsonLdTextIn(node.getValue(wrap), fields);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static String jsonLdTextIn(Object nested, String... fields) {
    if (nested instanceof JsonArray arr) {
      for (var item : arr) {
        if (item instanceof JsonObject obj) {
          var found = jsonLdText(obj, fields);
          if (found != null) {
            return found;
          }
        }
      }
    } else if (nested instanceof JsonObject obj) {
      return jsonLdText(obj, fields);
    }
    return null;
  }

  /** {@code image} trong JSON-LD có thể là chuỗi, object {@code {url}}, hoặc mảng của 2 thứ đó. */
  private static String jsonLdImage(JsonObject node) {
    var image = node.getValue("image");
    if (image == null) {
      for (var wrap : new String[] {"@graph", "mainEntity"}) {
        var found = jsonLdImageIn(node.getValue(wrap));
        if (found != null) {
          return found;
        }
      }
      return null;
    }
    if (image instanceof String s) {
      return s.isBlank() ? null : s;
    }
    if (image instanceof JsonArray arr) {
      for (var item : arr) {
        if (item instanceof String s && !s.isBlank()) {
          return s;
        }
        if (item instanceof JsonObject obj) {
          var url = obj.getString("url", obj.getString("contentUrl", null));
          if (url != null && !url.isBlank()) {
            return url;
          }
        }
      }
      return null;
    }
    if (image instanceof JsonObject obj) {
      var url = obj.getString("url", obj.getString("contentUrl", null));
      return url == null || url.isBlank() ? null : url;
    }
    return null;
  }

  private static String jsonLdImageIn(Object nested) {
    if (nested instanceof JsonArray arr) {
      for (var item : arr) {
        if (item instanceof JsonObject obj) {
          var found = jsonLdImage(obj);
          if (found != null) {
            return found;
          }
        }
      }
    } else if (nested instanceof JsonObject obj) {
      return jsonLdImage(obj);
    }
    return null;
  }

  private static String titleTag(String html) {
    var m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
    if (!m.find()) {
      return null;
    }
    var t = decode(m.group(1).trim());
    return t.isBlank() ? null : t;
  }

  /** Ghép kết quả thành preview chuẩn, hoặc null nếu không có gì để vẽ. */
  private static JsonObject build(String url, String title, String description, String image) {
    var preview = new JsonObject().put("domain", hostnameOf(url));
    if (title != null && !title.isBlank()) {
      preview.put("title", truncateText(title.strip(), 300));
    }
    if (description != null && !description.isBlank()) {
      preview.put("description", truncateText(description.strip(), 400));
    }
    if (image != null && !image.isBlank() && isHttp(image)) {
      preview.put("image", image);
    }
    return preview.size() > 1 ? preview : null;
  }

  private static String truncateText(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max).stripTrailing() + "...";
  }

  // ---------------------------------------------------------------------------------------------
  // URL helpers
  // ---------------------------------------------------------------------------------------------

  /** Domain thuần (bỏ {@code www.}) -- nhãn nhỏ đầu card preview. */
  public static String hostnameOf(String url) {
    var host = hostOf(url);
    return host == null ? url : host.replaceFirst("^www\\.", "");
  }

  private static String hostOf(String url) {
    try {
      var host = new URI(url).getHost();
      return host == null || host.isBlank() ? null : host;
    } catch (Exception e) {
      return null;
    }
  }

  private static String schemeOf(String url) {
    try {
      var scheme = new URI(url).getScheme();
      return scheme == null ? null : scheme.toLowerCase();
    } catch (Exception e) {
      return null;
    }
  }

  /** Ghép URL tương đối (og:image, header Location, href oembed) về tuyệt đối theo {@code base}. */
  private static String resolveAbsolute(String base, String maybeRelative) {
    try {
      return new URI(base).resolve(maybeRelative.trim()).toString();
    } catch (Exception e) {
      return maybeRelative;
    }
  }

  /**
   * Giải mã entity HTML thường gặp + entity số thập phân/thập lục ({@code &#8217;}, {@code &#x27;}) --
   * các site tin tức dùng nhiều, để lại nguyên văn thì client hiện chữ {@code &#8217;} ngay trong title.
   */
  private static String decode(String s) {
    if (s == null || s.indexOf('&') < 0) {
      return s;
    }
    var m = Pattern.compile("&#([xX]?)([0-9a-fA-F]+);").matcher(s);
    var sb = new StringBuilder();
    while (m.find()) {
      var hex = !m.group(1).isEmpty();
      try {
        var cp = Integer.parseInt(m.group(2), hex ? 16 : 10);
        m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
      } catch (Exception e) {
        m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
      }
    }
    m.appendTail(sb);
    return sb.toString()
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ");
  }
}
