var ws = null;
var myUserId = '';
var myUsername = '';
var authToken = '';
var countSent = 0;
var countReceived = 0;
var reconnectDelayMs = 1000;
var RECONNECT_MAX_DELAY_MS = 8000;
// Client tu ping co dinh moi PING_INTERVAL_MS (kieu Slack) thay vi doi server phat hien idle; server van giu lop tu ping du phong cho client khac.
var PING_INTERVAL_MS = 10000;
var pingIntervalId = null;
var conversations = {}; // conversationId -> { label, el, logEl, historyLoaded }
var pendingReadAcks = []; // [{id, conversationId}, ...] -- tin đã hiển thị nhưng CHƯA gửi READ vì lúc đó tab đang nền/mất focus (xem flushPendingReadAcks)
var activeConversationId = null; // conversationId đang hiển thị trong #chatMain (chỉ 1 tại 1 thời điểm, xem selectConversation)
// Badge "chưa đọc" đọc trực tiếp conv.unreadCount từ server (tính bằng SQL) -- không dùng cờ tạm ở client vì reload mất hết (đã gặp thật).
var lastConvList = []; // cache list gần nhất từ GET /conversations, dùng để re-render sidebar cục bộ (vd tăng tạm unreadCount lúc tin sống tới) mà không cần fetch lại
var selectedGroupMemberIds = []; // userId đang chọn làm thành viên group (tab "Tạo group", xem toggleGroupMember)

// userId -> true/false; snapshot ban đầu lấy từ GET /presence, WS (PRESENCE) chỉ báo thay đổi về sau, không tự biết trạng thái ban đầu.
var onlineUserIds = {};
// HERALD_API_BASE: NodePort riêng của herald -- xem herald/helm/templates/services.yaml.
var HERALD_API_BASE = 'http://localhost:31007';

// conversationId -> { userId: timeoutId }; server không có frame "đã dừng gõ" riêng -- tự suy hết hạn bằng timeout.
var typingTimers = {};
var TYPING_EXPIRE_MS = 4000;
// conversationId -> lúc gần nhất MÌNH gửi TYPING đi -- throttle, không gửi mỗi keystroke.
var lastTypingSentAt = {};
var TYPING_THROTTLE_MS = 2500;

// messageId -> { userId: emoji } -- nguồn vẽ lại huy hiệu (renderReactions), cập nhật qua frame REACTION hoặc GET /messages.
var reactionsByMessageId = {};
// messageId -> [userId, ...] (đã loại from_user_id) -- nguồn vẽ "✓✓ đã xem bởi ai" (updateSeenDisplay), cập nhật qua frame SEEN hoặc GET /messages (field seenBy).
var seenByUserIdsByMessageId = {};
// messageId -> body object HIỆN TẠI (đầy đủ, có thể chứa replyTo/forwardedFrom/files ngoài "message")
// -- cần giữ lại để: (1) mở form sửa thì biết chính xác text hiện tại để điền sẵn, (2) gửi frame EDIT
// giữ nguyên các field khác (replyTo/files...), chỉ thay "message". Cập nhật ở appendMessageBubble +
// khi nhận/gửi thành công frame EDIT (xem messages-render.js editMessage/handleMessageEdited).
var rawBodyByMessageId = {};
// messageId -> {fromUserId, conversationId, body} CHO TIN E2E GIẢI MÃ THẤT BẠI (đang hiển thị nhãn
// "chưa nhận được khoá") -- {@code body} ở đây là PHONG BÌ GỐC (ciphertext), khác hẳn rawBodyByMessageId
// (giữ bản đã giải mã/thất bại). Cần giữ riêng vì e2eResolveIncomingBody không cache lúc thất bại nên
// phong bì gốc bị mất ngay sau khi resolve xong nếu không lưu lại đây -- dùng để TỰ THỬ LẠI khi khoá
// tới muộn qua to-device (xem e2eRetryFailedMessagesIn trong mls-crypto.js), thay vì bắt người dùng tự
// F5 mới thấy lại được (bug thật đã gặp: khoá đã lưu xong trong IndexedDB nhưng màn hình không tự vẽ
// lại, chỉ F5 mới lộ ra).
var e2eFailedEnvelopesByMessageId = {};
// Icon ĐỘNG (Lottie JSON, Noto Animated Emoji của Google, tải cục bộ -- xem javadoc <lottie-player>
// trong index.html) thay emoji Unicode để hiển thị đồng nhất mọi OS; emoji vẫn là định danh gửi server
// (chỉ lưu chuỗi emoji, không biết icon) và để chèn vào ô nhập tin.
var REACTIONS = [
    {emoji: '👍', anim: 'images/chat/animated/like.json'},
    {emoji: '❤️', anim: 'images/chat/animated/heart.json'},
    {emoji: '😆', anim: 'images/chat/animated/haha.json'},
    {emoji: '😮', anim: 'images/chat/animated/wow.json'},
    {emoji: '😢', anim: 'images/chat/animated/sad.json'},
    {emoji: '😠', anim: 'images/chat/animated/angry.json'}
];
var REACTION_ANIM_BY_EMOJI = {};
REACTIONS.forEach(function (r) { REACTION_ANIM_BY_EMOJI[r.emoji] = r.anim; });

