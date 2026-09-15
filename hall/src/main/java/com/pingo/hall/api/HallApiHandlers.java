package com.pingo.hall.api;

import com.google.inject.Inject;
import com.pingo.chat.domain.e2e.E2eKeyRegistry;
import com.pingo.chat.domain.e2e.MlsRegistry;
import com.pingo.chat.domain.file.FileRegistry;
import com.pingo.chat.domain.history.MessageHistoryRegistry;
import com.pingo.chat.domain.link.MessageLinkRegistry;
import com.pingo.chat.domain.membership.ConversationMembershipRegistry;
import com.pingo.chat.domain.notification.NotificationRegistry;
import com.pingo.chat.domain.pin.MessagePinRegistry;
import com.pingo.chat.domain.user.UserRegistry;
import com.pingo.core.api.IRequest;
import com.pingo.core.api.annotaion.ApiMethod;
import com.pingo.core.api.annotaion.RegisterHandler;
import com.pingo.core.api.annotaion.RegisterIApi;
import com.pingo.core.api.annotaion.Type;
import com.pingo.core.common.exception.LegoBusinessException;
import com.pingo.core.common.support.UUIDUtils;
import com.pingo.core.common.token.JwtHelper;
import com.pingo.core.common.token.NdlTokenException;
import com.pingo.hall.api.error.HallErrorKeys;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;

/**
 * Handler cho toàn bộ REST API của `hall` — dispatch qua {@code IApiRegistry} (quét
 * {@code @RegisterHandler} lúc khởi động, xem {@code HallAppModule}), KHÔNG còn tự viết
 * {@code HttpServer}/routing tay như {@code RestApiVerticle} cũ. Trả về {@code byte[]} (JSON thô)
 * thay vì để framework tự bọc `{"data": ...}` — giữ đúng wire format cũ (client/e2e test đang parse
 * field phẳng như {@code token}/{@code id}, không phải {@code data.token}).
 */
@RequiredArgsConstructor(onConstructor_ = {@Inject})
public class HallApiHandlers {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 200;
  private static final int MAX_USERNAME_LENGTH = 64;
  private static final int MIN_PASSWORD_LENGTH = 8;
  private static final Duration TOKEN_TTL = Duration.ofDays(7);
  private static final String BEARER_PREFIX = "Bearer ";
  /**
   * Địa chỉ EventBus broadcast "user vừa được thêm vào conversation" — mọi pod harbor lắng nghe,
   * tự subscribe hộ + báo CONVERSATION_ADDED cho client nếu đang giữ session sống của user đó (xem
   * {@code RoutingVersionSync} bên harbor). Cùng địa chỉ colony dùng khi lazy-create qua SUBSCRIBE
   * (nay đã bỏ — mọi conversation MỚI đều tạo qua {@link #createConversation}), duplicate 1 string
   * literal thay vì thêm 1 class dùng chung chỉ để giữ 1 hằng số — cùng mức chấp nhận được với cách
   * hằng số này đã được duplicate giữa colony/harbor từ trước.
   */
  private static final String MEMBERSHIP_CHANGED_ADDRESS = "conversation_membership_changed";
  /**
   * Địa chỉ EventBus broadcast "conversation X vừa bị xoá hẳn" — xem {@code #deleteConversation}.
   * Harbor lắng nghe (xem {@code RoutingVersionSync#onConversationDeleted}), relay
   * {@code CONVERSATION_DELETED} cho MỌI session đang sống có subscribe conversationId đó, để
   * client dọn UI ngay (không phải đợi tự phát hiện qua lần load lại danh sách kế tiếp).
   */
  private static final String CONVERSATION_DELETED_ADDRESS = "conversation_deleted";
  /**
   * Địa chỉ EventBus broadcast "user X vừa bị xoá khỏi conversation Y" (kick HOẶC tự rời — cùng 1
   * đường, xem {@code #removeConversationMember}) — CHỈ báo cho đúng user đó (khác {@code
   * MEMBERSHIP_CHANGED_ADDRESS}, báo cho user MỚI được thêm). Harbor lắng nghe (xem {@code
   * RoutingVersionSync#onMemberRemoved}), relay {@code CONVERSATION_DELETED} (tái dùng nguyên type
   * đã có — với người bị remove, hiệu ứng đúng là "conversation biến mất khỏi UI của tôi", không
   * cần thêm type mới) cho MỌI session sống của đúng user đó.
   */
  private static final String MEMBER_REMOVED_ADDRESS = "conversation_member_removed";
  /**
   * Địa chỉ EventBus broadcast "vừa có 1 tin to-device mới cho user X" (mã hoá đầu cuối -- thiết
   * lập Olm session/phân phối Megolm session key, xem {@link #queueE2eToDevice}) — CHỈ để relay SỐNG
   * cho session đang mở của đúng user X (tối ưu tốc độ, không phải đường đảm bảo duy nhất — hàng đợi
   * DB mới là nguồn thật, xem {@link E2eKeyRegistry#drainToDeviceMessages}). Harbor lắng nghe (thêm
   * consumer tương tự {@code RoutingVersionSync#onMemberRemoved}), relay {@code MessageType.E2E_TO_DEVICE}.
   */
  private static final String E2E_TO_DEVICE_ADDRESS = "e2e_to_device_sent";

