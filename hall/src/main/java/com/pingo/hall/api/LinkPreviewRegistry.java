package com.pingo.hall.api;

import com.google.inject.Inject;
import com.pingo.chat.domain.preview.LinkPreviewService;
import com.pingo.core.api.IRequest;
import com.pingo.core.api.annotaion.ApiMethod;
import com.pingo.core.api.annotaion.RegisterHandler;
import com.pingo.core.api.annotaion.RegisterIApi;
import com.pingo.core.api.annotaion.Type;
import com.pingo.hall.api.error.HallErrorKeys;
import com.pingo.core.common.exception.LegoBusinessException;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;

/**
 * {@code GET /link-preview?url=...} -- resolve OpenGraph của 1 link cho client xem trước NGAY LÚC ĐANG
 * GÕ (pha compose, giống Slack {@code chat.unfurlLink}: dán link vào ô nhập là hiện card nhỏ cho xem
 * trước, kèm nút X để từ chối, rồi mới bấm gửi). Xem demo.html {@code maybeFetchComposeLinkPreview}.
 *
 * <p>Toàn bộ logic fetch + parse og: + chặn SSRF nằm ở {@link LinkPreviewService} (chat-domain) --
 * colony dùng CHUNG đúng lớp đó để tự enrich những tin client không gửi kèm {@code body.preview}, ở
 * đây chỉ còn validate tham số và bọc envelope. Trước khi tách thì logic nằm nguyên trong lớp này,
 * colony không có cách nào dùng lại ngoài việc tự gọi HTTP ngược vào chính hall.
 *
 * <p>Envelope LUÔN là {@code {"data": <preview>}} hoặc {@code {"data": null}} -- bản cũ trả
 * {@code {"domain":...}} trần cho nhánh lỗi/rỗng khiến client phải viết {@code json.data || json} để
 * đoán shape. "Không có preview" là kết quả bình thường (trang không khai báo og:, host nội bộ bị
 * chặn, timeout), KHÔNG phải lỗi HTTP -- chỉ sai tham số mới thành {@code VALIDATION}.
 */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class LinkPreviewRegistry {

  private final LinkPreviewService linkPreviewService;

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "link-preview", type = Type.HTTP)})
  public CompletionStage<byte[]> preview(IRequest request) {
    var raw = request.getParam("url");
    if (raw == null || raw.isBlank()) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing url");
    }
    // soleUrl() chặn luôn scheme lạ / URL kèm khoảng trắng -- đúng định nghĩa "tin chỉ có 1 link" mà
    // colony và demo.html đang dùng, không mỗi nơi validate 1 kiểu.
    var url = LinkPreviewService.soleUrl(raw);
    if (url == null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "url must be a single http(s) URL");
    }
    return linkPreviewService.fetch(url).thenApply(preview ->{
    return   bytes(new JsonObject().put("data", preview));
    });
  }

  private static byte[] bytes(JsonObject o) {
    return o.encode().getBytes(StandardCharsets.UTF_8);
  }
}