// Nút "+" trong reaction picker (xem populatePicker/openReactionPicker) cho thả BẤT KỲ emoji nào ngoài
// 6 icon cố định ở trên -- không tự tải trước hàng nghìn file cho toàn bộ bảng emoji, thay vào đó tự suy
// ra URL Lottie JSON của Google Noto Animated Emoji NGAY từ chuỗi emoji (cùng CDN, cùng định dạng file
// với 6 icon cục bộ). Array.from (không phải .split('')) để tách đúng theo code point, không vỡ cặp
// surrogate UTF-16 của các emoji nằm ngoài BMP (đa số emoji hiện đại, vd 👍 = U+1F44D).
function notoAnimUrlForEmoji(emoji) {
    var codepoint = Array.from(emoji).map(function (ch) { return ch.codePointAt(0).toString(16); }).join('_');
    return 'https://fonts.gstatic.com/s/e/notoemoji/latest/' + codepoint + '/lottie.json';
}

// ĐÃ TỪNG tự đo bounding box (bằng toạ độ thô trong JSON, rồi bằng getBBox() trên <svg> thật) để tự bù
// scale cho icon "trông đều nhau" -- BỎ HẲN sau 2 lần vá vẫn không hết: 6 icon cố định (REACTIONS) thì ổn
// vì bản thân 6 file đó vốn đã có tỉ lệ khung gần giống nhau, nhưng popup "+" kéo ngẫu nhiên từ ~1900
// icon Noto Animated Emoji (pháo giấy/tia lửa/lá cờ...) có cách vẽ (đặc kín vs rải rác thành nhiều hạt
// nhỏ) khác nhau quá xa để 1 con số "% khung phủ" đo bằng hình học nói lên đúng "độ lớn thị giác" -- hạt
// confetti rải khắp khung có bbox phủ gần 100% (heuristic tưởng đã đủ to, không bù) nhưng nhìn bằng mắt
// vẫn nhỏ li ti. Không có công thức hình học nào bù đúng hết cho nội dung vẽ tuỳ ý như vậy.
//
// Nhưng bỏ hẳn compensation (scale cố định = 1) làm icon nhìn NHỎ HẲN so với trước: tự đo thử nhiều file
// Noto Animated Emoji thật (kể cả 6 icon cố định) thấy phần vẽ thật bên trong hầu hết chỉ chiếm ~85-95%
// khung gốc (khung luôn chừa lề đều quanh mép để chỗ rung/lắc khi animate), tức là hiển thị "thật" (không
// zoom gì) LUÔN có 1 viền trắng mỏng quanh mọi icon -- khác hẳn icon Unicode tĩnh vẽ sát mép glyph. Thay
// vì tự đo (dễ sai/không đều như đã thấy), zoom vào 1 mức CỐ ĐỊNH, GIỐNG NHAU cho MỌI icon động (không
// phân biệt icon nào) để cắt bớt viền lề đó -- không đo đạc gì, luôn cùng 1 số, nên không thể ra kết quả
// khác nhau giữa các icon (không tái diễn bug "to nhỏ không đều"). overflow:hidden ở khung chứa (.emoji/
// #reactionPicker button/.reactionMoreItem, xem style.css) tự cắt phần tràn ra khi zoom, khung hiển thị
// (sizePx) không đổi. Icon nào vẽ chừa lề nhiều hơn mức trung bình (vd hiệu ứng hạt rải: confetti/pháo
// giấy) vẫn sẽ nhỏ hơn 1 chút -- CHỦ Ý của artwork gốc, không cố bù tiếp (xem đoạn trên).
var REACTION_ANIM_ZOOM = 1.15;

