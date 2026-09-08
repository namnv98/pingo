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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolve "link preview" (OpenGraph) của 1 URL bất kỳ -- đặt ở {@code chat-domain} vì CẢ 2 nơi dùng:
 * {@code hall} expose ra REST {@code GET /link-preview} cho client xem trước ngay lúc ĐANG GÕ (pha
 * compose, giống Slack {@code chat.unfurlLink}), còn {@code colony} tự enrich những tin mà client
 * KHÔNG gửi kèm {@code body.preview} (client cũ, hoặc client resolve thất bại) rồi ghi ngược lại
 * {@code messages.body} -- xem {@code ChatSessionManager#enrichLinkPreview}.
 *
 * <p>Kết quả là {@link JsonObject} {@code {title?, description?, image?, domain}} -- trả {@code null}
 * (KHÔNG phải exception) cho MỌI trường hợp không dùng được: URL sai định dạng, host nội bộ, trang
 * không khai báo gì, timeout, redirect lỗi... Gọi luôn best-effort, không được để hỏng đường gửi tin.
 *
 * <p><b>3 tầng trích xuất, 100% theo chuẩn mở -- KHÔNG hardcode theo host</b> (một trang có thể thiếu
 * tầng trên, rơi xuống tầng dưới; thêm site MỚI không bao giờ phải sửa code ở đây):
 * <ol>
 *   <li>{@code <meta>} og:/twitter: -- chuẩn phổ biến nhất, báo chí/mạng xã hội đều có.</li>
 *   <li>JSON-LD ({@code <script type="application/ld+json">}) -- {@code name/headline/image}, nhiều
 *       trang dùng thay og: (đặc biệt trang thương mại/doanh nghiệp).</li>
 *   <li>oEmbed -- CHỈ dùng endpoint do chính trang khai qua {@code <link rel="alternate"
 *       type="application/json+oembed" href="...">} (chuẩn oEmbed discovery). Cách này tự động đúng với
 *       MỌI site hỗ trợ oEmbed -- Youtube/Wordpress/Medium/Flickr/Spotify... -- mà không cần biết trước
 *       host nào. Cố tình KHÔNG dựng endpoint cứng kiểu {@code youtube.com/oembed?url=...}: làm vậy thì
 *       mỗi site mới lại phải thêm 1 case, và bản thân Youtube cũng đã khai {@code <link>} chuẩn nên
 *       hardcode là thừa.</li>
 * </ol>
 *
 * <p><b>Giới hạn độ dài HTML ({@value #MAX_HTML_BYTES} byte):</b> KHÔNG được cắt thấp hơn dung lượng thật
 * của mấy trang heavy như Youtube -- og: của họ nằm ở byte ~700.000 của file 1,2MB, cắt 512KB (mốc cũ)
 * là MẤT HẲN og: dù HTTP 200 và parse regex hoàn toàn đúng (đã gặp thật, trông như "Youtube không có
 * og:"). Trang lớn hơn mức này thì chấp nhận có thể hụt.
 *
 * <p><b>SSRF:</b> URL do người dùng đưa vào, server tự đi fetch -- bắt buộc chặn host phân giải về
 * dải nội bộ (loopback / RFC1918 / link-local gồm metadata 169.254.169.254 / ULA IPv6), chặn CẢ sau
 * mỗi lần redirect: {@code setFollowRedirects(false)} rồi tự follow từng chặng, vì để WebClient tự
 * follow thì 1 {@code Location: http://127.0.0.1:8080/} vẫn bị fetch mà guard không kịp nhìn thấy.
 *
 * <p><b>Giới hạn đã biết:</b> check DNS xong rồi mới {@code getAbs(hostname)} -- giữa 2 bước đó server
 * đích có thể đổi bản ghi DNS (DNS rebinding) để lần phân giải thứ 2 ra IP nội bộ. Chặn tuyệt đối cần
 * pin IP đã resolve vào connection (kèm SNI/Host riêng cho HTTPS) -- chưa làm ở đây.
 */
@Slf4j
public class LinkPreviewService {

  /**
   * UA trình duyệt THƯỜNG, không phải UA bot. Nhiều trang (Facebook/Instagram một số CDN) chặn hẳn
   * response có og: khi thấy UA bot -- cùng 1 URL, đổi UA là có/không có metadata.
   */
  private static final String USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36";

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

  private final Vertx vertx;
  private WebClient webClient;

  public LinkPreviewService(Vertx vertx) {
    this.vertx = vertx;
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
              fetchHtml(target, MAX_REDIRECTS)
                  .whenComplete(
                      (html, fetchEx) -> {
                        if (fetchEx != null) {
                          log.debug("link-preview: fetch {} failed: {}", target, fetchEx.getMessage());
                        }
                        var page = html == null ? "" : toStringUtf8(html);
                        var fromPage = parse(page, target);
                        if (fromPage != null) {
                          out.complete(fromPage);
                          return;
                        }
                        // Trang không có og:/JSON-LD -- thử oEmbed (Youtube/Vimeo/SoundCloud SPA).
                        tryOEmbed(target, page).whenComplete((viaOEmbed, oEmbedEx) -> out.complete(viaOEmbed));
                      });
            });
    return out;
  }

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
              out.complete(null);
            })
        .onFailure(ex -> out.complete(null));
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
    return new String(buffer.getBytes(), StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------------------------
  // Trích xuất metadata
  // ---------------------------------------------------------------------------------------------

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

  /**
   * Đọc {@code content} của thẻ {@code <meta>} được định danh bởi 1 thuộc tính bất kỳ
   * ({@code property} cho og:/twitter:, {@code name} cho chuẩn HTML).
   *
   * <p>VIẾT LẠI toàn bộ so với regex cũ vì regex cũ ({@code content=[\"']([^\"']+)[\"']}) loại dấu
   * ngoặc đơn NGAY TRONG value, nên mọi title có dấu phẩy đơn kiểu "Brazil's golden ball" bị coi là
   * không match -- im lặng mất metadata trên rất nhiều bài báo. Ở đây tách riêng việc quét thẻ
   * {@code <meta>} và việc đọc attribute, tôn trọng đúng loại ngoặc của từng attribute, và KHÔNG phụ
   * thuộc thứ tự attribute (có trang đặt {@code content} trước {@code property}).
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

  /** Giá trị 1 attribute trong chuỗi thuộc tính của thẻ HTML: {@code "..."}. {@code '...'} hoặc không ngoặc. */
  private static String attribute(String attrs, String name) {
    var m =
        Pattern.compile(
                "\\b" + Pattern.quote(name) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))",
                Pattern.CASE_INSENSITIVE)
            .matcher(attrs);
    if (!m.find()) {
      return null;
    }
    return m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
  }

  /**
   * JSON-LD đầu tiên đọc được trong trang (nhiều trang nhúng 1 khối {@code @graph} -- trả về nguyên
   * object để {@link #jsonLdText} tự đi tìm field, kể cả bên trong @graph).
   */
  private static JsonObject jsonLd(String html) {
    var m =
        Pattern.compile(
                "<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(html);
    while (m.find()) {
      var raw = m.group(1).trim();
      if (raw.isEmpty() || raw.charAt(0) != '{') {
        continue; // mảng đứng riêng hoặc rác -- không cần tới, bỏ qua cho đơn giản
      }
      try {
        return new JsonObject(raw);
      } catch (Exception e) {
        // JSON-LD bị động kinh tế/chèn script nội bộ -- bỏ qua khối này, thử khối kế tiếp
      }
    }
    return null;
  }

  /** Tìm field dạng chuỗi trong JSON-LD, lặn qua {@code @graph}/{@code mainEntity} 1 tầng. */
  private static String jsonLdText(JsonObject node, String... fields) {
    for (var field : fields) {
      var v = node.getValue(field);
      if (v instanceof String s && !s.isBlank()) {
        return s;
      }
    }
    for (var wrap : new String[] {"@graph", "mainEntity", "itemListElement"}) {
      var nested = node.getValue(wrap);
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
        var found = jsonLdText(obj, fields);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /** {@code image} trong JSON-LD có thể là chuỗi, object {@code {url}}, hoặc mảng của 2 thứ đó. */
  private static String jsonLdImage(JsonObject node) {
    var image = node.getValue("image");
    if (image == null) {
      for (var wrap : new String[] {"@graph", "mainEntity"}) {
        var nested = node.getValue(wrap);
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
          var found = jsonLdImage(obj);
          if (found != null) {
            return found;
          }
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
    if (s.length() <= max) {
      return s;
    }
    return s.substring(0, max).stripTrailing() + "...";
  }

  // ---------------------------------------------------------------------------------------------
  // oEmbed -- tầng dự phòng cho SPA không có og: (Youtube/Vimeo/SoundCloud...)
  // ---------------------------------------------------------------------------------------------

  /**
   * Thử oEmbed CHUẨN discovery: đọc {@code <link rel="alternate" type="application/json+oembed">} mà
   * chính trang khai rồi fetch endpoint đó. Cách này KHÔNG cần biết host -- Youtube/Wordpress/Medium/
   * Flickr/Spotify... đều tự khai link này, nên thêm site mới không phải sửa gì ở đây. Trang không khai
   * thì coi như không hỗ trợ oEmbed, trả null (đã có og:/JSON-LD ở tầng trên lo phần lớn trường hợp).
   */
  private CompletionStage<JsonObject> tryOEmbed(String pageUrl, String html) {
    var endpoint = discoveredOEmbedEndpoint(html);
    if (endpoint == null) {
      return CompletableFuture.completedStage(null);
    }
    // href trong <link> có thể là tương đối -- ghép về tuyệt đối theo chính trang đang đọc.
    var resolved = resolveAbsolute(pageUrl, endpoint);
    var host = hostOf(resolved);
    if (host == null || !isHttp(resolved)) {
      return CompletableFuture.completedStage(null);
    }
    var out = new CompletableFuture<JsonObject>();
    checkHostPublicAsync(host)
        .whenComplete(
            (allowed, ex) -> {
              if (ex != null || !Boolean.TRUE.equals(allowed)) {
                out.complete(null);
                return;
              }
              fetchBody(resolved, 1, MAX_OEMBED_BYTES)
                  .whenComplete(
                      (body, fetchEx) -> {
                        if (fetchEx != null || body == null) {
                          out.complete(null);
                          return;
                        }
                        out.complete(toPreviewFromOEmbed(toStringUtf8(body), pageUrl));
                      });
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
      var type = attribute(attrs, "type");
      var rel = attribute(attrs, "rel");
      if (rel == null || !"alternate".equalsIgnoreCase(rel.strip())) {
        continue;
      }
      if (type == null || !type.toLowerCase().contains("oembed")) {
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
    var title = oembed.getString("title", null);
    var author = oembed.getString("author_name", null);
    var image = oembed.getString("thumbnail_url", null);
    return build(pageUrl, title, author, image);
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

  /** Ghép URL tương đối (og:image, header Location) về tuyệt đối theo {@code base}; giữ nguyên nếu ghép lỗi. */
  private static String resolveAbsolute(String base, String maybeRelative) {
    try {
      return new URI(base).resolve(maybeRelative.trim()).toString();
    } catch (Exception e) {
      return maybeRelative;
    }
  }

  /**
   * Giải mã entity HTML thường gặp + entity số thập phân/ thập lục ({@code &#8217;}, {@code &#x27;}) --
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