  private final MessageHistoryRegistry history;
  private final UserRegistry users;
  private final JwtHelper jwtHelper;
  private final ConversationMembershipRegistry membership;
  private final NotificationRegistry notifications;
  private final FileRegistry files;
  private final MessagePinRegistry pins;
  private final MessageLinkRegistry links;
  private final E2eKeyRegistry e2eKeys;
  private final MlsRegistry mls;
  private final AtomicBoolean ready;
  private final Vertx vertx;

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "healthcheck", type = Type.HTTP)})
  public CompletionStage<byte[]> healthcheck(IRequest request) {
    if (!ready.get()) {
      throw new LegoBusinessException(HallErrorKeys.DRAINING, "draining");
    }
    return completed(new JsonObject().put("status", "ok"));
  }

  /**
   * {@code GET /messages?conversationId=&limit=&before=} (mặc định, nạp NGƯỢC từ 1 mốc trở về
   * trước -- "cuộn lên xem tin cũ hơn") HOẶC {@code &after=} (nạp XUÔI, tăng dần -- nạp đoạn tin
   * CHƯA ĐỌC từ ngay sau con trỏ đã đọc tới hiện tại lúc mở lại conversation, xem
   * {@link MessageHistoryRegistry#listMessagesAfter}). {@code after} ưu tiên hơn nếu cả 2 cùng có
   * mặt (không dùng đồng thời trong thực tế -- xem demo.html loadHistory).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "messages", type = Type.HTTP)})
  public CompletionStage<byte[]> listMessages(IRequest request) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var limit = parseLimit(request.getParam("limit"));
    Long after = parseBefore(request.getParam("after"));
    if (after != null) {
      return history.listMessagesAfter(conversationId, after, limit).thenApply(HallApiHandlers::bytes);
    }
    Long before = parseBefore(request.getParam("before"));
    return history.listMessages(conversationId, limit, before).thenApply(HallApiHandlers::bytes);
  }

  /**
   * {@code GET /messages/search?q=<text>&conversationId=<uuid, tuỳ chọn>&limit=} -- tìm nội dung tin
   * nhắn (full-text search, xem {@link MessageHistoryRegistry#searchMessages}). Bắt buộc auth --
   * scoping quyền theo {@code conversation_members} của CHÍNH {@code userId} lấy từ token, không phải
   * tham số client tự khai. {@code conversationId} thiếu/rỗng = tìm TOÀN CỤC xuyên mọi conversation
   * đang là thành viên; có giá trị = chỉ tìm trong đúng conversation đó (vẫn lọc lại theo membership ở
   * registry, không tin mù theo client).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "messages/search", type = Type.HTTP)})
  public CompletionStage<byte[]> searchMessages(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var query = request.getParam("q");
    if (query == null || query.isBlank()) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing q");
    }
    var conversationId = UUIDUtils.parseOrDefault(request.getParam("conversationId"));
    var limit = parseLimit(request.getParam("limit"));
    return history.searchMessages(userId, conversationId, query.strip(), limit).thenApply(HallApiHandlers::bytes);
  }

  /**
   * {@code GET /messages/edit-history?messageId=<uuid>} -- các bản CŨ của 1 tin đã bị sửa (không
   * gồm bản hiện tại, xem {@link MessageHistoryRegistry#getEditHistory}), cũ nhất trước. Không kiểm
   * tra membership riêng ở đây -- cùng mức "loose-schema" như {@link #listMessages} (messageId là
   * UUID không đoán được, ai đã thấy được tin đó qua GET /messages thì xem được lịch sử sửa của nó).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "messages/edit-history", type = Type.HTTP)})
  public CompletionStage<byte[]> getEditHistory(IRequest request) {
    UUID messageId;
    try {
      messageId = UUID.fromString(request.getParam("messageId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid messageId");
    }
    return history.getEditHistory(messageId).thenApply(HallApiHandlers::bytes);
  }

  /**
   * {@code GET /read-cursor?conversationId=} -- vị trí "đã đọc tới đâu" CỦA CHÍNH MÌNH trong 1
   * conversation (xem {@link MessageHistoryRegistry#getReadCursor}), dùng lúc mở lại conversation
   * để cuộn đúng chỗ lần trước dừng (xem demo.html loadHistory). Cần auth (khác {@code /messages}
   * -- đây là dữ liệu RIÊNG của từng user, không phải view chung của cả conversation).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "read-cursor", type = Type.HTTP)})
  public CompletionStage<byte[]> getReadCursor(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    return history.getReadCursor(conversationId, userId)
        .thenApply(cursor -> bytes(new JsonObject().put("data", cursor)));
  }

  /** {@code GET /files?conversationId=<uuid>} -- tab "Files" panel Info bên demo.html, xem javadoc {@link FileRegistry#listForConversation}. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "files", type = Type.HTTP)})
  public CompletionStage<byte[]> listFiles(IRequest request) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var limit = parseLimit(request.getParam("limit"));
    return files.listForConversation(conversationId, limit).thenApply(HallApiHandlers::bytes);
  }

  /**
   * {@code GET /pins?conversationId=<uuid>} -- tab "Pins" panel Info bên demo.html. Cần auth (khác
   * {@code /files}) -- ghim riêng là dữ liệu RIÊNG của từng user, xem javadoc
   * {@link MessagePinRegistry#listPins}.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "pins", type = Type.HTTP)})
  public CompletionStage<byte[]> listPins(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    return pins.listPins(conversationId, userId).thenApply(HallApiHandlers::bytes);
  }

  /**
   * {@code GET /links?conversationId=<uuid>&limit=} -- tab "Links" panel Info bên demo.html, danh
   * sách link chung của cả conversation (không riêng user nào, giống {@code /files}), xem javadoc
   * {@link MessageLinkRegistry#listForConversation}.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "links", type = Type.HTTP)})
  public CompletionStage<byte[]> listLinks(IRequest request) {
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var limit = parseLimit(request.getParam("limit"));
    return links.listForConversation(conversationId, limit).thenApply(HallApiHandlers::bytes);
  }

  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "users", type = Type.HTTP)})
  public CompletionStage<byte[]> listUsers(IRequest request) {
    return users.listUsers().thenApply(HallApiHandlers::bytes);
  }

  /** {@code POST /register} — body JSON {@code {username, password}}. id do server tự sinh, password được hash trước khi lưu. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "register", type = Type.HTTP)})
  public CompletionStage<byte[]> register(IRequest request) {
    var body = parseJsonBody(request);
    var validationError = validateCredentials(body);
    if (validationError != null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, validationError);
    }
    var username = body.getString("username").strip();
    var password = body.getString("password");
    return users
        .registerUser(username, password)
        .thenApply(created -> bytes(withToken(created)))
        .exceptionally(ex -> {
          if (unwrap(ex) instanceof UserRegistry.UsernameTakenException) {
            throw new LegoBusinessException(HallErrorKeys.CONFLICT, "username taken");
          }
          throw rethrow(ex);
        });
  }

  /** {@code POST /login} — body JSON {@code {username, password}}. Trả 401 chung chung cho cả "không tồn tại" lẫn "sai mật khẩu". */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "login", type = Type.HTTP)})
  public CompletionStage<byte[]> login(IRequest request) {
    var body = parseJsonBody(request);
    var username = body.getString("username");
    var password = body.getString("password");
    if (isBlank(username) || isBlank(password)) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing username/password");
    }
    return users
        .verifyLogin(username.strip(), password)
        .thenApply(found -> {
          if (found.isEmpty()) {
            throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "invalid credentials");
          }
          return bytes(withToken(found.get()));
        });
  }

  /**
   * {@code PUT /users?username=<tên mới>} — đổi tên hiển thị của CHÍNH mình. id lấy từ token đã
   * verify, không nhận qua query param nữa (lỗ hổng cũ: ai cũng đổi tên được user khác).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "users", type = Type.HTTP)})
  public CompletionStage<byte[]> setUsername(IRequest request) {
    var id = requireAuthenticatedUserId(request);
    var rawUsername = request.getParam("username");
    if (rawUsername == null || rawUsername.isBlank()) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing username");
    }
    var username = rawUsername.strip();
    if (username.length() > MAX_USERNAME_LENGTH) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "username too long");
    }
    return users
        .updateUsername(id, username)
        .thenApply(unused -> bytes(new JsonObject().put("id", id.toString()).put("username", username)))
        .exceptionally(ex -> {
          if (unwrap(ex) instanceof UserRegistry.UsernameTakenException) {
            throw new LegoBusinessException(HallErrorKeys.CONFLICT, "username taken");
          }
          throw rethrow(ex);
        });
  }

  /**
   * {@code PUT /users/avatar?avatarFileId=<uuid|rỗng>} — đổi/xoá ảnh đại diện của CHÍNH mình. id lấy
   * từ token (như {@link #setUsername}). {@code avatarFileId} rỗng/thiếu = xoá (quay lại vòng tròn
   * màu mặc định phía client); không kiểm tra fileId có thật trong bảng {@code files} hay không (id
   * sai chỉ khiến {@code <img>} phía client 404, cùng mức chấp nhận được như file đính kèm tin nhắn
   * hỏng khác — xem javadoc plan/PR liên quan).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "users/avatar", type = Type.HTTP)})
  public CompletionStage<byte[]> setUserAvatar(IRequest request) {
    var id = requireAuthenticatedUserId(request);
    var avatarFileId = parseOptionalUuidParam(request.getParam("avatarFileId"));
    return users.updateAvatar(id, avatarFileId)
        .thenApply(unused -> bytes(new JsonObject().put("id", id.toString()).put("avatarFileId", avatarFileId == null ? null : avatarFileId.toString())));
  }

  /** {@code GET /conversations} (bắt buộc {@code Authorization: Bearer <token>}) — mọi conversation mà CHÍNH mình đang là thành viên, mới hoạt động gần đây trước. */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "conversations", type = Type.HTTP)})
  public CompletionStage<byte[]> listConversations(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    return membership.listConversationsForUser(userId).thenApply(HallApiHandlers::bytes);
  }

  /**
   * {@code POST /conversations} — body JSON {@code {memberUserIds: [...], name: "..." (tuỳ chọn)}}
   * (không cần tự thêm chính mình). Tạo 1 conversationId MỚI (UUID ngẫu nhiên, dùng chung cho cả DM
   * lẫn group — không còn suy tất định từ 2 userId như trước, xem ARCHITECTURE.md mục 12), ghi
   * membership (+tên riêng nếu có, xem {@code ConversationMembershipRegistry#upsertName}), rồi
   * broadcast {@code conversation_membership_changed} để MỌI thành viên đang online (kể cả chính
   * người tạo) tự được harbor wake-subscribe + báo {@code CONVERSATION_ADDED} — client KHÔNG cần tự
   * gửi SUBSCRIBE nữa cho cả 2 loại conversation (đúng cách Slack/Discord làm: phải gọi API tạo
   * trước khi gửi tin được, xem {@code conversations.create}/{@code POST /users/@me/channels}).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "conversations", type = Type.HTTP)})
  public CompletionStage<byte[]> createConversation(IRequest request) {
    var creatorId = requireAuthenticatedUserId(request);
    var body = parseJsonBody(request);
    var rawMemberUserIds = body.getJsonArray("memberUserIds");
    var name = body.getString("name");
    var members = new LinkedHashSet<UUID>();
    members.add(creatorId);
    if (rawMemberUserIds != null) {
      for (var raw : rawMemberUserIds) {
        var parsed = UUIDUtils.parseOrDefault(String.valueOf(raw));
        if (parsed != null) {
          members.add(parsed);
        }
      }
    }
    var conversationId = UUID.randomUUID();
    return membership
        .addMembers(conversationId, members)
        .thenCompose(
            newlyAdded -> {
              // Người tạo LUÔN là owner đầu tiên (xem ConversationMembershipRegistry#setRole) --
              // thành viên khác thêm qua addMembers giữ role mặc định 'member' của cột (xem schema).
              var afterRole = membership.setRole(conversationId, creatorId, "owner");
              var afterName = afterRole.thenCompose(unused -> name == null || name.isBlank() ? CompletableFuture.completedFuture((Void) null) : membership.upsertName(conversationId, name.strip()));
              return afterName.thenCompose(
                  unused -> newlyAdded.isEmpty() ? CompletableFuture.completedFuture((Void) null) : publishMembershipChanged(conversationId, newlyAdded));
            })
        .thenApply(
            unused ->
                bytes(
                    new JsonObject()
                        .put("conversationId", conversationId.toString())
                        .put("name", name == null || name.isBlank() ? null : name.strip())
                        .put("memberUserIds", new JsonArray(new ArrayList<>(members.stream().map(UUID::toString).toList())))));
  }

  /**
   * {@code PUT /conversations?conversationId=<uuid>} — body JSON {@code {name: "..."}}. Đổi/xoá
   * tên riêng, dùng chung cho MỌI thành viên thấy (khác bản đầu chỉ lưu localStorage riêng từng
   * trình duyệt) — áp dụng được cho cả DM lẫn group, nhưng chỉ AI ĐƯỢC GỌI thì khác nhau: DM (2
   * người) ai cũng đổi được, GROUP (>2 người) CHỈ owner (xem {@link #requireGroupOwner}). {@code
   * name} rỗng/blank thì XOÁ tên (quay lại tự suy từ danh sách thành viên).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "conversations", type = Type.HTTP)})
  public CompletionStage<byte[]> renameConversation(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var body = parseJsonBody(request);
    var name = body.getString("name");
    var finalConversationId = conversationId;
    return requireGroupOwner(finalConversationId, userId)
        .thenCompose(unused -> membership.upsertName(finalConversationId, name))
        .thenApply(unused -> bytes(new JsonObject().put("conversationId", finalConversationId.toString()).put("name", name == null || name.isBlank() ? null : name.strip())));
  }

  /**
   * {@code PUT /conversations/avatar?conversationId=<uuid>&avatarFileId=<uuid|rỗng>} — đổi/xoá ảnh
   * đại diện RIÊNG của 1 group (không dùng cho DM — DM hiện avatar thật của người kia, xem
   * {@code conversationAvatar()} phía client). GROUP (>2 người) CHỈ owner mới đổi được (xem
   * {@link #requireGroupOwner}). {@code avatarFileId} rỗng/thiếu = xoá.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "conversations/avatar", type = Type.HTTP)})
  public CompletionStage<byte[]> setConversationAvatar(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var avatarFileId = parseOptionalUuidParam(request.getParam("avatarFileId"));
    var finalConversationId = conversationId;
    return requireGroupOwner(finalConversationId, userId)
        .thenCompose(unused -> membership.upsertAvatar(finalConversationId, avatarFileId))
        .thenApply(unused -> bytes(new JsonObject().put("conversationId", finalConversationId.toString()).put("avatarFileId", avatarFileId == null ? null : avatarFileId.toString())));
  }

  /**
   * {@code POST /conversations/members?conversationId=<uuid>} — body JSON {@code {memberUserIds: [...]}}.
   * Thêm thành viên vào 1 conversation ĐÃ TỒN TẠI (khác {@link #createConversation}, luôn tạo mới).
   * DM (2 người) thì ai cũng thêm được (thêm 1 người thứ 3 vào biến nó thành group tự nhiên); GROUP
   * (>2 người) CHỈ owner mới thêm được (xem {@link #requireGroupOwner} — kiểm tra theo SỐ THÀNH VIÊN
   * HIỆN TẠI, trước khi thêm). Tái dùng nguyên {@code membership.addMembers} + {@link
   * #publishMembershipChanged} như lúc tạo conversation, nên member mới được harbor wake-subscribe +
   * báo {@code CONVERSATION_ADDED} y hệt.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "conversations/members", type = Type.HTTP)})
  public CompletionStage<byte[]> addConversationMembers(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var body = parseJsonBody(request);
    var rawMemberUserIds = body.getJsonArray("memberUserIds");
    var newMemberUserIds = new LinkedHashSet<UUID>();
    if (rawMemberUserIds != null) {
      for (var raw : rawMemberUserIds) {
        var parsed = UUIDUtils.parseOrDefault(String.valueOf(raw));
        if (parsed != null) {
          newMemberUserIds.add(parsed);
        }
      }
    }
    var finalConversationId = conversationId;
    return requireGroupOwner(finalConversationId, userId)
        .thenCompose(unused -> membership.addMembers(finalConversationId, newMemberUserIds))
        .thenCompose(
            newlyAdded -> newlyAdded.isEmpty() ? CompletableFuture.completedFuture((Void) null) : publishMembershipChanged(finalConversationId, newlyAdded))
        .thenApply(
            unused ->
                bytes(
                    new JsonObject()
                        .put("conversationId", finalConversationId.toString())
                        .put("memberUserIds", new JsonArray(new ArrayList<>(newMemberUserIds.stream().map(UUID::toString).toList())))));
  }

  /**
   * {@code DELETE /conversations/members?conversationId=<uuid>&userId=<uuid>&newOwnerUserId=<uuid|tuỳ chọn>}
   * — xoá 1 thành viên khỏi conversation. Dùng chung cho CẢ 2 case: kick người khác ({@code userId}
   * != caller — CHỈ owner mới làm được) lẫn tự rời nhóm ({@code userId} == caller — ai cũng làm
   * được). Nếu xoá xong conversation không còn thành viên nào, cascade-xoá cả conversation luôn
   * (tái dùng đúng chuỗi lệnh của {@link #deleteConversation}) — tránh để lại conversation rỗng mồ côi.
   *
   * <p><b>Không được để nhóm còn thành viên mà KHÔNG CÒN owner nào</b> — xoá/rời đúng owner CUỐI
   * CÙNG trong khi nhóm còn người khác thì bắt buộc kèm {@code newOwnerUserId} (1 thành viên còn lại
   * bất kỳ) để tự động thăng làm owner mới NGAY TRƯỚC KHI xoá; thiếu thì trả lỗi {@code CONFLICT}
   * thay vì âm thầm để nhóm mồ côi. Không owner nào bị ảnh hưởng thì tham số này bỏ qua (không cần).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.DELETE, endpoint = "conversations/members", type = Type.HTTP)})
  public CompletionStage<byte[]> removeConversationMember(IRequest request) {
    var callerId = requireAuthenticatedUserId(request);
    UUID conversationId;
    UUID removedUserId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
      removedUserId = UUID.fromString(request.getParam("userId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId/userId");
    }
    var newOwnerUserId = UUIDUtils.parseOrDefault(request.getParam("newOwnerUserId"));
    var finalConversationId = conversationId;
    var finalRemovedUserId = removedUserId;
    return membership
        .getMemberRoles(finalConversationId)
        .thenCompose(
            roles -> {
              if (!roles.containsKey(callerId)) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "conversation not found");
              }
              if (!roles.containsKey(finalRemovedUserId)) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "member not found");
              }
              // Kick người KHÁC (userId != caller) chỉ owner mới làm được -- member thường chỉ được tự rời chính mình.
              if (!finalRemovedUserId.equals(callerId) && !"owner".equals(roles.get(callerId))) {
                throw new LegoBusinessException(HallErrorKeys.FORBIDDEN, "chỉ chủ nhóm mới được xoá thành viên khác");
              }
              var remainingCount = roles.size() - 1;
              var wasOnlyOwner =
                  "owner".equals(roles.get(finalRemovedUserId))
                      && roles.entrySet().stream().noneMatch(e -> !e.getKey().equals(finalRemovedUserId) && "owner".equals(e.getValue()));
              CompletionStage<Void> beforeRemove;
              if (wasOnlyOwner && remainingCount > 0) {
                if (newOwnerUserId == null || newOwnerUserId.equals(finalRemovedUserId) || !roles.containsKey(newOwnerUserId)) {
                  throw new LegoBusinessException(HallErrorKeys.CONFLICT, "phải chỉ định 1 thành viên khác làm chủ nhóm trước khi rời/xoá chủ nhóm cuối cùng");
                }
                beforeRemove = membership.setRole(finalConversationId, newOwnerUserId, "owner");
              } else {
                beforeRemove = CompletableFuture.completedFuture(null);
              }
              return beforeRemove
                  .thenCompose(unused -> membership.removeMember(finalConversationId, finalRemovedUserId))
                  .thenCompose(
                      unused ->
                          remainingCount == 0
                              ? history
                                  .deleteForConversation(finalConversationId)
                                  .thenCompose(u2 -> notifications.deleteForConversation(finalConversationId))
                                  .thenCompose(u2 -> membership.clearConversationRow(finalConversationId))
                                  .thenCompose(u2 -> membership.deleteConversation(finalConversationId))
                              : CompletableFuture.completedFuture((Void) null));
            })
        .thenApply(
            unused -> {
              vertx
                  .eventBus()
                  .publish(
                      MEMBER_REMOVED_ADDRESS,
                      new JsonObject().put("conversationId", finalConversationId.toString()).put("removedUserId", finalRemovedUserId.toString()));
              return bytes(new JsonObject().put("conversationId", finalConversationId.toString()).put("removedUserId", finalRemovedUserId.toString()));
            });
  }

  /**
   * {@code PUT /conversations/role?conversationId=<uuid>&userId=<uuid>&role=owner|member} — thăng
   * ({@code owner})/hạ ({@code member}) 1 thành viên. CHỈ owner hiện tại mới gọi được (kể cả tự hạ
   * chính mình). Hạ owner CUỐI CÙNG (không còn owner nào khác) xuống member bị chặn ({@code
   * CONFLICT}) — muốn rút khỏi vai trò owner trong trường hợp đó thì phải thăng người khác làm owner
   * TRƯỚC, đúng tinh thần "nhóm luôn phải có ít nhất 1 owner" như {@link #removeConversationMember}.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "conversations/role", type = Type.HTTP)})
  public CompletionStage<byte[]> setConversationMemberRole(IRequest request) {
    var callerId = requireAuthenticatedUserId(request);
    UUID conversationId;
    UUID targetUserId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
      targetUserId = UUID.fromString(request.getParam("userId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId/userId");
    }
    var role = request.getParam("role");
    if (!"owner".equals(role) && !"member".equals(role)) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "role phải là 'owner' hoặc 'member'");
    }
    var finalConversationId = conversationId;
    var finalTargetUserId = targetUserId;
    var finalRole = role;
    return membership
        .getMemberRoles(finalConversationId)
        .thenCompose(
            roles -> {
              if (!roles.containsKey(callerId)) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "conversation not found");
              }
              if (!"owner".equals(roles.get(callerId))) {
                throw new LegoBusinessException(HallErrorKeys.FORBIDDEN, "chỉ chủ nhóm mới đổi được vai trò thành viên");
              }
              if (!roles.containsKey(finalTargetUserId)) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "member not found");
              }
              var wasOnlyOwner =
                  "owner".equals(roles.get(finalTargetUserId))
                      && roles.entrySet().stream().noneMatch(e -> !e.getKey().equals(finalTargetUserId) && "owner".equals(e.getValue()));
              if ("member".equals(finalRole) && wasOnlyOwner) {
                throw new LegoBusinessException(HallErrorKeys.CONFLICT, "phải có 1 owner khác trước khi hạ owner cuối cùng xuống member");
              }
              return membership.setRole(finalConversationId, finalTargetUserId, finalRole);
            })
        .thenApply(
            unused ->
                bytes(
                    new JsonObject()
                        .put("conversationId", finalConversationId.toString())
                        .put("userId", finalTargetUserId.toString())
                        .put("role", finalRole)));
  }

  /**
   * {@code PUT /conversations/mute?conversationId=<uuid>&muted=<true|false>} — bật/tắt thông báo
   * RIÊNG của chính người gọi cho 1 conversation (không ảnh hưởng thành viên khác, khác {@code
   * renameConversation}/{@code setConversationAvatar} — dùng chung cho mọi người). Không broadcast
   * EventBus nào — mute là state riêng tư, không ai khác cần biết.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "conversations/mute", type = Type.HTTP)})
  public CompletionStage<byte[]> setConversationMuted(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var muted = Boolean.parseBoolean(request.getParam("muted"));
    var finalConversationId = conversationId;
    return membership
        .isMember(finalConversationId, userId)
        .thenCompose(
            isMember -> {
              if (!isMember) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "conversation not found");
              }
              return membership.setMuted(finalConversationId, userId, muted);
            })
        .thenApply(unused -> bytes(new JsonObject().put("conversationId", finalConversationId.toString()).put("muted", muted)));
  }

  /**
   * {@code DELETE /conversations?conversationId=<uuid>} — xoá 1 conversation cho MỌI thành viên
   * (không phải "rời khỏi" chỉ riêng mình, xem {@link #removeConversationMember}). DM (2 người) ai
   * cũng xoá được; GROUP (>2 người) CHỈ owner (xem {@link #requireGroupOwner}) — member thường chỉ
   * được tự rời, muốn "xoá nhóm cho mọi người" phải là owner. 404 nếu conversationId không tồn
   * tại/đã bị xoá, 403 nếu là group mà không phải owner.
   *
   * <p><b>{@code conversation_members} bị xoá THẬT (hard-delete)</b> — đây mới là bước làm
   * conversation thực sự "biến mất" khỏi mọi nơi (mọi truy vấn — {@code listConversationsForUser},
   * {@code isMember} — đều xét theo bảng này). {@code messages} chỉ bị XOÁ MỀM (xem
   * {@link MessageHistoryRegistry#deleteForConversation}), tên/ảnh riêng ({@code conversations} row)
   * và {@code notifications} vẫn xoá thật như cũ. 5 bảng phụ khác còn lại ({@code message_reads},
   * {@code message_reactions}, {@code message_pins_shared}, {@code message_pins_private}, {@code
   * message_links}, {@code files}) CHƯA được dọn ở đây — cố tình để lại (mồ côi tạm thời, vô hại vì
   * conversation đã "biến mất" theo {@code conversation_members} rồi) cho 1 job dọn dẹp định kỳ RIÊNG
   * (chưa viết) quét sạch toàn bộ — bao gồm cả {@code messages} đã xoá mềm ở trên — theo
   * conversationId không còn dòng nào trong {@code conversation_members}.
   *
   * <p>Không bọc transaction ACID xuyên các bảng — các lệnh tuần tự, cùng mức best-effort với
   * {@code persistMessage}/{@code publishNotificationCandidates} bên colony (mỗi lệnh tự
   * idempotent, chạy lại an toàn nếu 1 bước giữa chừng lỗi mạng — không rủi ro như INSERT trùng lặp).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.DELETE, endpoint = "conversations", type = Type.HTTP)})
  public CompletionStage<byte[]> deleteConversation(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var finalConversationId = conversationId;
    return requireGroupOwner(finalConversationId, userId)
        .thenCompose(
            unused ->
                history
                    .deleteForConversation(finalConversationId)
                    .thenCompose(u2 -> notifications.deleteForConversation(finalConversationId))
                    .thenCompose(u2 -> membership.clearConversationRow(finalConversationId))
                    .thenCompose(u2 -> membership.deleteConversation(finalConversationId)))
        .thenApply(
            unused -> {
              vertx.eventBus().publish(CONVERSATION_DELETED_ADDRESS, new JsonObject().put("conversationId", finalConversationId.toString()));
              return bytes(new JsonObject().put("conversationId", finalConversationId.toString()).put("deleted", true));
            });
  }

  /**
   * Xác nhận {@code userId} có quyền chỉnh THÔNG TIN CHUNG của conversation -- đổi tên/ảnh, thêm
   * thành viên, xoá hẳn conversation (KHÔNG dùng cho kick/thăng-hạ role, 2 chỗ đó tự kiểm tra riêng
   * vì còn phải tính thêm quy tắc "luôn còn ít nhất 1 owner", xem {@link #removeConversationMember}/
   * {@link #setConversationMemberRole}). Với GROUP (>2 thành viên) CHỈ owner được làm -- member
   * thường chỉ được tự rời nhóm, không được tác động thông tin chung. Với DM (đúng 2 người) BỎ QUA
   * owner-gate này -- 2 người ngang hàng, không có khái niệm "quản trị" thật sự, giữ nguyên hành vi
   * cũ (ai cũng đổi được tên/ảnh chung, hoặc xoá hẳn đoạn chat đó cho cả 2).
   */
  private CompletionStage<Void> requireGroupOwner(UUID conversationId, UUID userId) {
    return membership
        .getMemberRoles(conversationId)
        .thenApply(
            roles -> {
              if (!roles.containsKey(userId)) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "conversation not found");
              }
              if (roles.size() > 2 && !"owner".equals(roles.get(userId))) {
                throw new LegoBusinessException(HallErrorKeys.FORBIDDEN, "chỉ chủ nhóm mới được thay đổi thông tin nhóm");
              }
              return null;
            });
  }

  /** Cùng shape payload với colony's {@code ChatSessionManager#publishMembershipChanged} — xem javadoc {@link #MEMBERSHIP_CHANGED_ADDRESS}. */
  private CompletionStage<Void> publishMembershipChanged(UUID conversationId, java.util.Set<UUID> newlyAddedUserIds) {
    return membership
        .getMembers(conversationId)
        .thenAccept(
            allMembers -> {
              var payload =
                  new JsonObject()
                      .put("conversationId", conversationId.toString())
                      .put("newMemberUserIds", newlyAddedUserIds.stream().map(UUID::toString).toList())
                      .put("memberUserIds", allMembers.stream().map(UUID::toString).toList());
              vertx.eventBus().publish(MEMBERSHIP_CHANGED_ADDRESS, payload);
            });
  }

  /**
   * {@code POST /file/create?token=<jwt>&conversationId=<uuid>} -- endpoint thay thế cho service
   * "jad" bị thiếu mà module {@code file-server} (nginx/Lua, copy từ dự án cũ) cần gọi ra trước khi
   * ghi file lên đĩa, xem javadoc {@link FileRegistry}. Nhận token qua QUERY PARAM (không phải header
   * {@code Authorization} như mọi endpoint khác) vì {@code jad.create_file_path} bên Lua không
   * forward header cho route này (nguyên bản đã vậy, không phải mình tự chọn) -- client tự gắn
   * {@code ?token=} khi gọi {@code /v2/api/upload} thì token mới tới được đây, xem demo.html
   * {@code uploadAndSendFile()}. {@code conversationId} tuỳ chọn -- thiếu/sai định dạng thì vẫn cho
   * upload bình thường, chỉ là file đó sẽ không xuất hiện ở tab Files của conversation nào.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "file/create", type = Type.HTTP)})
  public CompletionStage<byte[]> createFile(IRequest request) {
    var userId = resolveUserId(request.getParam("token"));
    var conversationId = UUIDUtils.parseOrDefault(request.getParam("conversationId"));
    return files.createFile(userId, conversationId)
        .thenApply(
            fileId ->
                bytes(
                    new JsonObject()
                        .put("data", new JsonObject().put("id", fileId.toString()).put("path", FileRegistry.STORAGE_PATH))
                        .put("userId", userId.toString())));
  }

  /**
   * {@code POST /file/update} -- file-server gọi NGAY SAU KHI ghi xong bytes lên đĩa, body JSON
   * {@code {fileId, size, mime}} (xem file-server/fileserver/v2/file/upload.lua). Tên file gốc (nếu
   * có) đi kèm qua query {@code fileName} -- client tự gắn lúc gọi {@code /v2/api/upload}, giữ
   * nguyên xuyên suốt (upload.lua tái sử dụng lại {@code args} ban đầu cho cả 2 lệnh gọi).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "file/update", type = Type.HTTP)})
  public CompletionStage<byte[]> updateFile(IRequest request) {
    var body = parseJsonBody(request);
    UUID fileId;
    try {
      fileId = UUID.fromString(body.getString("fileId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid fileId");
    }
    var name = request.getParam("fileName");
    return files.markUploaded(fileId, body.getString("mime"), body.getLong("size", 0L), name)
        .thenApply(unused -> bytes(new JsonObject()));
  }

  /**
   * {@code GET /file/get?id=<fileId>} -- KHÔNG yêu cầu {@code Authorization} (khác mọi endpoint
   * khác của hall) vì thẻ {@code <img src>}/{@code <video src>} của trình duyệt không gắn được
   * header tuỳ ý -- coi fileId (UUID ngẫu nhiên, không đoán được) là đủ bảo vệ cho quy mô demo/test
   * hiện tại, chấp nhận cùng mức "không auth cho GET" như {@code /users}/{@code /messages} đã có sẵn.
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "file/get", type = Type.HTTP)})
  public CompletionStage<byte[]> getFile(IRequest request) {
    UUID fileId;
    try {
      fileId = UUID.fromString(request.getParam("id"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid id");
    }
    return files.getFile(fileId)
        .thenApply(
            file -> {
              if (file == null) {
                throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "file not found");
              }
              return bytes(new JsonObject().put("data", file));
            });
  }

  /** Đọc + verify header {@code Authorization: Bearer <token>} -- ném 401 cho mọi lý do thất bại (thiếu header, token sai/hết hạn). */
  private UUID requireAuthenticatedUserId(IRequest request) {
    var header = request.getHeader("Authorization");
    if (header == null || !header.startsWith(BEARER_PREFIX)) {
      throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
    return resolveUserId(header.substring(BEARER_PREFIX.length()).strip());
  }

  /** Verify 1 chuỗi token JWT thô (đã tách khỏi header/query) -- dùng chung cho cả 2 đường lấy token (header Bearer, hoặc query param -- xem {@link #createFile}). */
  private UUID resolveUserId(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
    try {
      var decoded = jwtHelper.decode(rawToken.strip());
      var userId = decoded.getUUID("userId");
      if (userId == null) {
        throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
      }
      return userId;
    } catch (NdlTokenException e) {
      throw new LegoBusinessException(HallErrorKeys.UNAUTHORIZED, "missing/invalid/expired token");
    }
  }

  private String issueToken(UUID userId, String username) {
    return jwtHelper.tokenBuilder().withClaim("userId", userId).withClaim("username", username).withClaim("exp", Instant.now().plus(TOKEN_TTL)).build();
  }

  private JsonObject withToken(JsonObject user) {
    return user.copy().put("token", issueToken(UUID.fromString(user.getString("id")), user.getString("username")));
  }

  private static JsonObject parseJsonBody(IRequest request) {
    try {
      return request.getBody().toJsonObject();
    } catch (Exception e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "invalid json body");
    }
  }

  private static String validateCredentials(JsonObject body) {
    var username = body.getString("username");
    var password = body.getString("password");
    if (isBlank(username)) {
      return "missing username";
    }
    if (username.strip().length() > MAX_USERNAME_LENGTH) {
      return "username too long";
    }
    if (isBlank(password)) {
      return "missing password";
    }
    if (password.length() < MIN_PASSWORD_LENGTH) {
      return "password too short (min " + MIN_PASSWORD_LENGTH + " chars)";
    }
    return null;
  }

  private static CompletionStage<byte[]> completed(JsonObject body) {
    return java.util.concurrent.CompletableFuture.completedStage(bytes(body));
  }

  private static byte[] bytes(JsonObject body) {
    return body.encode().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] bytes(JsonArray body) {
    return body.encode().getBytes(StandardCharsets.UTF_8);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static Throwable unwrap(Throwable ex) {
    return ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
  }

  private static RuntimeException rethrow(Throwable ex) {
    return ex instanceof RuntimeException re ? re : new RuntimeException(ex);
  }

  private static int parseLimit(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_LIMIT;
    }
    try {
      return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw)));
    } catch (NumberFormatException e) {
      return DEFAULT_LIMIT;
    }
  }

  /** Rỗng/thiếu = null (xoá avatar). Có giá trị nhưng SAI định dạng UUID -> 400 (khác {@code UUIDUtils.parseOrDefault} dùng ở nơi khác, vốn lặng lẽ trả null cho input tuỳ chọn/khoan dung hơn — ở đây avatarFileId sai định dạng là lỗi client thật sự cần báo rõ). */
  private static UUID parseOptionalUuidParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.strip());
    } catch (IllegalArgumentException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "invalid avatarFileId");
    }
  }

  private static Long parseBefore(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ================= Mã hoá đầu cuối (E2E) — xem chat-domain E2eKeyRegistry, frontend/vendor/olm.js =================

  // ================= MLS (RFC 9420) — thay Olm/Megolm, engine ts-mls phía client (vendor/mls.js) =================
  // Server chỉ là Delivery Service tối giản (RFC 9750): directory KeyPackage (4 endpoint dưới) +
  // trung chuyển Welcome/Commit báo hiệu qua hàng đợi to-device SẴN CÓ (type "mls_welcome"/
  // "mls_commit", vẫn dùng POST/GET /e2e/to-device — bảng + EventBus + relay harbor giữ nguyên,
  // chỉ payload đổi). Ciphertext tin nhắn MLS ({mls:true}) đi như MESSAGE thường qua colony, không
  // đụng hệ thống này.

  /**
   * {@code PUT /mls/key-packages} — body {@code {deviceId, keyPackages: ["<base64 wire>", ...]}}.
   * Đăng ký thêm 1 lô KeyPackage (public, opaque với server — credential "user:&lt;userId&gt;" nằm
   * TRONG keypackage do client MLS tự ký) cho THIẾT BỊ này (deviceId UUID do client sinh 1 lần,
   * lưu localStorage). Client gọi lúc init + định kỳ khi GET /mls/key-package-count còn ít (xem
   * {@link MlsRegistry#publishKeyPackages}).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "mls/key-packages", type = Type.HTTP)})
  public CompletionStage<byte[]> publishMlsKeyPackages(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var body = parseJsonBody(request);
    var deviceId = UUIDUtils.parseOrDefault(body.getString("deviceId"));
    if (deviceId == null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid deviceId");
    }
    var raw = body.getJsonArray("keyPackages");
    var keyPackages = new ArrayList<String>();
    if (raw != null) {
      for (var item : raw) {
        if (item instanceof String kp && !kp.isBlank()) {
          keyPackages.add(kp);
        }
      }
    }
    if (keyPackages.size() > 50) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "too many key packages in one batch (max 50)");
    }
    return mls.publishKeyPackages(userId, deviceId, keyPackages)
        .thenApply(unused -> bytes(new JsonObject().put("deviceId", deviceId.toString()).put("published", keyPackages.size())));
  }

  /** {@code GET /mls/key-package-count?deviceId=<uuid>} — số KeyPackage còn lại của thiết bị này, client tự top-up (giống /e2e/prekey-count cũ). */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "mls/key-package-count", type = Type.HTTP)})
  public CompletionStage<byte[]> countMlsKeyPackages(IRequest request) {
    requireAuthenticatedUserId(request);
    var deviceId = UUIDUtils.parseOrDefault(request.getParam("deviceId"));
    if (deviceId == null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid deviceId");
    }
    return mls.countKeyPackages(deviceId).thenApply(count -> bytes(new JsonObject().put("count", count)));
  }

  /**
   * {@code GET /mls/key-packages?userId=<uuid>&limit=N} — claim (dùng 1 lần, xoá luôn) tối đa N
   * KeyPackage của user đích, chia round-robin theo thiết bị (xem {@link MlsRegistry#claimKeyPackages}).
   * Dùng lúc thêm thành viên vào nhóm MLS: mỗi người được add CẦN ĐÚNG 1 keypackage (thiết bị nào
   * đại diện không quan trọng — MLS group ciphertext mọi client trong group cùng giải được, khác
   * hẳn fan-out per-device của Olm cũ). Mảng rỗng = user đó chưa bật MLS ở bất kỳ thiết bị nào
   * (không phải 404 — cùng tinh thần GET /e2e/keys/bundle cũ).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "mls/key-packages", type = Type.HTTP)})
  public CompletionStage<byte[]> claimMlsKeyPackages(IRequest request) {
    requireAuthenticatedUserId(request);
    UUID targetUserId;
    try {
      targetUserId = UUID.fromString(request.getParam("userId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid userId");
    }
    int limit = 1;
    var rawLimit = request.getParam("limit");
    if (rawLimit != null && !rawLimit.isBlank()) {
      try {
        limit = Math.max(1, Math.min(100, Integer.parseInt(rawLimit.trim())));
      } catch (NumberFormatException e) {
        throw new LegoBusinessException(HallErrorKeys.VALIDATION, "invalid limit");
      }
    }
    var finalLimit = limit;
    return mls.claimKeyPackages(targetUserId, finalLimit)
        .thenApply(kps -> bytes(new JsonObject().put("keyPackages", kps)));
  }

  /**
   * {@code DELETE /mls/key-packages?deviceId=<uuid>} — gỡ mọi KeyPackage CHƯA DÙNG của 1 thiết bị
   * thuộc CHÍNH mình ("xoá thiết bị" bản MLS — device chưa join group nào thì keypackage là tất cả
   * những gì còn trên server; membership trong group nào là chuyện client, server không biết gì về
   * ratchet tree MLS).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.DELETE, endpoint = "mls/key-packages", type = Type.HTTP)})
  public CompletionStage<byte[]> deleteMlsKeyPackages(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var deviceId = UUIDUtils.parseOrDefault(request.getParam("deviceId"));
    if (deviceId == null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid deviceId");
    }
    return mls.deleteKeyPackagesOfDevice(deviceId, userId)
        .thenApply(unused -> bytes(new JsonObject().put("deviceId", deviceId.toString())));
  }


  /**
   * {@code PUT /conversations/e2e?conversationId=<uuid>} — bật mã hoá đầu cuối cho 1 conversation.
   * Cùng quyền với {@link #requireGroupOwner} (GROUP >2 người CHỈ owner, DM ai cũng bật được) --
   * CHỈ MỘT CHIỀU bật, gọi lại nhiều lần vô hại (idempotent), KHÔNG có endpoint tắt lại (xem
   * {@code ConversationMembershipRegistry#setEncrypted}). Không broadcast EventBus riêng gì --
   * client tự phát hiện qua {@code e2eEnabled} ở lần {@code GET /conversations} kế tiếp (đủ nhanh,
   * bật mã hoá không phải hành động cần phản ứng tức thời như tin nhắn/kick).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.PUT, endpoint = "conversations/e2e", type = Type.HTTP)})
  public CompletionStage<byte[]> setConversationEncrypted(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    UUID conversationId;
    try {
      conversationId = UUID.fromString(request.getParam("conversationId"));
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing/invalid conversationId");
    }
    var finalConversationId = conversationId;
    return requireGroupOwner(finalConversationId, userId)
        .thenCompose(unused -> membership.setEncrypted(finalConversationId))
        .thenApply(unused -> bytes(new JsonObject().put("conversationId", finalConversationId.toString()).put("e2eEnabled", true)));
  }

  /**
   * {@code POST /e2e/to-device} — body JSON {@code {deliveries: [{recipientUserId, type, conversationId
   * (tuỳ chọn), body: {...}}, ...]}}. Gửi hàng loạt tin báo hiệu mã hoá THẲNG cho từng user (thiết
   * lập Olm session, phân phối/rotate Megolm session key) -- KHÔNG đi qua conversation fan-out
   * thường (xem javadoc {@link E2eKeyRegistry}). Mỗi delivery vừa PERSIST (nguồn thật, đọc lại lúc
   * offline qua {@link #drainE2eToDevice}) vừa publish EventBus để relay SỐNG ngay nếu recipient
   * đang online (xem {@link #E2E_TO_DEVICE_ADDRESS}, harbor's RoutingVersionSync lắng nghe).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "e2e/to-device", type = Type.HTTP)})
  public CompletionStage<byte[]> queueE2eToDevice(IRequest request) {
    var senderId = requireAuthenticatedUserId(request);
    var body = parseJsonBody(request);
    var rawDeliveries = body.getJsonArray("deliveries");
    if (rawDeliveries == null || rawDeliveries.isEmpty()) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing deliveries");
    }
    var futures = new ArrayList<CompletableFuture<?>>();
    for (var raw : rawDeliveries) {
      if (!(raw instanceof JsonObject delivery)) {
        continue;
      }
      var recipientUserId = UUIDUtils.parseOrDefault(delivery.getString("recipientUserId"));
      var type = delivery.getString("type");
      var payload = delivery.getJsonObject("body");
      if (recipientUserId == null || type == null || payload == null) {
        continue;
      }
      var conversationId = UUIDUtils.parseOrDefault(delivery.getString("conversationId"));
      var id = UUID.randomUUID();
      var future = e2eKeys
          .queueToDeviceMessage(id, recipientUserId, senderId, conversationId, type, payload.encode())
          .thenAccept(unused -> vertx.eventBus().publish(
              E2E_TO_DEVICE_ADDRESS,
              new JsonObject()
                  .put("id", id.toString())
                  .put("recipientUserId", recipientUserId.toString())
                  .put("senderUserId", senderId.toString())
                  .put("conversationId", conversationId == null ? null : conversationId.toString())
                  .put("type", type)
                  .put("body", payload)))
          .toCompletableFuture();
      futures.add(future);
    }
    return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
        .thenApply(unused -> bytes(new JsonObject().put("count", futures.size())));
  }

  /**
   * {@code GET /e2e/to-device} — lấy hết + xoá sạch hàng đợi to-device của CHÍNH mình, gọi lúc
   * connect/login để bù những tin gửi lúc mình offline (xem {@link E2eKeyRegistry#drainToDeviceMessages}).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "e2e/to-device", type = Type.HTTP)})
  public CompletionStage<byte[]> drainE2eToDevice(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    return e2eKeys.drainToDeviceMessages(userId).thenApply(HallApiHandlers::bytes);
  }

  // ================= Liên kết thiết bị mã hoá (KHÔNG quét QR -- gõ tay mã 6 ký tự) =================
  // Thiết bị MỚI (chưa có Olm.Account cục bộ) xin 1 mã ngắn hạn ở đây, hiển thị cho user gõ tay sang
  // thiết bị ĐÃ bật E2E; thiết bị đó lấy bundle (identity+one-time key) của thiết bị mới, mã hoá
  // NGUYÊN Account thật của mình (Olm 1-1 bình thường, tái dùng {@code POST /e2e/to-device} sẵn có
  // để chuyển gói đó về CHÍNH MÌNH) -- xem frontend's e2eRequestDeviceLink/e2eApproveDeviceLink.
  // Server CHỈ trung chuyển 2 public key (identity+one-time) ở các endpoint dưới đây, KHÔNG BAO GIỜ
  // thấy private key/nội dung gói chuyển giao (đi qua hàng đợi to-device đã mã hoá sẵn từ client).

  /**
   * {@code POST /e2e/device-link/request} — body {@code {identityKey, oneTimeKeyId, oneTimeKey}}
   * (bundle của 1 {@code Olm.Account} TẠM, thiết bị mới tự tạo, chỉ dùng 1 lần cho việc này). Trả
   * {@code {code, expiresInSeconds}} -- mã hết hạn sau 5 phút (xem
   * {@link E2eKeyRegistry#claimDeviceLinkRequest}).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.POST, endpoint = "e2e/device-link/request", type = Type.HTTP)})
  public CompletionStage<byte[]> requestDeviceLink(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var body = parseJsonBody(request);
    var identityKey = body.getString("identityKey");
    var oneTimeKeyId = body.getString("oneTimeKeyId");
    var oneTimeKey = body.getString("oneTimeKey");
    if (identityKey == null || identityKey.isBlank() || oneTimeKeyId == null || oneTimeKey == null) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing identityKey/oneTimeKeyId/oneTimeKey");
    }
    return e2eKeys.createDeviceLinkRequest(userId, identityKey.strip(), oneTimeKeyId, oneTimeKey)
        .thenApply(code -> bytes(new JsonObject().put("code", code).put("expiresInSeconds", 300)));
  }

  /**
   * {@code GET /e2e/device-link/bundle?code=XXXXXX} — thiết bị ĐÃ bật E2E gọi sau khi user gõ đúng
   * mã hiển thị trên thiết bị mới, lấy bundle (identity+one-time key) để tự mã hoá gói chuyển giao
   * PHÍA CLIENT (xem frontend's e2eApproveDeviceLink) rồi gửi qua {@code POST /e2e/to-device} có sẵn
   * (recipientUserId = chính mình). Dùng 1 LẦN -- gọi lại cùng mã sẽ 404 (xem
   * {@link E2eKeyRegistry#claimDeviceLinkRequest}, cùng lý do trả 404 dù mã sai/hết hạn/thuộc user
   * khác: không lộ mã nào từng tồn tại).
   */
  @RegisterHandler(apis = {@RegisterIApi(method = ApiMethod.GET, endpoint = "e2e/device-link/bundle", type = Type.HTTP)})
  public CompletionStage<byte[]> claimDeviceLinkBundle(IRequest request) {
    var userId = requireAuthenticatedUserId(request);
    var code = request.getParam("code");
    if (code == null || code.isBlank()) {
      throw new LegoBusinessException(HallErrorKeys.VALIDATION, "missing code");
    }
    return e2eKeys.claimDeviceLinkRequest(code.strip().toUpperCase(), userId).thenApply(bundle -> {
      if (bundle == null) {
        throw new LegoBusinessException(HallErrorKeys.NOT_FOUND, "mã không đúng hoặc đã hết hạn");
      }
      return bytes(bundle);
    });
  }
}