// Cache Storage API (Cache Storage -- CÙNG API Service Worker dùng, nhưng gọi thẳng được từ trang, KHÔNG
// cần đăng ký Service Worker) để cache lại y hệt response JSON đã fetch, SỐNG SÓT qua reload trang -- khác
// hẳn reactionAnimEntryCache/reactionMoreData (biến JS thường, mất sạch mỗi lần F5). Lý do cần thêm tầng
// này dù CDN đã có Cache-Control riêng: (1) 6 icon reaction cố định (images/chat/animated/*.json) do
// CHÍNH server app này phục vụ, đã tự đo bằng curl -I thấy KHÔNG gửi Cache-Control gì cả (chỉ Last-
// Modified) -- mỗi lần F5 trình duyệt phải round-trip lại dù nội dung không đổi; (2) icon Noto Animated
// Emoji lẻ (CDN fonts.gstatic.com, URL "latest") chỉ max-age=172800 (2 ngày). Dùng chung 1 tầng app-level
// cho đồng nhất, không phụ thuộc server nào có cấu hình cache tốt hay không.
var PERSISTENT_JSON_CACHE_NAME = 'pingo-reaction-cache-v1';
// "latest" trong URL gstatic có thể đổi nội dung theo thời gian (Google cập nhật lại icon) nên KHÔNG cache
// vĩnh viễn -- quá hạn vẫn dùng NGAY bản cũ (khỏi chặn UI chờ mạng) nhưng âm thầm tải lại cập nhật cache
// cho lần sau (kiểu "stale-while-revalidate", khớp đúng header gstatic đã tự gợi ý).
var PERSISTENT_JSON_CACHE_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;

function fetchJsonNetwork(url) {
    return fetch(url).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    });
}

// Fetch mạng + ghi lại vào Cache Storage kèm header tự thêm "x-pingo-cached-at" (Response gốc không có
// chỗ nào để biết CHÍNH APP cache lúc nào -- Cache-Control/Date là của server, không phải lúc mình ghi vào
// cache). res.clone() TRƯỚC khi đọc .json() vì body chỉ đọc được 1 lần -- clone dành để ghi cache, bản gốc
// dành để trả JSON ngay cho caller.
function fetchAndCacheJson(cache, url) {
    return fetch(url).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var headers = new Headers(res.headers);
        headers.set('x-pingo-cached-at', String(Date.now()));
        cache.put(url, new Response(res.clone().body, {status: res.status, statusText: res.statusText, headers: headers}))
            .catch(function () {}); // lỗi ghi cache (vd hết quota) không được chặn hiển thị reaction
        return res.json();
    });
}

// Hàm dùng CHUNG cho mọi nơi cần fetch 1 URL trả JSON và cache lại lâu dài (animation reaction lẫn data
// tên/nhóm emoji, xem ensureReactionMoreData trong compose-reactions-pins.js). Cache Storage không có (vd
// Safari chế độ riêng tư cũ, trình duyệt cổ) hoặc lỗi thì tự lùi về fetch mạng thường -- KHÔNG BAO GIỜ để
// thiếu cache làm hỏng tính năng.
function fetchJsonWithPersistentCache(url) {
    if (typeof caches === 'undefined') return fetchJsonNetwork(url);
    return caches.open(PERSISTENT_JSON_CACHE_NAME).then(function (cache) {
        return cache.match(url).then(function (cachedRes) {
            if (!cachedRes) return fetchAndCacheJson(cache, url);
            var cachedAt = Number(cachedRes.headers.get('x-pingo-cached-at')) || 0;
            var isStale = Date.now() - cachedAt > PERSISTENT_JSON_CACHE_MAX_AGE_MS;
            return cachedRes.json().then(function (data) {
                if (isStale) fetchAndCacheJson(cache, url).catch(function () {});
                return data;
            });
        });
    }).catch(function () { return fetchJsonNetwork(url); });
}

function loadReactionAnimJson(url) {
    var cached = reactionAnimEntryCache[url];
    if (cached) return Promise.resolve(cached);
    return fetchJsonWithPersistentCache(url).then(function (data) {
        reactionAnimEntryCache[url] = data;
        return data;
    });
}
// Cache CHUNG cho mọi nơi render reaction (badge dưới tin, 6 icon picker chính, lưới popup "+") -- giữ
// JSON ĐÃ PARSE trong RAM 1 LẦN DUY NHẤT cho mỗi URL rồi dùng lại mãi trong phiên hiện tại, kể cả khi DOM
// bị dựng lại (vd gõ tìm kiếm trong popup "+" xoá sạch lưới cũ). Tầng NÀY mất khi F5 -- tầng sống sót qua
// F5 là fetchJsonWithPersistentCache (Cache Storage) phía trên.
var reactionAnimEntryCache = {}; // url -> JSON đã parse

// Gắn 1 <lottie-player> vào containerEl, khung hiển thị CỐ ĐỊNH đúng sizePx, zoom vào giữa 1 mức CỐ ĐỊNH
// REACTION_ANIM_ZOOM (không đo/không phụ thuộc icon nào -- xem javadoc phía trên). onFail(): gọi khi tải/
// parse lỗi (lùi về emoji thô). autoplay=false: dựng xong CHỈ đứng yên ở khung hình đầu (không tự
// play+loop) -- dùng cho lưới popup "+" (xem renderReactionMoreGrid): ~40-90 icon cùng hiện trong khung
// nhìn 1 lúc, mỗi icon 1 vòng lặp rAF riêng CÙNG LÚC (BUG THẬT đã tự thấy: giật/lag rõ khi cuộn) -- chỉ
// play() khi thật sự hover (xem attachReactionMoreHover) để tại 1 thời điểm hiếm khi có quá 1-2 icon đang
// chạy animation cùng lúc. Mặc định (không truyền) vẫn autoplay như cũ cho khay 6 icon + badge dưới tin
// nhắn (số lượng nhỏ, không cần tiết kiệm).
//
// BUG THẬT đã tự thấy (không đoán): xoá containerEl.innerHTML NGAY LẬP TỨC trước khi <lottie-player> có
// gì để vẽ -- ở lưới popup "+" (IntersectionObserver huỷ/dựng lại liên tục lúc cuộn, xem
// renderReactionMoreGrid), cuộn 1 icon ra rồi cuộn lại thấy 1 khung TRẮNG chớp qua trước khi icon hiện lại,
// DÙ loadReactionAnimJson đã lấy JSON từ cache RAM/Cache Storage chỉ mất vài ms -- vì lottie-web vẫn cần
// thời gian TỰ DỰNG LẠI SVG mỗi lần .load() (không cache được bước này, chỉ cache được bước TẢI JSON).
// Sửa: dựng <lottie-player> NGẦM (display:none nhưng vẫn gắn thật vào DOM -- customElement cần
// connectedCallback chạy mới khởi tạo đúng, không thể giữ rời rạc ngoài containerEl), đợi .load() xong mới
// xoá nội dung cũ (glyph tĩnh...) và hiện lp ra CÙNG 1 nhịp, không có khoảng trắng ở giữa.
//
// BUG THẬT của thư viện @lottiefiles/lottie-player (repo GITHUB ĐÃ ARCHIVE 20/06/2026, không còn ai vá):
// .load() có thể "thành công" (resolve, currentState báo "playing" bình thường, KHÔNG throw/lỗi console)
// nhưng <svg> bên trong lại RỖNG HOÀN TOÀN (0 phần tử con) -- tự đo thấy xảy ra ở 2 tình huống KHÁC NHAU,
// không đoán: (1) huỷ nhiều player đang chạy rồi dựng lại nhiều player mới CÙNG LÚC (xem populatePicker
// trong compose-reactions-pins.js -- đã sửa bằng cách không huỷ/dựng lại DOM khay 6 icon cố định nữa vì
// nội dung không đổi), (2) NGAY CẢ lần dựng ĐẦU TIÊN, tải nhiều animation ĐỒNG THỜI (vd 6 icon trong khay,
// hoặc nhiều badge reaction dưới nhiều tin nhắn cùng lúc) thỉnh thoảng có 1-2 cái random bị lỗi này (không
// cố định emoji nào, không phải do dữ liệu, thử lại là hết) -- và badge reaction dưới tin nhắn thì KHÔNG
// thể áp dụng "chỉ dựng 1 lần" như khay 6 icon vì nội dung THẬT SỰ đổi mỗi khi có người thả/gỡ reaction
// (renderReactions huỷ+dựng lại cả hàng reaction mỗi lần, xem messages-render.js).
//
// Không sửa được tận gốc trong thư viện đã archive -- tự dò (getBBox()/children.length của chính svg vừa
// dựng, xem checkRenderedOk) rồi TỰ THỬ LẠI (dựng hẳn 1 <lottie-player> MỚI, KHÔNG dùng lại con cũ vì đã
// xác nhận hỏng) tối đa 2 lần nữa trước khi đầu hàng lùi về emoji tĩnh -- attempt = tự truyền lại khi đệ
// quy, code gọi mountReactionAnim từ bên ngoài luôn để trống (mặc định 1).
var REACTION_ANIM_MAX_ATTEMPTS = 3;
function checkReactionAnimRenderedOk(lp) {
    var svg = lp.shadowRoot && lp.shadowRoot.querySelector('.animation svg');
    return !!(svg && svg.children.length > 0);
}
function mountReactionAnim(containerEl, emoji, sizePx, onFail, autoplay, attempt) {
    attempt = attempt || 1;
    var url = REACTION_ANIM_BY_EMOJI[emoji] || notoAnimUrlForEmoji(emoji);
    var lp = document.createElement('lottie-player');
    lp.setAttribute('background', 'transparent');
    lp.setAttribute('speed', '1');
    lp.style.width = sizePx + 'px';
    lp.style.height = sizePx + 'px';
    lp.style.transform = 'scale(' + REACTION_ANIM_ZOOM + ')';
    lp.style.display = 'none';
    containerEl.appendChild(lp);
    loadReactionAnimJson(url).then(function (data) {
        // loadReactionAnimJson trả về ĐÚNG 1 OBJECT JS DÙNG CHUNG cho mọi lần mount cùng emoji (cache theo
        // URL) -- lottie-web tự MUTATE object animationData lúc render (gắn cache nội bộ/cờ "đã tính" lên
        // thẳng các keyframe bên trong), nên PHẢI clone (JSON.parse(JSON.stringify(...)) -- animationData
        // chỉ là dữ liệu thuần JSON) TRƯỚC MỖI LẦN load() để mỗi <lottie-player> luôn nhận 1 bản RIÊNG,
        // không ai mutate chung với ai (tự đo thấy nếu dùng chung object, player thứ 2 trở đi luôn hỏng).
        var dataForThisInstance = JSON.parse(JSON.stringify(data));
        // .load() (khác thuộc tính src) tự đặt loop:false/autoplay:false bên trong lottie-web -- phải tự
        // bật lại loop + play NGAY SAU KHI load() resolve (lúc đó this._lottie chắc chắn đã tồn tại). Riêng
        // autoplay=false: giữ loop=true sẵn (để attachReactionMoreHover chỉ cần gọi play() lúc hover) nhưng
        // KHÔNG tự play -- lottie-player tự đứng ở khung hình đầu (không phải màn hình trắng).
        return lp.load(dataForThisInstance).then(function () {
            // Trong lúc đang tải, item có thể đã rời khung nhìn (containerEl.innerHTML bị reset về glyph
            // tĩnh, xem renderReactionMoreGrid) hoặc bị 1 lần mount khác thay thế -- lp lúc này đã bị gỡ
            // khỏi containerEl, KHÔNG được đụng vào nội dung hiện tại của containerEl nữa (tránh xoá nhầm
            // nội dung đúng của lần mount/trạng thái mới hơn).
            if (lp.parentElement !== containerEl) return;
            lp.loop = true;
            if (autoplay !== false) lp.play();
            Array.from(containerEl.children).forEach(function (child) { if (child !== lp) child.remove(); });
            lp.style.display = 'block';
            // Đợi 1 khung hình cho <svg> kịp dựng (nếu dựng được) rồi mới đo -- dựng thành công thì gần như
            // ngay lập tức, còn dựng hỏng thì CHỜ BAO LÂU CŨNG KHÔNG TỰ HẾT (đã tự đo, chờ thêm 1.5s vẫn
            // rỗng), nên 1 khung hình là đủ để phân biệt 2 trường hợp, không cần chờ lâu hơn.
            requestAnimationFrame(function () {
                if (lp.parentElement !== containerEl || checkReactionAnimRenderedOk(lp)) return;
                if (attempt >= REACTION_ANIM_MAX_ATTEMPTS) { if (onFail) onFail(); return; }
                containerEl.innerHTML = ''; // lp này đã xác nhận hỏng (rỗng) -- bỏ hẳn, không giữ lại
                mountReactionAnim(containerEl, emoji, sizePx, onFail, autoplay, attempt + 1);
            });
        });
    }).catch(function () { if (onFail) onFail(); });
}
var reactionPickerTarget = null; // {conversationId, messageId} đang mở picker cho tin nào
var reactionPickerTriggerEl = null; // nút 🙂 đã mở picker hiện tại -- giữ hiện cưỡng bức (class "picker-open") tới khi đóng

// localStorage cache token JWT thật (do colony cấp) để khỏi đăng nhập lại mỗi lần mở trang, tới khi hết hạn (xem AUTH_ERROR).
var STORAGE_TOKEN_KEY = 'pingoAuthToken';
var STORAGE_ID_KEY = 'pingoUserId';
var STORAGE_USERNAME_KEY = 'pingoUsername';

// NodePort riêng của colony cho REST API -- đổi theo publicHttp.port nếu chạy local bằng "mvn exec:java" thay vì k3s.
var HISTORY_API_BASE = 'http://localhost:31002';

// NodePort của file-server (upload/download ảnh-video); metadata (id/path/mime) do hall cấp phát (xem HallApiHandlers/FileRegistry), file thật lưu/đọc trên file-server.
var FILE_SERVER_BASE = 'http://localhost:31008';
// Khớp "upload.max: 20m" của file-server -- chặn sớm phía client cho nhanh, server vẫn tự chặn lại.
var MAX_UPLOAD_BYTES = 1024 * 1024 * 1024;
// Giới hạn UI khi chọn nhiều file cùng lúc -- mỗi file vẫn upload+gửi thành 1 tin riêng (không có khái niệm "1 tin nhiều file" ở server).
var MAX_PENDING_FILES = 10;

var knownUsers = []; // [{id, username, firstSeenAt, avatarFileId}, ...] từ GET /users
var usernameById = {}; // cache tra nhanh id -> username khi hiển thị chat log
var avatarFileIdById = {}; // cache tra nhanh id -> avatarFileId (ảnh đại diện, có thể null), xem conversationAvatar()

// true từ khi enterApp() chạy tới khi logout()/AUTH_ERROR -- connect()/ws.onclose dùng để biết có nên tự mở/giữ WebSocket.
var identityConfirmed = false;

// Icon outline tự vẽ (SVG, stroke="currentColor"), không phụ thuộc icon font/CDN ngoài để index.html chạy được khi mở trực tiếp bằng file://.
var ICON = {
    search: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>',
    star: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26"/></svg>',
    // edit/trash/pin/reply/lock/link dùng Font Awesome (CDN) thay vì SVG ở đây.
    info: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
    paperclip: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>',
    plus: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>',
    sparkle: '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M12 2l1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2z"/></svg>',
    sticker: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H8a5 5 0 0 0-5 5v8a5 5 0 0 0 5 5h6l6-6V8a5 5 0 0 0-5-5z"/><path d="M14 21v-3a3 3 0 0 1 3-3h3"/></svg>',
    mail: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 6l-10 7L2 6"/><rect x="2" y="4" width="20" height="16" rx="2"/></svg>',
    users: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
    refresh: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>',
    hash: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/><line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/></svg>',
    clock: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></svg>',
    activity: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
    messageCircle: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
    back: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>',
    send: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>',
    down: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></svg>',
    emoji: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" y1="9" x2="9.01" y2="9"/><line x1="15" y1="9" x2="15.01" y2="9"/></svg>',
    play: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
    close: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="6" y1="18" x2="18" y2="6"/></svg>',
    closeSm: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="6" y1="18" x2="18" y2="6"/></svg>',
    chevronLeft: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>',
    chevronRight: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>',
    image: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
    contrast: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 0 0 18z" fill="currentColor" stroke="none"/></svg>',
    sun: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4.5"/><line x1="12" y1="1.5" x2="12" y2="4"/><line x1="12" y1="20" x2="12" y2="22.5"/><line x1="4.2" y1="4.2" x2="6" y2="6"/><line x1="18" y1="18" x2="19.8" y2="19.8"/><line x1="1.5" y1="12" x2="4" y2="12"/><line x1="20" y1="12" x2="22.5" y2="12"/><line x1="4.2" y1="19.8" x2="6" y2="18"/><line x1="18" y1="6" x2="19.8" y2="4.2"/></svg>',
    moon: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/></svg>',
    monitor: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="4" width="19" height="13" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>'
};
// Nút tĩnh trong HTML (không tự vẽ lại qua JS) -- gán icon 1 lần lúc script chạy, DOM đã có sẵn vì
// <script> nằm cuối <body>.
document.getElementById('sidebarSearchIcon').innerHTML = ICON.search;
document.getElementById('newConvSearchIcon').innerHTML = ICON.search;
document.getElementById('globalSearchBtn').innerHTML = ICON.search;
document.getElementById('newConvCloseBtn').innerHTML = ICON.closeSm;
document.getElementById('userListRefreshBtn').innerHTML = ICON.refresh;
document.getElementById('convListRefreshBtn').innerHTML = ICON.refresh;
document.getElementById('infoStatusIcon').innerHTML = ICON.activity;
document.getElementById('infoTypeIcon').innerHTML = ICON.messageCircle;
document.getElementById('infoMemberIcon').innerHTML = ICON.users;
document.getElementById('infoActivityIcon').innerHTML = ICON.clock;
document.getElementById('infoConvIdIcon').innerHTML = ICON.hash;

