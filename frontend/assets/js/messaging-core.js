// PHẢI là UUID thật (không phải chuỗi tuỳ ý như trước) -- id của frame MESSAGE được colony dùng
// LUÔN làm primary key khi lưu bảng messages (xem ChatSessionManager#persistMessage), và SEEN/
// REACTION gửi sau này tham chiếu lại ĐÚNG id đó -- id không phải UUID sẽ khiến "seen"/"reactions"
// không bao giờ khớp lại được sau reload (đã gặp thật, xem lịch sử sửa).
function newId() {
    return crypto.randomUUID();
}

function send(frame) {
    ws.send(JSON.stringify(frame));
}

// Gửi bù READ cho mọi tin đã hiển thị lúc tab còn nền/mất focus (xem pendingReadAcks trong
// ws.onmessage case MESSAGE) -- gọi mỗi khi tab thực sự quay lại active, không phải chỉ 1 lần lúc nhận.
// Gửi READ cho 1 tin -- dùng CHUNG cho mọi đường đánh dấu "đã đọc": tin sống tới lúc đang neo đáy
// (xem ws.onmessage case MESSAGE), tin cũ/mới tự lọt vào khung nhìn qua IntersectionObserver (xem
// ensureConversationCard), hay cuộn tay xuống xem sau khi bấm nút "xuống cuối". Giữ nguyên quy tắc
// cũ: tab đang nền/mất focus thì xếp hàng (pendingReadAcks), gửi bù khi tab quay lại active.
function sendReadReceipt(conversationId, messageId) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return; // socket chưa/không còn sống (vd đang reconnect) -- bỏ qua, không phải luồng chính
    if (document.visibilityState === 'visible' && document.hasFocus()) {
        send({type: 'READ', id: messageId, conversationId: conversationId});
    } else {
        pendingReadAcks.push({id: messageId, conversationId: conversationId});
    }
}

// Log đang cuộn gần đáy (trong khoảng NEAR_BOTTOM_PX) hay không -- ngưỡng thay vì so bằng tuyệt đối
// (scrollTop lẻ vài px do làm tròn subpixel gần như luôn khác scrollHeight-clientHeight tuyệt đối).
var NEAR_BOTTOM_PX = 64;
function isNearBottom(logEl) {
    return logEl.scrollHeight - logEl.scrollTop - logEl.clientHeight < NEAR_BOTTOM_PX;
}

// Cuộn MƯỢT xuống đáy -- dùng cho các trường hợp "cuộn thấy được" (nút xuống cuối, tin mới tới trong
// lúc đang neo đáy): behavior:'smooth' chỉ áp dụng ĐÚNG lần gọi này (không đụng CSS scroll-behavior
// của .conv-log), nên KHÔNG được đổi công thức "logEl.scrollTop = ..." (gán tức thời) ở những chỗ
// khác như bù chiều cao ô soạn tin (composeResizeObserver) hay neo vị trí lúc mới mở conversation
// (applyScrollAnchor) -- 2 chỗ đó cần đúng NGAY LẬP TỨC, animate vào sẽ gây giật/lệch vị trí thật.
function smoothScrollToBottom(logEl) {
    logEl.scrollTo({top: logEl.scrollHeight, behavior: 'smooth'});
}

// Số hiện trên chấm đỏ = entry.totalUnreadCount -- KHỞI TẠO từ số CHÍNH XÁC server đếm bằng SQL
// COUNT lúc mở lại conversation (xem GET /read-cursor, MessageHistoryRegistry#getReadCursor), KHÔNG
// PHẢI đếm theo số phần tử client đã lazy-load được (đã gặp thật: client chỉ tải dần từng trang 30
// tin/lần nên số hiện luôn bị chặn ở đúng cỡ 1 trang dù thực tế còn nhiều hơn, "sao con số hiển thị
// trên chấm max là 30 à??"). Từ số gốc chính xác đó, CHỈ tăng khi có tin SỐNG thật sự mới tới (không
// phải do lazy-load nạp tiếp phần vốn đã được server đếm rồi, xem appendMessageBubble) và CHỈ giảm
// đúng lúc 1 tin THẬT SỰ lọt khung nhìn (readObserver) hoặc bị xoá -- entry.unreadIdSet chỉ còn dùng
// để biết CHÍNH XÁC tin nào đang "chờ observer", không dùng size của nó để hiển thị số nữa.
function updateJumpBadge(entry) {
    if (!entry.jumpBadgeEl) return;
    var count = entry.totalUnreadCount || 0;
    if (count > 0) {
        // Hiện ĐÚNG số thật, không rút gọn thành "9+" -- yêu cầu là "hiển thị chính xác có bao nhiêu
        // tin chưa đọc", rút gọn sẽ trông như bị "kẹt" ở 9+ suốt trong lúc số thật vẫn đang giảm dần.
        entry.jumpBadgeEl.innerText = String(count);
        entry.jumpBadgeEl.classList.add('show');
    } else {
        entry.jumpBadgeEl.classList.remove('show');
    }
}

// Có tin MỚI tới trong lúc người dùng đang cuộn lên xem tin cũ hơn (không neo đáy) -- KHÔNG tự cuộn
// xuống (sẽ giật mất chỗ đang đọc dở), chỉ hiện nút (tin đó đã được add vào entry.unreadIdSet ở nơi
// gọi, xem appendMessageBubble) để tự bấm xuống xem khi sẵn sàng (đúng yêu cầu).
function bumpJumpBadge(entry) {
    updateJumpBadge(entry);
    if (entry.jumpBtnEl) entry.jumpBtnEl.classList.add('show');
}

function flushPendingReadAcks() {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (document.visibilityState !== 'visible' || !document.hasFocus()) return;
    if (pendingReadAcks.length === 0) return;
    var toSend = pendingReadAcks;
    pendingReadAcks = [];
    toSend.forEach(function (ack) {
        send({type: 'READ', id: ack.id, conversationId: ack.conversationId});
    });
}
document.addEventListener('visibilitychange', flushPendingReadAcks);
window.addEventListener('focus', flushPendingReadAcks);

function setStatus(text, cls) {
    var el = document.getElementById('status');
    el.innerText = text;
    el.className = 'pill ' + (cls || '');
}

// --- UI cho 1 conversation (card riêng, log + ô gửi riêng) ---
// Card được chèn thẳng vào #chatMain, mặc định ẩn (CSS ".conv" không có ".active") -- chỉ hiện khi
// selectConversation() gán class "active", xem CSS. Nhờ vậy nhận tin cho 1 conversation KHÔNG đang
// mở vẫn ghi log/giữ trạng thái bình thường (không mất tin), chỉ là chưa hiển thị ra màn hình.
// ===== Hình nền riêng cho từng cuộc trò chuyện =====
// Lưu THUẦN client-side (localStorage) -- đây là tuỳ biến hiển thị cá nhân (giống Messenger/Zalo,
// mỗi người tự chọn nền/màu bong bóng riêng cho máy/trình duyệt của mình), KHÔNG phải dữ liệu nghiệp
// vụ cần đồng bộ qua server/tới người khác trong hội thoại, nên không cần thêm field nào ở body tin
// nhắn hay API mới.
var BG_STORAGE_KEY = 'pingoConvBackgrounds_v1';
var BG_PRESET_COLORS = ['#f5f1ea', '#eaf2f6', '#eef7ee', '#fdf1e8', '#f7edf5', '#e9edf7', '#fdecec', '#eef3f0', '#2b2b33', '#1c2b3a'];
var BG_PRESET_GRADIENTS = [
    'linear-gradient(135deg, #d9e4ff, #f7d9ff)',
    'linear-gradient(135deg, #ffe8d6, #ffd6e0)',
    'linear-gradient(135deg, #d6fff0, #d6eaff)',
    'linear-gradient(135deg, #fff6d6, #ffd6d6)',
    'linear-gradient(160deg, #2b2540, #4b3f6b)',
    'linear-gradient(160deg, #16323e, #1f5a54)'
];
// Màu bong bóng chat CỦA MÌNH (bên phải, xem .bubble-row.mine) -- #0084ff là màu mặc định gốc (vẫn
// đứng đầu danh sách để dễ quay lại), còn lại chọn 1 bảng màu đủ đậm để CHỮ TRẮNG lên nổi rõ (tránh
// người dùng chọn nhầm màu quá nhạt rồi tự làm chữ khó đọc -- dù contrastTextColor() vẫn tự đổi sang
// chữ đen nếu lỡ có màu nhạt lọt vào, xem applyConversationBubbleColor).
var BUBBLE_PRESET_COLORS = ['#0084ff', '#6d5bf5', '#00b894', '#e17055', '#e84393', '#00b8d9', '#2d3436', '#636e72', '#d63031', '#00cec9'];
// Ảnh tự tải lên lưu dạng data: URI ngay trong localStorage (không có API upload nền riêng ở server)
// -- giới hạn nhỏ để tránh vượt hạn mức localStorage (thường ~5-10MB/origin, dùng chung với token...).
var MAX_BG_IMAGE_BYTES = 1.5 * 1024 * 1024;

function loadBgMap() {
    try { return JSON.parse(localStorage.getItem(BG_STORAGE_KEY)) || {}; } catch (e) { return {}; }
}
function saveBgMap(map) {
    try {
        localStorage.setItem(BG_STORAGE_KEY, JSON.stringify(map));
    } catch (e) {
        // Tràn hạn mức localStorage (thường do ảnh custom quá lớn/nhiều cuộc trò chuyện) -- bỏ qua
        // lặng lẽ, nền chỉ là tuỳ biến hiển thị phụ, không đáng chặn cả tính năng vì lỗi ghi cache này.
        appAlert('Không lưu được (bộ nhớ trình duyệt đầy) -- thử chọn màu/gradient thay vì ảnh, hoặc xoá bớt tuỳ biến đã đặt ở cuộc trò chuyện khác.', 'Không lưu được');
    }
}
// Đọc tuỳ biến của 1 conversation, TỰ NHẬN DIỆN bản ghi cũ (từ trước khi có màu bong bóng -- khi đó
// map[id] LÀ THẲNG object nền {type, value}, không bọc trong {background, bubbleColor}) để không mất
// dữ liệu nền đã chọn từ trước khi tính năng này ra đời.
function getConversationSettings(conversationId) {
    var raw = loadBgMap()[conversationId];
    if (!raw) return {background: null, bubbleColor: null};
    if (raw.type) return {background: raw, bubbleColor: null};
    return {background: raw.background || null, bubbleColor: raw.bubbleColor || null};
}
function saveConversationSettings(conversationId, settings) {
    var map = loadBgMap();
    if (!settings.background && !settings.bubbleColor) delete map[conversationId];
    else map[conversationId] = {background: settings.background, bubbleColor: settings.bubbleColor};
    saveBgMap(map);
}
function getConversationBackground(conversationId) {
    return getConversationSettings(conversationId).background;
}
function setConversationBackground(conversationId, bg) {
    var settings = getConversationSettings(conversationId);
    settings.background = bg;
    saveConversationSettings(conversationId, settings);
}
function getConversationBubbleColor(conversationId) {
    return getConversationSettings(conversationId).bubbleColor;
}
function setConversationBubbleColor(conversationId, color) {
    var settings = getConversationSettings(conversationId);
    settings.bubbleColor = color;
    saveConversationSettings(conversationId, settings);
}

// Vẽ nền đã lưu (nếu có) lên ĐÚNG .conv-log của conversation đó -- gọi lúc dựng card
// (ensureConversationCard) VÀ mỗi lần người dùng đổi lựa chọn trong popup (applyBgChoice).
function applyConversationBackground(conversationId, logEl) {
    var bg = getConversationBackground(conversationId);
    logEl.style.backgroundSize = 'cover';
    logEl.style.backgroundPosition = 'center';
    if (!bg) {
        logEl.style.backgroundColor = '';
        logEl.style.backgroundImage = '';
    } else if (bg.type === 'color') {
        logEl.style.backgroundColor = bg.value;
        logEl.style.backgroundImage = '';
    } else {
        // gradient: bg.value đã là 1 CSS <image> hợp lệ (linear-gradient(...)) -- gán thẳng.
        // image: bg.value là data: URI -- bọc trong url(...).
        logEl.style.backgroundColor = '';
        logEl.style.backgroundImage = bg.type === 'image' ? 'url(' + bg.value + ')' : bg.value;
    }
}

// Chọn chữ trắng hay đen tuỳ độ sáng của màu nền (công thức luminance tương đối rút gọn, đủ chính
// xác để chọn ĐỦ tương phản đọc được -- không cần tính AA ratio đầy đủ) -- người dùng có thể chọn BẤT
// KỲ màu nào (kể cả màu nhạt) nên không thể LUÔN cố định chữ trắng như bản gốc (#0084ff) được nữa.
function contrastTextColor(hex) {
    var c = hex.replace('#', '');
    if (c.length === 3) c = c.split('').map(function (ch) { return ch + ch; }).join('');
    var r = parseInt(c.substr(0, 2), 16), g = parseInt(c.substr(2, 2), 16), b = parseInt(c.substr(4, 2), 16);
    var luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.6 ? '#1b1d29' : '#ffffff';
}

// Vẽ màu bong bóng "mine" đã lưu (nếu có) -- gán 2 CSS custom property lên CHÍNH .conv (thẻ card),
// cascade xuống mọi .bubble-row.mine .bubble bên trong (xem CSS var(--my-bubble-bg)) mà không đụng gì
// tới conversation khác. Không tuỳ biến thì gỡ property đi, rơi về fallback mặc định của var() trong CSS.
function applyConversationBubbleColor(conversationId, cardEl) {
    var color = getConversationBubbleColor(conversationId);
    if (!color) {
        cardEl.style.removeProperty('--my-bubble-bg');
        cardEl.style.removeProperty('--my-bubble-fg');
    } else {
        cardEl.style.setProperty('--my-bubble-bg', color);
        cardEl.style.setProperty('--my-bubble-fg', contrastTextColor(color));
    }
}

var bgPickerConversationId = null;
var bgPickerLogEl = null;
var bgPickerCardEl = null;

function buildBgSwatch(bg, extraClass) {
    var el = document.createElement('div');
    el.className = 'bgSwatch' + (extraClass ? ' ' + extraClass : '');
    el.title = bg ? '' : 'Mặc định (không tuỳ biến)';
    if (bg) {
        if (bg.type === 'color') el.style.backgroundColor = bg.value;
        else el.style.backgroundImage = bg.value;
    }
    el.dataset.bgKey = bg ? (bg.type + ':' + bg.value) : 'default';
    el.onclick = function () { applyBgChoice(bg); };
    return el;
}

function buildBubbleSwatch(color) {
    var el = document.createElement('div');
    el.className = 'bgSwatch bubbleSwatch' + (color ? '' : ' defaultSwatch');
    el.title = color ? '' : 'Mặc định (xanh dương)';
    if (color) el.style.backgroundColor = color;
    el.dataset.bubbleKey = color || 'default';
    el.onclick = function () { applyBubbleChoice(color); };
    return el;
}

function ensureBgPicker() {
    var overlay = document.getElementById('bgPickerOverlay');
    if (overlay) return overlay;
    overlay = document.createElement('div');
    overlay.id = 'bgPickerOverlay';
    overlay.innerHTML =
        '<div class="bgPickerModal">' +
        '<div class="bgPickerHead"><span>Đổi hình nền &amp; màu chat</span>' +
        '<button type="button" class="bgPickerClose" title="Đóng (Esc)">' + ICON.close + '</button></div>' +
        '<div class="bgPickerBody">' +
        '<div class="bgPickerSectionTitle">Màu bong bóng chat của bạn</div>' +
        '<div class="bgSwatchGrid" id="bubbleColorGrid"></div>' +
        '<div class="bgPickerSectionTitle">Màu nền</div>' +
        '<div class="bgSwatchGrid" id="bgColorGrid"></div>' +
        '<div class="bgPickerSectionTitle">Gradient</div>' +
        '<div class="bgSwatchGrid" id="bgGradientGrid"></div>' +
        '<div class="bgPickerSectionTitle">Ảnh của bạn</div>' +
        '<input type="file" accept="image/*" id="bgImageInput" style="display:none">' +
        '<button type="button" class="bgUploadBtn" id="bgUploadBtn">' + ICON.image + ' Chọn ảnh từ máy</button>' +
        '</div></div>';
    overlay.querySelector('.bgPickerClose').onclick = function (e) { e.stopPropagation(); closeBgPicker(); };
    // Bấm ra NGOÀI modal (đúng vào nền tối) mới đóng -- giống #mediaLightbox.
    overlay.onclick = function (e) { if (e.target === overlay) closeBgPicker(); };
    document.body.appendChild(overlay);

    // Ô "Mặc định" luôn đứng đầu -- bấm để XOÁ tuỳ biến, quay lại màu/nền gốc của theme.
    var bubbleGrid = overlay.querySelector('#bubbleColorGrid');
    bubbleGrid.appendChild(buildBubbleSwatch(null));
    BUBBLE_PRESET_COLORS.forEach(function (c) { bubbleGrid.appendChild(buildBubbleSwatch(c)); });

    var colorGrid = overlay.querySelector('#bgColorGrid');
    colorGrid.appendChild(buildBgSwatch(null, 'defaultSwatch'));
    BG_PRESET_COLORS.forEach(function (c) { colorGrid.appendChild(buildBgSwatch({type: 'color', value: c})); });

    var gradientGrid = overlay.querySelector('#bgGradientGrid');
    BG_PRESET_GRADIENTS.forEach(function (g) { gradientGrid.appendChild(buildBgSwatch({type: 'gradient', value: g})); });

    overlay.querySelector('#bgUploadBtn').onclick = function () { overlay.querySelector('#bgImageInput').click(); };
    overlay.querySelector('#bgImageInput').addEventListener('change', function (e) {
        var file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        if (file.size > MAX_BG_IMAGE_BYTES) {
            appAlert('Ảnh quá lớn (tối đa ' + (MAX_BG_IMAGE_BYTES / 1024 / 1024) + 'MB) -- nền ảnh lưu ngay trên trình duyệt, không upload lên server.', 'Ảnh quá lớn');
            return;
        }
        var reader = new FileReader();
        reader.onload = function () { applyBgChoice({type: 'image', value: reader.result}); };
        reader.readAsDataURL(file);
    });
    return overlay;
}

// Đánh dấu ✓ đúng swatch khớp với nền/màu bong bóng ĐANG áp cho cuộc trò chuyện đang mở popup -- ảnh
// tự tải lên không có ô đại diện sẵn trong lưới nên không có gì được đánh dấu trong trường hợp đó
// (chấp nhận được, không phải lỗi).
function refreshBgPickerSelection() {
    var overlay = document.getElementById('bgPickerOverlay');
    if (!overlay || !bgPickerConversationId) return;
    var currentBg = getConversationBackground(bgPickerConversationId);
    var currentBgKey = currentBg ? (currentBg.type + ':' + currentBg.value) : 'default';
    overlay.querySelectorAll('#bgColorGrid .bgSwatch, #bgGradientGrid .bgSwatch').forEach(function (el) {
        el.classList.toggle('selected', el.dataset.bgKey === currentBgKey);
    });
    var currentBubble = getConversationBubbleColor(bgPickerConversationId) || 'default';
    overlay.querySelectorAll('#bubbleColorGrid .bgSwatch').forEach(function (el) {
        el.classList.toggle('selected', el.dataset.bubbleKey === currentBubble);
    });
}

function openBackgroundPicker(conversationId, logEl, cardEl) {
    bgPickerConversationId = conversationId;
    bgPickerLogEl = logEl;
    bgPickerCardEl = cardEl;
    ensureBgPicker().classList.add('show');
    refreshBgPickerSelection();
}

function applyBgChoice(bg) {
    if (!bgPickerConversationId) return;
    setConversationBackground(bgPickerConversationId, bg);
    applyConversationBackground(bgPickerConversationId, bgPickerLogEl);
    refreshBgPickerSelection();
}

function applyBubbleChoice(color) {
    if (!bgPickerConversationId) return;
    setConversationBubbleColor(bgPickerConversationId, color);
    applyConversationBubbleColor(bgPickerConversationId, bgPickerCardEl);
    refreshBgPickerSelection();
}

function closeBgPicker() {
    var overlay = document.getElementById('bgPickerOverlay');
    if (overlay) overlay.classList.remove('show');
    bgPickerConversationId = null;
    bgPickerLogEl = null;
    bgPickerCardEl = null;
}
document.addEventListener('keydown', function (e) {
    var overlay = document.getElementById('bgPickerOverlay');
    if (overlay && overlay.classList.contains('show') && e.key === 'Escape') closeBgPicker();
});

// Quét "@token" trong text KHỚP ĐÚNG username thành viên (không tính chính mình) của conversation
// này -- cùng quy tắc với popup gợi ý lúc gõ (xem updateMentionSuggest): "@all" (nhóm >=2 người
// khác) = nhắc hết, còn lại phải khớp NGUYÊN username (tránh dương tính giả kiểu "@nam2" ăn nhầm
// "@nam"). Trả về mảng userId gửi kèm body.mentionedUserIds cho colony tạo noti (xem sendMsg).
function computeMentionedUserIds(text, conversationId) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    var memberIds = (conv && conv.memberUserIds || []).filter(function (id) { return id !== myUserId; });
    var tokens = text.toLowerCase().split(/\s+/);
    if (memberIds.length >= 2 && tokens.indexOf('@all') !== -1) return memberIds.slice();
    return memberIds.filter(function (id) {
        var uname = usernameById[id];
        return uname && tokens.indexOf('@' + uname.toLowerCase()) !== -1;
    });
}

function ensureConversationCard(conversationId, label, subtitle) {
    if (conversations[conversationId]) return conversations[conversationId];

    var initialLabel = label || 'Conversation';
    var card = document.createElement('div');
    card.className = 'conv';
    card.innerHTML =
        '<div class="conv-head"><div class="headLeft">' +
        '<button class="icon backBtn" title="Quay lại danh sách" onclick="goBackToSidebar()">' + ICON.back + '</button>' +
        '<span class="avatar"></span><div class="titleBlock">' +
        '<span class="convLabel">' + initialLabel + '</span>' +
        '<div class="subtitle"></div><div class="online-status"></div></div></div>' +
        '<div class="headRight">' +
        '<span class="badge">' + conversationId.substring(0, 8) + '…</span>' +
        '<button class="icon" disabled title="Demo chưa hỗ trợ ghim">' + ICON.star + '</button>' +
        '<button class="icon renameBtn" title="Đổi tên riêng">' + ICON.edit + '</button>' +
        '<button class="icon bgBtn" title="Đổi hình nền &amp; màu chat">' + ICON.image + '</button>' +
        '<button class="icon deleteBtn fa-solid fa-trash" title="Xoá hẳn cuộc trò chuyện này"></button>' +
        '<button class="icon infoBtn" title="Ẩn/hiện panel thông tin" onclick="toggleInfoPanel()">' + ICON.info + '</button>' +
        '</div></div>' +
        '<div class="conv-log-wrap"><div class="conv-log"></div>' +
        '<button type="button" class="jumpToBottomBtn" title="Xuống tin mới nhất">' + ICON.down +
        '<span class="jumpBadge"></span></button></div>' +
        '<div class="typing-indicator"><span class="typingDots"><span></span><span></span><span></span></span><span class="typingText"></span></div>' +
        '<div class="conv-send"><div class="composeCard">' +
        '<div class="composePreview" style="display:none"><div class="composePreviewList"></div></div>' +
        '<div class="composeRow">' +
        '<button type="button" class="attachMenuBtn" title="Đính kèm">' + ICON.paperclip + '</button>' +
        '<div class="attachMenu">' +
        '<button type="button" class="attachTrayBtn attachBtn" title="Gửi ảnh/video (chọn được nhiều file)">' + ICON.image + '</button>' +
        '<button type="button" class="attachTrayBtn stickerTrayBtn" title="Sticker">' + ICON.sticker + '</button>' +
        '<button type="button" class="attachTrayBtn gifTrayBtn" title="GIF">GIF</button>' +
        '</div>' +
        '<input type="file" class="fileInput" accept="image/*,video/*" multiple style="display:none">' +
        '<textarea class="composeInput" placeholder="Nhập tin nhắn..." rows="1"></textarea>' +
        '<button type="button" class="composeIconBtn composeEmojiBtn" title="Chèn emoji">' + ICON.emoji + '</button>' +
        '<button type="button" class="composeSendBtn" title="Gửi" disabled>' + ICON.send + '<span class="sendBtnLabel">Gửi</span></button>' +
        '</div>' +
        '</div></div>';
    card.querySelector('.renameBtn').onclick = function () { renameConversation(conversationId); };
    card.querySelector('.deleteBtn').onclick = function () { deleteConversation(conversationId); };
    card.querySelector('.subtitle').innerText = subtitle || '';
    document.getElementById('chatMain').appendChild(card);

    var logEl = card.querySelector('.conv-log');
    // Hình nền + màu bong bóng chat riêng của cuộc trò chuyện này (lưu ở localStorage -- xem
    // applyConversationBackground/applyConversationBubbleColor/openBackgroundPicker) -- áp NGAY lúc
    // dựng card, trước khi tin nhắn đầu tiên kịp render, để không bị "nháy" từ mặc định sang đã chọn.
    applyConversationBackground(conversationId, logEl);
    applyConversationBubbleColor(conversationId, card);
    card.querySelector('.bgBtn').onclick = function () { openBackgroundPicker(conversationId, logEl, card); };
    var convSendEl = card.querySelector('.conv-send');
    var inputEl = card.querySelector('.composeInput');
    // Textarea "gương" ẩn, DÙNG RIÊNG để đo chiều cao cần thiết -- xem lý do chi tiết ở autoGrowComposeInput.
    // rows=1 PHẢI đặt tay -- document.createElement không tự có rows="1" như ô thật (parse từ HTML
    // template), mặc định HTML là rows=2, khiến scrollHeight đo được LUÔN tính theo tối thiểu 2 dòng dù
    // chỉ gõ 1 từ (bug thật đã gặp: ô nhập tự cao gấp đôi ngay từ ký tự đầu, xoá hết cũng không co lại
    // được vì "sàn" 2 dòng đó không đổi theo nội dung).
    var inputMirrorEl = document.createElement('textarea');
    inputMirrorEl.className = 'composeInput composeInputMirror';
    inputMirrorEl.rows = 1;
    inputMirrorEl.tabIndex = -1;
    inputMirrorEl.setAttribute('aria-hidden', 'true');
    document.body.appendChild(inputMirrorEl);
    var fileInputEl = card.querySelector('.fileInput');
    var attachMenuBtnEl = card.querySelector('.attachMenuBtn');
    var attachMenuEl = card.querySelector('.attachMenu');
    var attachBtnEl = card.querySelector('.attachBtn');
    var emojiBtnEl = card.querySelector('.composeEmojiBtn');
    var gifTrayBtnEl = card.querySelector('.gifTrayBtn');
    var stickerTrayBtnEl = card.querySelector('.stickerTrayBtn');
    var sendBtnEl = card.querySelector('.composeSendBtn');
    var previewEl = card.querySelector('.composePreview');
    var previewListEl = card.querySelector('.composePreviewList');
    // Khối xem trước LINK NGAY TRONG Ô NHẬP (pha compose, đặt SÁT TRÊN logEl để bù scroll như với
    // composePreview file) -- chèn ngay sau composePreview file, không đè lên vị trí file.
    var composeLinkPreviewEl = null;
    var composeLinkPreviewState = {url: null, meta: null, declined: false};
    function ensureComposeLinkPreviewEl() {
        if (composeLinkPreviewEl) return composeLinkPreviewEl;
        composeLinkPreviewEl = document.createElement('div');
        // Chèn NGAY TRƯỚC .composeRow nhưng phải chèn vào ĐÚNG cha của nó là .composeCard (không phải
        // .conv-send -- .composeRow là con của .composeCard, insertBefore vào .conv-send sẽ ném
        // NotFoundError và làm chết CẢ handler input, đây chính là bug "dán link không hiện gì").
        // Vị trí này = ngay sau .composePreview file, và vẫn nằm trong .conv-send nên
        // composeResizeObserver (observe .conv-send) vẫn bù scrollTop khi khối này hiện/ẩn.
        card.querySelector('.composeCard').insertBefore(composeLinkPreviewEl, card.querySelector('.composeRow'));
        return composeLinkPreviewEl;
    }
    function renderComposeLinkPreview(url, meta, pending) {
        var host = ensureComposeLinkPreviewEl();
        host.innerHTML = '';
        if (!url) { host.style.display = 'none'; return; }
        host.style.display = 'block';
        var cardEl = document.createElement('div');
        cardEl.className = pending ? 'composeLinkPreview pending' : 'composeLinkPreview';
        if (pending) {
            var thumb = document.createElement('div');
            thumb.className = 'composeLinkPreviewThumb skeleton';
            cardEl.appendChild(thumb);
            var bd = document.createElement('div');
            bd.className = 'composeLinkPreviewBody';
            var dom = document.createElement('div');
            dom.className = 'composeLinkPreviewDomain';
            dom.textContent = hostnameOf(url).toUpperCase();
            bd.appendChild(dom);
            ['b1', 'b2'].forEach(function (cls) {
                var bar = document.createElement('div');
                bar.className = 'composeLinkPreviewSkeletonBar ' + cls;
                bd.appendChild(bar);
            });
            cardEl.appendChild(bd);
        } else {
            var thumb = document.createElement('div');
            thumb.className = 'composeLinkPreviewThumb';
            if (meta && meta.image) {
                var im = document.createElement('img');
                im.src = meta.image;
                im.alt = '';
                im.onerror = function () { thumb.textContent = (meta.domain || 'LINK').slice(0, 8); };
                thumb.appendChild(im);
            } else {
                thumb.textContent = (meta && meta.domain ? meta.domain : hostnameOf(url)).slice(0, 8);
            }
            cardEl.appendChild(thumb);
            var bd = document.createElement('div');
            bd.className = 'composeLinkPreviewBody';
            var dom = document.createElement('div');
            dom.className = 'composeLinkPreviewDomain';
            dom.textContent = (meta && meta.domain) || hostnameOf(url);
            bd.appendChild(dom);
            if (meta && meta.title) {
                var tt = document.createElement('div');
                tt.className = 'composeLinkPreviewTitle';
                tt.textContent = meta.title;
                bd.appendChild(tt);
            }
            if (meta && meta.description) {
                var dd = document.createElement('div');
                dd.className = 'composeLinkPreviewDesc';
                dd.textContent = meta.description;
                bd.appendChild(dd);
            }
            cardEl.appendChild(bd);
        }
        var closer = document.createElement('button');
        closer.type = 'button';
        closer.className = 'composePreviewRemove';
        closer.title = pending ? 'Bo xem truoc' : 'Bo the xem truoc (gui nhu link tran)';
        closer.innerText = '✕';
        closer.onclick = function (e) {
            e.stopPropagation();
            dismissComposeLinkPreview();
            inputEl.focus();
        };
        cardEl.appendChild(closer);
        host.appendChild(cardEl);
    }
    function dismissComposeLinkPreview() {
        composeLinkPreviewState.declined = true;
        composeLinkPreviewState.url = null;
        composeLinkPreviewState.meta = null;
        if (composeLinkPreviewEl) { composeLinkPreviewEl.innerHTML = ''; composeLinkPreviewEl.style.display = 'none'; }
    }
    var composeLinkDebounce = null;
    function maybeFetchComposeLinkPreview() {
        if (composeLinkDebounce) { clearTimeout(composeLinkDebounce); composeLinkDebounce = null; }
        var sole = extractSoleUrl(inputEl.value);
        if (!sole || entry.pendingFiles.length > 0) {
            if (composeLinkPreviewEl) { composeLinkPreviewEl.innerHTML = ''; composeLinkPreviewEl.style.display = 'none'; }
            // Don URL moi sau khi xoas/dan khac text -- reset declined de no co co hoi fetch lai lan sau
            if (!sole) composeLinkPreviewState.declined = false;
            composeLinkPreviewState.url = null;
            composeLinkPreviewState.meta = null;
            return;
        }
        if (composeLinkPreviewState.declined && sole === composeLinkPreviewState.url) return; // user vua bam X tu choi -- khong fetch lai cung URL do
        if (composeLinkPreviewState.meta && sole === composeLinkPreviewState.url) {
            renderComposeLinkPreview(sole, composeLinkPreviewState.meta, false);
            return;
        }
        // Cache cua pha render trong log da co (hall vua tra cho cung URL nay o tin cu) -- lay ngay, khong goi lai
        if (linkPreviewCache[sole]) {
            composeLinkPreviewState.url = sole;
            composeLinkPreviewState.meta = linkPreviewCache[sole];
            composeLinkPreviewState.declined = false;
            renderComposeLinkPreview(sole, linkPreviewCache[sole], false);
            return;
        }
        composeLinkPreviewState.url = sole;
        composeLinkPreviewState.meta = null;
        composeLinkPreviewState.declined = false;
        renderComposeLinkPreview(sole, null, true);
        composeLinkDebounce = setTimeout(function () {
            var atCall = sole;
            fetchLinkPreviewMeta(atCall)
                .then(function (meta) {
                    // Giua chung user da xoa input / xoa X / doi URL khac -- bo qua ket qua cu
                    if (extractSoleUrl(inputEl.value) !== atCall) return;
                    if (composeLinkPreviewState.declined) return;
                    if (!meta || (!meta.title && !meta.description && !meta.image)) {
                        dismissComposeLinkPreview();
                        return;
                    }
                    composeLinkPreviewState.meta = meta;
                    renderComposeLinkPreview(atCall, meta, false);
                })
                .catch(function () {
                    if (extractSoleUrl(inputEl.value) !== atCall) return;
                    composeLinkPreviewEl && (composeLinkPreviewEl.innerHTML = '') && (composeLinkPreviewEl.style.display = 'none');
                });
        }, 420); // debounce 420ms: vua nhan khi go xong, khong spam moi lan 'input'
    }
    var jumpBtnEl = card.querySelector('.jumpToBottomBtn');
    var jumpBadgeEl = card.querySelector('.jumpBadge');

    // Nút "xuống cuối" -- bấm mới THẬT SỰ cuộn xuống xem tin mới (không tự cuộn khi tin tới lúc đang
    // đọc tin cũ hơn ở trên, xem appendMessageBubble/bumpJumpBadge). Cuộn xong rồi thì các tin vừa
    // hiện ra sẽ tự trôi qua IntersectionObserver bên dưới -> tự gửi READ đúng lúc thật sự nhìn thấy,
    // không cần code riêng ở đây.
    jumpBtnEl.onclick = function () {
        entry.stickToBottom = true;
        smoothScrollToBottom(logEl);
        // KHÔNG tự xoá entry.unreadIdSet/badge ở đây -- nhảy thẳng xuống đáy có thể "lướt qua" 1 số
        // tin chưa đọc nằm giữa (chưa từng thật sự lọt vào khung nhìn ở bất kỳ thời điểm nào trong cú
        // nhảy tức thời này), badge phải tiếp tục phản ánh ĐÚNG số còn lại chưa được nhìn thấy thật
        // sự -- readObserver sẽ tự xoá dần đúng những tin NẰM TRONG khung nhìn ở vị trí cuối cùng.
        jumpBtnEl.classList.remove('show');
    };

    // stickToBottom: đang neo ở đáy (gần đáy, không cần chính xác tuyệt đối) hay đang cuộn lên xem
    // tin cũ hơn -- quyết định tin mới có tự cuộn xuống hay chỉ báo (xem appendMessageBubble). Cũng
    // là nơi bắn "cuộn gần tới đỉnh -> tải thêm tin cũ hơn" (phân trang lùi, xem maybeLoadOlder).
    logEl.addEventListener('scroll', function () {
        var nearBottom = isNearBottom(logEl);
        entry.stickToBottom = nearBottom;
        // Nút "xuống cuối" hiện MỖI KHI đang cuộn lên (không neo đáy), không chỉ lúc có tin mới tới
        // -- badge số (nếu có) chỉ là chỉ báo PHỤ thêm vào chứ không phải điều kiện để hiện nút.
        if (nearBottom) {
            // KHÔNG tự xoá entry.unreadIdSet ở đây -- ẩn cả nút (kéo theo badge con bên trong) là đủ,
            // để nguyên Set cho readObserver tự xoá đúng từng tin THẬT SỰ đã lọt khung nhìn.
            jumpBtnEl.classList.remove('show');
            maybeLoadNewer(conversationId);
        } else {
            jumpBtnEl.classList.add('show');
        }
        maybeLoadOlder(conversationId);
    });

    // Đánh dấu "đã đọc" (READ) CHỈ khi tin THỰC SỰ lọt vào khung nhìn (threshold 0.6 = hiện ít nhất
    // 60% chiều cao) -- đúng yêu cầu "thực sự xem tin nào mới read", khác cách cũ (coi như đã xem chỉ
    // vì tin tới lúc conversation đang mở, kể cả khi đang cuộn lên xem tin cũ ở trên, ngoài tầm nhìn
    // thật). root = chính logEl (không phải viewport) vì log là vùng cuộn riêng, không phải cả trang.
    var readObserver = new IntersectionObserver(function (obsEntries) {
        obsEntries.forEach(function (obsEntry) {
            if (!obsEntry.isIntersecting) return;
            var row = obsEntry.target;
            readObserver.unobserve(row);
            var messageId = row.dataset.messageId;
            var msgConversationId = row.dataset.conversationId;
            if (messageId && msgConversationId) sendReadReceipt(msgConversationId, messageId);
            // Tin NÀY vừa thật sự lọt khung nhìn -- bớt đúng 1 khỏi số "chưa đọc" hiện trên chấm đỏ,
            // đồng bộ chính xác dù tin tới từ đợt nạp nào (xem entry.totalUnreadCount/updateJumpBadge).
            if (messageId && entry.unreadIdSet.delete(messageId)) {
                entry.totalUnreadCount = Math.max(0, (entry.totalUnreadCount || 0) - 1);
                updateJumpBadge(entry);
            }
        });
    }, {root: logEl, threshold: 0.6});

    // Chọn ảnh/video xong KHÔNG gửi ngay -- hiện xem trước ngay trong ô nhập, giữ ở đây
    // (entry.pendingFiles, MẢNG -- gửi nhiều file cùng lúc) tới khi bấm Gửi mới thật sự upload+gửi
    // (giống Messenger/Zalo/Telegram) -- xem sendMsg() bên dưới. Chọn thêm lần nữa (input file có
    // "multiple", hoặc bấm nút đính kèm lại) sẽ NỐI THÊM vào danh sách đang chờ, không thay thế --
    // attachBtn chỉ tự disable khi đã CHẠM TRẦN (MAX_PENDING_FILES), không phải hễ có 1 file là khoá
    // luôn như bản chỉ-1-file trước đây.
    var composeCardEl = card.querySelector('.composeCard');
    // Nút gửi đổi màu XÁM -> TÍM ngay khi có gì đó để gửi (chữ đã gõ, HOẶC đã chọn file dù chưa gõ
    // caption) -- gọi lại mỗi khi 1 trong 2 điều kiện đó có thể vừa đổi (gõ phím, chọn/bỏ file, gửi
    // xong reset về rỗng). disabled thật (không chỉ đổi màu) lúc rỗng -- bấm Enter/click lúc đó vốn
    // đã bị chặn ở sendMsg() rồi, disabled chỉ để tránh việc bấm nhầm cursor "trông như bấm được".
    function updateSendButtonState() {
        var hasContent = inputEl.value.trim().length > 0 || entry.pendingFiles.length > 0;
        sendBtnEl.disabled = !hasContent;
        sendBtnEl.classList.toggle('active', hasContent);
    }
    // <textarea> KHÔNG tự cao dần theo số dòng như <input> -- phải tự đo lại mỗi lần nội dung đổi.
    // ĐO QUA BẢN GƯƠNG ẩn (inputMirrorEl, height:auto CỐ ĐỊNH -- xem CSS .composeInputMirror), KHÔNG
    // đo trực tiếp trên ô nhập thật -- trước đây code đặt height='auto' TRÊN CHÍNH ô nhập thật rồi mới
    // đo lại scrollHeight (cần bước này để phát hiện lúc XOÁ BỚT dòng, không thì scrollHeight giữ
    // nguyên chiều cao CŨ lớn hơn thật) -- NHƯNG bước "về auto" đó làm ô nhập thật CO NHỎ LẠI THẬT SỰ
    // đúng trong khoảnh khắc đo (dù chỉ 1 tick trước khi gán lại chiều cao đúng), và vì .conv-send
    // (chứa ô nhập) với .conv-log (khung chat) là 2 anh em cùng cha trong 1 flex column, ô nhập co nhỏ
    // lại tạm thời làm .conv-log tạm thời RỘNG RA -- trình duyệt tự ý clamp lại scrollTop của .conv-log
    // cho vừa khoảng rộng tạm thời đó (hành vi mặc định không thể tắt, không phải bug của app). Kết quả
    // là mỗi lần gõ thêm 1 dòng, phần đã bù trước đó bị trình duyệt âm thầm xoá bớt, càng gõ càng lệch
    // nặng (bug thật đã gặp, xác nhận bằng test có ghi log timeline thật). Đo trên bản gương (đứng
    // ngoài luồng layout, không nằm cạnh .conv-log) thì ô nhập thật + .conv-log không bao giờ bị đụng
    // tới trong lúc đo -- chỉ đổi ĐÚNG 1 LẦN duy nhất sang chiều cao CUỐI CÙNG cần có.
    function autoGrowComposeInput() {
        inputMirrorEl.style.width = inputEl.clientWidth + 'px';
        inputMirrorEl.value = inputEl.value;
        inputEl.style.height = inputMirrorEl.scrollHeight + 'px';
    }
    // Bù scrollTop của .conv-log MỖI KHI khối soạn tin (.conv-send) đổi chiều cao -- vì BẤT KỲ lý do
    // gì (gõ nhiều dòng, hiện/ẩn khối xem trước file khi đính kèm/bỏ đính kèm...), KHÔNG chỉ riêng lúc
    // gõ chữ. Dùng ResizeObserver (không phải gọi tay ở từng nơi) để không bỏ sót -- đã gặp thật: chỉ
    // xử lý lúc gõ chữ thì trường hợp đính kèm file (khối xem trước cao thêm ~60px) hay lúc đang cuộn
    // lên xem tin cũ (trước đó cố tình bỏ qua, tưởng "không đụng vị trí đang đọc" là đủ) vẫn bị che.
    //
    // delta > 0 (khối soạn tin CAO LÊN, khung .conv-log hẹp lại) -- CỘNG THÊM delta vào scrollTop để
    // giữ đúng NỘI DUNG ĐANG NẰM Ở ĐÁY khung nhìn (dù đang neo đáy thật hay chỉ đang xem giữa chừng 1
    // đoạn lịch sử) tiếp tục hiện đủ, không bị rìa dưới (vừa dịch lên do khối soạn tin phình ra) cắt
    // mất -- ngược lại (delta < 0, khối soạn tin thấp xuống) thì trừ đúng bấy nhiêu, đối xứng. Đây là
    // hành vi ĐÚNG cho MỌI trạng thái cuộn (neo đáy hay đang đọc tin cũ), không cần tách 2 nhánh --
    // khi đang neo đáy, công thức này tự động cho ra kết quả "vẫn ở đúng đáy mới", xem lại chứng minh
    // toán trong lịch sử sửa.
    // 0 = "chưa đo được" (card còn display:none, chưa active lần nào) -- lần đo đầu tiên có giá trị
    // thật chỉ để LẤY MỐC, không được coi là "vừa phình ra" mà trừ nhầm vào vị trí cuộn ban đầu
    // (applyScrollAnchor đã tính riêng, xem loadHistory).
    //
    // BUG THẬT ĐÃ GẶP: mốc "lần đo đầu tiên" này KHÔNG ĐƯỢC lấy từ chính lần ResizeObserver báo đầu
    // tiên -- card display:none->hiện ra (activate) VÀ lần gõ đầu tiên của người dùng có thể xảy ra
    // sát nhau tới mức trình duyệt GỘP LUÔN 2 thay đổi kích thước đó thành 1 lần báo DUY NHẤT (đặc
    // tính coalesce của ResizeObserver -- chỉ báo kích thước CUỐI CÙNG, không báo từng bước trung
    // gian). Nếu để lần báo gộp đó tự làm mốc (như code cũ), phần "gõ dòng đầu tiên" bị nuốt mất vĩnh
    // viễn, không bao giờ được bù -- kéo theo scrollTop lệch mãi mãi từ đó về sau (mọi lần bù kế tiếp
    // đều đúng DELTA nhưng tính trên 1 mốc đã sai sẵn), kể cả thu nhỏ lại cũng không về đúng vị trí gốc
    // (đã tái hiện + xác nhận bằng CDP thật, không phải artifact test). Xem entry.resetComposeSendBaseline
    // bên dưới -- selectConversation() gọi hàm đó NGAY SAU KHI card thật sự hiện ra (đo offsetHeight
    // ĐỒNG BỘ, không đợi ResizeObserver), nên mốc luôn chính xác trước khi người dùng kịp gõ gì.
    var lastConvSendHeight = 0;
    var composeResizeObserver = new ResizeObserver(function () {
        var newHeight = convSendEl.offsetHeight;
        if (lastConvSendHeight > 0 && newHeight > 0) {
            var delta = newHeight - lastConvSendHeight;
            if (delta !== 0) {
                // BUG THẬT ĐÃ GẶP (nhánh thứ 2): khi compose CO LẠI (delta < 0) lúc đang neo đáy, ngay
                // khi .conv-log vừa RỘNG RA (clientHeight tăng theo), trình duyệt tự CLAMP scrollTop
                // xuống giới hạn mới NGAY LẬP TỨC (ràng buộc vật lý, không tắt được) -- TRƯỚC KHI dòng
                // này kịp chạy. Cộng thêm delta ÂM lên trên giá trị ĐÃ BỊ CLAMP đó là trừ 2 LẦN, kéo lên
                // trên đáy thật. Đang neo đáy thì tính lại THẲNG vị trí đáy (scrollHeight-clientHeight)
                // từ số đo HIỆN TẠI -- luôn ra đúng dù trình duyệt đã tự clamp gì trước đó, không cần
                // biết chính xác đã bị clamp bao nhiêu. Không neo đáy (đang đọc lịch sử) thì giữ nguyên
                // cách cộng dồn delta cũ -- giữ ĐÚNG vị trí đang đọc, clamp kép hiếm khi xảy ra ở xa đáy.
                if (entry.stickToBottom) {
                    logEl.scrollTop = logEl.scrollHeight - logEl.clientHeight;
                } else {
                    logEl.scrollTop += delta;
                }
            }
        }
        lastConvSendHeight = newHeight;
    });
    composeResizeObserver.observe(convSendEl);
    // Vẽ lại TOÀN BỘ dải xem trước từ entry.pendingFiles (mảng) -- đơn giản hơn tự vá từng thẻ 1 khi
    // thêm/bớt, số lượng trong demo này luôn nhỏ (tối đa MAX_PENDING_FILES) nên dựng lại cả dải mỗi
    // lần không tốn kém gì đáng kể.
    function renderPendingFilePreviews() {
        previewListEl.innerHTML = '';
        entry.pendingFiles.forEach(function (item, index) {
            var itemEl = document.createElement('div');
            itemEl.className = 'composePreviewItem';
            itemEl.title = item.file.name;
            var thumbEl = document.createElement('div');
            thumbEl.className = 'composePreviewThumb';
            var isVideo = (item.file.type || '').indexOf('video/') === 0;
            var mediaEl = document.createElement(isVideo ? 'video' : 'img');
            mediaEl.src = item.objectUrl;
            if (isVideo) mediaEl.muted = true;
            thumbEl.appendChild(mediaEl);
            itemEl.appendChild(thumbEl);
            var removeBtn = document.createElement('button');
            removeBtn.type = 'button';
            removeBtn.className = 'composePreviewRemove';
            removeBtn.title = 'Bỏ file này';
            removeBtn.innerText = '✕';
            // Đóng chặt "index" đúng CỦA LẦN VẼ NÀY qua closure -- removePendingFileAt tự vẽ lại toàn
            // bộ dải sau khi xoá nên các nút còn lại (được tạo mới hoàn toàn) luôn khớp đúng index hiện tại.
            removeBtn.onclick = function (e) { e.stopPropagation(); removePendingFileAt(index); };
            itemEl.appendChild(removeBtn);
            previewListEl.appendChild(itemEl);
        });
        var hasFiles = entry.pendingFiles.length > 0;
        previewEl.style.display = hasFiles ? 'block' : 'none';
        composeCardEl.classList.toggle('hasPreview', hasFiles);
        var atCap = entry.pendingFiles.length >= MAX_PENDING_FILES;
        attachBtnEl.disabled = atCap;
        attachBtnEl.style.opacity = atCap ? '.5' : '';
        updateSendButtonState();
    }
    function removePendingFileAt(index) {
        var item = entry.pendingFiles[index];
        if (!item) return;
        URL.revokeObjectURL(item.objectUrl);
        entry.pendingFiles.splice(index, 1);
        renderPendingFilePreviews();
    }
    function clearAllPendingFiles() {
        entry.pendingFiles.forEach(function (item) { URL.revokeObjectURL(item.objectUrl); });
        entry.pendingFiles = [];
        renderPendingFilePreviews();
    }
    // fileList: FileList thật từ <input type=file multiple> (chọn 1 lần được nhiều file) -- NỐI THÊM
    // vào entry.pendingFiles đang có (không thay thế), cho phép bấm đính kèm nhiều lần để gộp dần.
    // Chặn ở CHÍNH XÁC MAX_PENDING_FILES (không cắt bớt âm thầm) -- báo rõ nếu người dùng chọn dư.
    function stagePendingFiles(fileList) {
        var remaining = MAX_PENDING_FILES - entry.pendingFiles.length;
        if (remaining <= 0) {
            appAlert('Chỉ gửi được tối đa ' + MAX_PENDING_FILES + ' file trong 1 lần.', 'Quá số lượng cho phép');
            return;
        }
        var files = Array.prototype.slice.call(fileList);
        var toAdd = files.slice(0, remaining);
        var skippedTooManyCount = files.length - toAdd.length;
        var skippedTooBigNames = [];
        toAdd.forEach(function (file) {
            if (file.size > MAX_UPLOAD_BYTES) {
                skippedTooBigNames.push(file.name);
                return;
            }
            entry.pendingFiles.push({file: file, objectUrl: URL.createObjectURL(file)});
        });
        renderPendingFilePreviews();
        inputEl.focus();
        if (skippedTooBigNames.length) {
            appAlert('Bỏ qua file quá lớn (tối đa ' + (MAX_UPLOAD_BYTES / 1024 / 1024) + 'MB): ' + skippedTooBigNames.join(', '), 'File quá lớn');
        }
        if (skippedTooManyCount > 0) {
            appAlert('Chỉ thêm được ' + toAdd.length + '/' + files.length + ' file đã chọn (tối đa ' + MAX_PENDING_FILES + ' file trong 1 lần).', 'Vượt giới hạn');
        }
    }
    // .attachMenu gắn NGAY TRONG card này (khác #reactionPicker/#gifPicker dùng chung 1 phần tử toàn
    // cục) -- bấm .attachMenuBtn chỉ bật/tắt ĐÚNG cái của card này, đóng mọi popup KHÁC (kể cả .attachMenu
    // của card khác, nếu lỡ đang mở -- xem closeAttachMenu/openAttachMenuEl).
    attachMenuBtnEl.onclick = function (e) {
        e.stopPropagation();
        if (openAttachMenuEl === attachMenuEl) { closeAttachMenu(); return; } // đang mở đúng cái này -- bấm lại để đóng
        closeReactionPicker();
        closeComposeEmojiPicker();
        closeGifPicker();
        closeStickerPicker();
        closeAttachMenu();
        attachMenuEl.classList.add('show');
        openAttachMenuEl = attachMenuEl;
        positionComposeEmojiPicker(attachMenuBtnEl, attachMenuEl); // dùng chung logic định vị với #composeEmojiPicker
    };
    // Bấm bất kỳ đâu TRONG .attachMenu không bị coi là "bấm ra ngoài" -- cùng lý do với các popup khác.
    attachMenuEl.addEventListener('click', function (e) { e.stopPropagation(); });
    attachBtnEl.onclick = function () { closeAttachMenu(); fileInputEl.click(); };
    emojiBtnEl.onclick = function (e) {
        e.stopPropagation(); // không thì document click listener (đóng picker) chạy ngay sau khi vừa mở
        openComposeEmojiPicker(emojiBtnEl, inputEl);
    };
    // Định vị theo attachMenuBtnEl (nút "+" LUÔN hiển thị), KHÔNG phải theo chính nút bấm bên trong
    // .attachMenu -- closeAttachMenu() ở trên ẩn .attachMenu (display:none) TRƯỚC KHI popup mới kịp đo
    // vị trí, mà 1 phần tử nằm trong tổ tiên display:none luôn cho getBoundingClientRect() ra TOÀN SỐ 0
    // (bug thật đã gặp: popup GIF/sticker bị đẩy lên hẳn góc trên cùng màn hình vì "đo" ra toạ độ (0,0)).
    stickerTrayBtnEl.onclick = function (e) {
        e.stopPropagation();
        closeAttachMenu();
        openStickerPicker(attachMenuBtnEl, conversationId);
    };
    gifTrayBtnEl.onclick = function (e) {
        e.stopPropagation();
        closeAttachMenu();
        openGifPicker(attachMenuBtnEl, conversationId);
    };
    fileInputEl.addEventListener('change', function () {
        // Array.prototype.slice.call (KHÔNG chỉ gán thẳng fileInputEl.files) -- .files là 1 FileList
        // SỐNG gắn liền với chính input, gán .value = '' ngay dòng dưới XOÁ LUÔN cả FileList đó (mảng
        // trống dài 0 phần tử) vì cùng 1 trạng thái nội bộ -- phải chụp lại thành mảng THƯỜNG (snapshot
        // độc lập) TRƯỚC khi reset value, không thì stagePendingFiles() luôn nhận về rỗng (đã gặp thật).
        var files = Array.prototype.slice.call(fileInputEl.files);
        fileInputEl.value = ''; // cho phép chọn lại ĐÚNG (những) file đó lần nữa (không đổi gì input cũng không bắn lại "change" nếu không reset)
        if (files.length) stagePendingFiles(files);
    });
    var sendMsg = function () {
        var text = inputEl.value.trim();
        if (entry.pendingFiles.length) {
            // Tin dinh kem file thi phan chu la CAPTION, khong phai "tin chi co 1 link" -- huy khoi
            // xem truoc link dang hien trong o nhap di cho khoi hieu lam (maybeFetchComposeLinkPreview
            // cung tu an khi pendingFiles > 0, day chi la don ngay luc bam gui).
            if (composeLinkDebounce) { clearTimeout(composeLinkDebounce); composeLinkDebounce = null; }
            dismissComposeLinkPreview();
            composeLinkPreviewState.declined = false;
            // Gửi TẤT CẢ file đã chọn trong CÙNG 1 TIN (body.files = [...], xem uploadAndSendFiles) --
            // giống Telegram/Messenger gộp cả album vào 1 bong bóng thay vì tách rời từng ảnh/video
            // thành nhiều tin lẻ. Chú thích gõ kèm (nếu có) gắn CHUNG cho cả tin đó.
            var replyForFiles = pendingReplyTo;
            var files = entry.pendingFiles.map(function (item) { return item.file; });
            clearAllPendingFiles();
            clearPendingReply();
            uploadAndSendFiles(conversationId, files, text, replyForFiles);
            inputEl.value = '';
            autoGrowComposeInput(); // co lại về 1 dòng -- gán value bằng JS không tự bắn "input" nên không tự co
            updateSendButtonState();
            hideMentionSuggest();
            return;
        }
        if (!text) return;
        // PHA 3 -- giong Slack `chat.postMessage` gui kem `unfurl`/`pending_slug_urls`: tin chi chua
        // dung 1 link thi gui KEM `body.preview` da resolve tu luc dang go (pha compose o tren). colony
        // chi viec Json.encode luu nguyen, moi client -- ke ca echo ve chinh minh -- ve card DONG BO
        // dung kich thuoc ngay tu dau, khong con "no" sau lam day cac tin khac (bug goc cua task nay).
        // Chua co meta (bam gui som hon fetch xong, hoac user bam X tu choi) thi CU GUI khong preview:
        // colony tu enrich sau khi persist (xem ChatSessionManager#enrichLinkPreview), tin van di ngay,
        // khong bat user doi -- dung tinh than best-effort cua ca 3 pha.
        var body = {message: text};
        if (pendingReplyTo) body.replyTo = pendingReplyTo;
        // Tự tính SẴN ở client (đã có usernameById/memberUserIds trong tay, khớp @token y hệt cách
        // gợi ý mention lúc gõ -- xem updateMentionSuggest) rồi gửi kèm cho colony -- đỡ phải thêm 1
        // bảng tra username↔userId riêng vào đường xử lý tin nóng chỉ để phục vụ 1 noti phụ, xem
        // ChatSessionManager#notifyReplyAndMentions bên colony.
        var mentionedUserIds = computeMentionedUserIds(text, conversationId);
        if (mentionedUserIds.length) body.mentionedUserIds = mentionedUserIds;
        var soleUrlToSend = extractSoleUrl(text);
        if (soleUrlToSend && !composeLinkPreviewState.declined && composeLinkPreviewState.meta
            && composeLinkPreviewState.url === soleUrlToSend) {
            body.preview = composeLinkPreviewState.meta;
        }
        send({type: 'MESSAGE', id: newId(), conversationId: conversationId, body: body});
        clearPendingReply();
        if (composeLinkDebounce) { clearTimeout(composeLinkDebounce); composeLinkDebounce = null; }
        dismissComposeLinkPreview(); // an khoi xem truoc trong o nhap
        composeLinkPreviewState.declined = false; // reset: lan dan link tiep theo phai duoc fetch lai
        inputEl.value = '';
        autoGrowComposeInput();
        updateSendButtonState();
        hideMentionSuggest();
    };
    sendBtnEl.onclick = sendMsg;

    // ===== @mention: gõ "@" trong ô nhập hiện popup gợi ý thành viên cuộc trò chuyện này =====
    // Chèn cùng kiểu với composeLinkPreviewEl/replyBarEl ở dưới (1 khối flex NGAY TRÊN .composeRow,
    // trong .composeCard) thay vì absolute-position như attachMenu/reactionPicker -- popup này chỉ
    // xuất hiện/biến mất theo nội dung đang gõ, không neo theo 1 nút bấm cụ thể nào.
    var mentionSuggestEl = document.createElement('div');
    mentionSuggestEl.className = 'mentionSuggest';
    card.querySelector('.composeCard').insertBefore(mentionSuggestEl, card.querySelector('.composeRow'));
    var mentionCandidates = []; // [{id, username}, ...] hoặc {id:'all', username:'all', label:'Tất cả mọi người'} -- danh sách ĐANG hiện trong popup, khớp theo thứ tự với các .mentionSuggestItem
    var mentionQueryStart = -1; // vị trí ký tự "@" trong inputEl.value ứng với lượt gợi ý đang mở, -1 = đang đóng
    var mentionSelectedIndex = 0;
    // Tìm "@xxx" đang gõ dở NGAY TRƯỚC con trỏ -- @ phải đứng đầu dòng/ngay sau khoảng trắng (giống
    // quy ước mention thật, tránh khớp nhầm email a@b) và không được có khoảng trắng nào sau nó.
    function findMentionQuery() {
        var caret = inputEl.selectionStart;
        var uptoCaret = inputEl.value.slice(0, caret);
        var m = uptoCaret.match(/(^|[\s])@([^\s@]*)$/);
        if (!m) return null;
        return {query: m[2], start: caret - m[2].length - 1, end: caret};
    }
    function hideMentionSuggest() {
        mentionQueryStart = -1;
        mentionSuggestEl.classList.remove('show');
        mentionSuggestEl.innerHTML = '';
    }
    function renderMentionSuggestHighlight() {
        mentionSuggestEl.querySelectorAll('.mentionSuggestItem').forEach(function (el, i) {
            el.classList.toggle('active', i === mentionSelectedIndex);
        });
    }
    function confirmMentionSelect(index) {
        var picked = mentionCandidates[index];
        if (!picked || mentionQueryStart < 0) return;
        var q = findMentionQuery();
        var endPos = q ? q.end : mentionQueryStart + 1;
        var before = inputEl.value.slice(0, mentionQueryStart);
        var after = inputEl.value.slice(endPos);
        var inserted = '@' + picked.username + ' ';
        inputEl.value = before + inserted + after;
        var caretPos = before.length + inserted.length;
        inputEl.setSelectionRange(caretPos, caretPos);
        hideMentionSuggest();
        autoGrowComposeInput(); // gán .value bằng JS không tự bắn "input" nên không tự co/nở lại
        updateSendButtonState();
        inputEl.focus();
    }
    function updateMentionSuggest() {
        var q = findMentionQuery();
        if (!q) { hideMentionSuggest(); return; }
        var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
        var memberIds = (conv && conv.memberUserIds || []).filter(function (id) { return id !== myUserId; });
        var queryLower = q.query.toLowerCase();
        var candidates = memberIds
            .map(function (id) { return {id: id, username: usernameById[id] || id}; })
            .filter(function (u) { return u.username.toLowerCase().indexOf(queryLower) === 0; });
        // Nhóm (>2 thành viên) mới có "Tất cả mọi người" -- DM chỉ 2 người thì @tên đối phương là đủ,
        // "mention all" trong DM vô nghĩa.
        if (memberIds.length >= 2 && 'all'.indexOf(queryLower) === 0) {
            candidates.unshift({id: 'all', username: 'all', label: 'Tất cả mọi người'});
        }
        if (!candidates.length) { hideMentionSuggest(); return; }
        mentionQueryStart = q.start;
        mentionCandidates = candidates;
        mentionSelectedIndex = 0;
        mentionSuggestEl.innerHTML = '';
        candidates.forEach(function (u, i) {
            var row = document.createElement('div');
            row.className = 'mentionSuggestItem';
            var avatarEl = document.createElement('span');
            avatarEl.className = 'mentionSuggestAvatar';
            if (u.id === 'all') { avatarEl.style.background = 'var(--ink-faint)'; avatarEl.innerHTML = ICON.users; }
            else { avatarEl.style.background = avatarColor(u.id); avatarEl.innerText = avatarInitial(u.username); }
            var nameEl = document.createElement('span');
            nameEl.className = 'mentionSuggestName';
            nameEl.innerText = u.label || u.username;
            row.appendChild(avatarEl);
            row.appendChild(nameEl);
            // mousedown (không phải click) + preventDefault -- giữ nguyên focus/con trỏ trong ô nhập,
            // không thì textarea bị blur TRƯỚC khi click kịp bắn, làm mất đúng vị trí "@query" cần thay.
            row.onmousedown = function (e) { e.preventDefault(); confirmMentionSelect(i); };
            mentionSuggestEl.appendChild(row);
        });
        mentionSuggestEl.classList.add('show');
        renderMentionSuggestHighlight();
    }
    // Đăng ký TRƯỚC handler "Enter = gửi" bên dưới -- Enter/Tab/mũi tên khi popup đang mở phải được
    // bắt Ở ĐÂY (điều hướng/chọn gợi ý) và chặn KHÔNG cho handler gửi tin chạy tiếp
    // (stopImmediatePropagation), thay vì vừa chọn mention vừa lỡ gửi luôn tin nhắn.
    inputEl.addEventListener('keydown', function (e) {
        if (mentionQueryStart < 0) return;
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            mentionSelectedIndex = (mentionSelectedIndex + 1) % mentionCandidates.length;
            renderMentionSuggestHighlight();
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            mentionSelectedIndex = (mentionSelectedIndex - 1 + mentionCandidates.length) % mentionCandidates.length;
            renderMentionSuggestHighlight();
        } else if (e.key === 'Enter' || e.key === 'Tab') {
            e.preventDefault();
            e.stopImmediatePropagation();
            confirmMentionSelect(mentionSelectedIndex);
        } else if (e.key === 'Escape') {
            e.preventDefault();
            e.stopImmediatePropagation();
            hideMentionSuggest();
        }
    });
    inputEl.addEventListener('blur', function () {
        // Trễ 1 nhịp -- click chọn gợi ý dùng mousedown+preventDefault nên KHÔNG tự blur, nhưng vẫn
        // chừa lưới an toàn cho các cách rời focus khác (Tab ra ngoài, bấm nút khác...).
        setTimeout(hideMentionSuggest, 120);
    });

    // Enter = gửi, Shift+Enter = xuống dòng -- đúng quy ước Messenger/Zalo/Slack/Discord. <textarea>
    // mặc định Enter LUÔN xuống dòng, phải preventDefault() để chặn hành vi đó khi KHÔNG giữ Shift.
    inputEl.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMsg();
        }
    });
    // Báo "đang gõ" cho các thành viên khác -- throttle (không gửi mỗi keystroke), im lặng bỏ qua
    // nếu socket chưa sẵn sàng (best-effort, không phải luồng chính).
    // pase LINK TRAN vao o nhap thi trigger pha slugify moi (giong Slack chat.slugifyUrl/khong goi
    // la slugifyUrl thi client tu regex) -- khong phai user go tay từng ky tu van spams cap hall
    inputEl.addEventListener('input', function () {
        autoGrowComposeInput();
        updateSendButtonState();
        updateMentionSuggest();
        maybeFetchComposeLinkPreview();
        if (!ws || ws.readyState !== WebSocket.OPEN) return;
        var now = Date.now();
        if (lastTypingSentAt[conversationId] && now - lastTypingSentAt[conversationId] < TYPING_THROTTLE_MS) return;
        lastTypingSentAt[conversationId] = now;
        send({type: 'TYPING', id: newId(), conversationId: conversationId});
    });

    // stickToBottom mặc định true -- card MỚI tạo (chưa có tin nào) coi như "đang ở đáy", tin đầu
    // tiên thêm vào (dù nạp lịch sử hay tin sống) tự cuộn xuống bình thường, không cần chờ tính toán
    // hình học trên 1 log rỗng (scrollHeight/clientHeight đều 0 lúc này, isNearBottom() sẽ luôn đúng
    // vậy thôi nhưng đặt tường minh cho dễ đọc).
    // Reply bar (nằm trong .composeCard, trên .composeRow) -- hiện khi pendingReplyTo != null
    var replyBarEl = document.createElement('div');
    replyBarEl.className = 'replyBar';
    replyBarEl.innerHTML = '<img class="replyBarThumb" alt=""><div class="replyBarBody"><div class="replyBarName"></div><div class="replyBarSnippet"></div></div><button type="button" class="replyBarClose">\u2715</button>';
    card.querySelector('.composeCard').insertBefore(replyBarEl, card.querySelector('.composeRow'));
    var pendingReplyTo = null;
    function setPendingReply(replyTo) {
        pendingReplyTo = replyTo;
        replyBarEl.querySelector('.replyBarName').innerText = 'Tr\u1ea3 l\u1eddi ' + displayName(replyTo.fromUserId);
        replyBarEl.querySelector('.replyBarSnippet').innerText = replyTo.snippet || 'Tin nh\u1eafn';
        var rbt = replyBarEl.querySelector('.replyBarThumb');
        if (replyTo.thumbUrl) { rbt.src = replyTo.thumbUrl; rbt.classList.add('show'); rbt.onerror = function(){ rbt.classList.remove('show'); }; }
        else { rbt.removeAttribute('src'); rbt.classList.remove('show'); }
        replyBarEl.classList.add('show');
    }
    function clearPendingReply() {
        pendingReplyTo = null;
        var rbt2 = replyBarEl.querySelector('.replyBarThumb');
        if (rbt2) { rbt2.removeAttribute('src'); rbt2.classList.remove('show'); }
        replyBarEl.classList.remove('show');
    }
    replyBarEl.querySelector('.replyBarClose').onclick = function(e){ e.stopPropagation(); clearPendingReply(); inputEl.focus(); };

    var entry = {
        label: initialLabel, el: card, logEl: logEl,
        jumpBtnEl: jumpBtnEl, jumpBadgeEl: jumpBadgeEl, readObserver: readObserver,
        stickToBottom: true, unreadIdSet: new Set(), totalUnreadCount: 0, renderedMessageIds: new Set(),
        pendingFiles: [], // [{file, objectUrl}, ...] -- file/\u1ea3nh/video \u0110ANG ch\u1edd g\u1eedi (multi-file, xem stagePendingFiles)
        hasMoreOlder: false, hasMoreNewer: false,
        loadingOlder: false, loadingNewer: false, oldestLoadedTs: null, newestLoadedTs: null,
        pendingReplyToRef: function(v){ if(arguments.length) pendingReplyTo=v; return pendingReplyTo; },
        clearPendingReply: null
    };
    entry._setPendingReply = setPendingReply;
    entry.clearPendingReply = clearPendingReply;
    // Đo lại mốc chiều cao .conv-send NGAY LÚC card thật sự hiện ra (gọi từ selectConversation, sau
    // classList.add('active')) -- offsetHeight ở đây là phép đo ĐỒNG BỘ (ép layout ngay lập tức), lấy
    // đúng kích thước THẬT của compose box tại đúng thời điểm này, KHÔNG phụ thuộc lần ResizeObserver
    // báo đầu tiên (có thể đã gộp lẫn 1 thay đổi thật của người dùng, xem chú thích composeResizeObserver
    // phía trên) -- cắt đứt hẳn khả năng "gõ dòng đầu tiên bị nuốt mất, không bao giờ được bù".
    entry.resetComposeSendBaseline = function () { lastConvSendHeight = convSendEl.offsetHeight; };
    conversations[conversationId] = entry;
    updateConvHeadPresence(conversationId);
    return entry;
}

// Dòng hệ thống (sys) -- xác nhận subscribe, thông báo tạo conversation... KHÔNG phải tin nhắn chat
// thật, xem appendMessageBubble cho tin nhắn thật (bong bóng chat).
function logToConversation(conversationId, text, cssClass) {
    var entry = ensureConversationCard(conversationId, 'Conversation');
    var line = document.createElement('div');
    line.className = cssClass || 'sys';
    line.innerText = text;
    entry.logEl.appendChild(line);
    if (entry.stickToBottom !== false) smoothScrollToBottom(entry.logEl);
}

// Gom nhóm tin liên tiếp CÙNG người gửi + gửi gần nhau (giống Messenger/Slack: bớt lặp avatar/tên,
// giãn cách hẹp hơn) -- ngưỡng thời gian, không phải "liên tiếp về vị trí DOM" (để không bị vỡ nhóm
// bởi dòng "sys" xen giữa, vd "✓ subscribe thành công"). Theo dõi qua entry.lastMessageMeta thay vì
// dò lại DOM (rẻ hơn, không cần đọc lại phần tử cuối cùng mỗi lần).
var GROUP_WINDOW_MS = 5 * 60 * 1000;

// "Hôm nay"/"Hôm qua"/ngày cụ thể -- ngưỡng so theo NGÀY DƯƠNG LỊCH thật (00:00 local), không phải
// "cách nhau 24 tiếng" (2 tin lúc 23h50 và 00h10 hôm sau CÁCH NHAU 20 phút nhưng vẫn là 2 ngày khác
// nhau, phải có vạch ngăn).
function formatDateDivider(tsEpochMillis) {
    var d = new Date(tsEpochMillis);
    var now = new Date();
    var startOfDay = function (x) { return new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime(); };
    var diffDays = Math.round((startOfDay(now) - startOfDay(d)) / 86400000);
    if (diffDays === 0) return 'Hôm nay';
    if (diffDays === 1) return 'Hôm qua';
    var opts = {day: 'numeric', month: 'long'};
    if (d.getFullYear() !== now.getFullYear()) opts.year = 'numeric';
    return d.toLocaleDateString('vi-VN', opts);
}

// Chèn 1 vạch ngăn ngày mới vào log NẾU tin này khác ngày với tin gần nhất đã vẽ (hoặc là tin ĐẦU
// TIÊN của cả conversation) -- trả về true nếu VỪA chèn (để appendMessageBubble biết mà cắt nhóm
// gộp, xem lời gọi). entry.lastDividerDateKey theo dõi RIÊNG, tách khỏi entry.lastMessageMeta (dùng
// cho gom nhóm theo cửa sổ thời gian) vì 2 khái niệm "cùng ngày" và "cùng nhóm 5 phút" độc lập nhau.
function maybeInsertDateDivider(entry, tsEpochMillis) {
    var d = new Date(tsEpochMillis);
    var dateKey = d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate();
    if (entry.lastDividerDateKey === dateKey) return false;
    entry.lastDividerDateKey = dateKey;
    var divider = document.createElement('div');
    divider.className = 'dateDivider';
    divider.innerText = formatDateDivider(tsEpochMillis);
    entry.logEl.appendChild(divider);
    return true;
}

// Mọi conversation (DM lẫn nhóm) đều dùng CHUNG 1 kiểu hiển thị: bong bóng chat kiểu Facebook
// Messenger (buildDmBubbleRow) -- theo đúng yêu cầu: "bỏ giao diện chat nhóm khác chat riêng đi, làm
// giống chat riêng ấy". Kiểu phẳng (Slack) riêng cho nhóm trước đây đã bị bỏ hoàn toàn.
function appendMessageBubble(conversationId, fromUserId, body, tsEpochMillis, messageId, seen, reactions, deleted) {
    var entry = ensureConversationCard(conversationId, 'Conversation');
    // 1 tin có thể tới trùng qua 2 đường (vd đang lazy-load maybeLoadNewer đúng lúc tin đó cũng vừa
    // được đẩy sống qua WS) -- vẽ trùng row đã xấu, còn khiến entry.totalUnreadCount CỘNG TRÙNG. Bỏ
    // qua thẳng nếu id này đã render rồi, message không có id (hiếm, coi như luôn vẽ) thì bỏ qua kiểm tra.
    if (messageId && entry.renderedMessageIds.has(messageId)) return;
    if (messageId) entry.renderedMessageIds.add(messageId);
    var mine = fromUserId === myUserId;
    // Vạch ngăn ngày mới ("Hôm nay"/"Hôm qua"/ngày cụ thể) -- thứ hay thiếu khiến list tin trông
    // "phẳng lì" như 1 khối chữ vô tận, không có mốc thời gian nào để mắt bám vào. Sang ngày mới LUÔN
    // cắt nhóm (dù cùng người gửi, cách nhau vài giây) -- không ai mong "tiếp tục" 1 nhóm tin từ hôm
    // qua sang hôm nay, kể cả kỹ thuật gộp theo GROUP_WINDOW_MS có cho phép.
    var dateInserted = maybeInsertDateDivider(entry, tsEpochMillis);
    var prev = entry.lastMessageMeta;
    var grouped = !dateInserted && !!prev && prev.fromUserId === fromUserId && tsEpochMillis >= prev.ts && (tsEpochMillis - prev.ts) < GROUP_WINDOW_MS;
    entry.lastMessageMeta = {fromUserId: fromUserId, ts: tsEpochMillis};

    // Ảnh/video chưa biết chiều cao thật lúc vừa gắn vào DOM (chưa tải xong -- <img>/<video> cao 0px
    // tới khi có dữ liệu thật), nên scrollTop tính theo scrollHeight ngay lúc đó NGẮN hơn thật --
    // đã gặp thật: gửi ảnh xong khung chat không tự cuộn xuống, kể cả sau khi reload (coi ảnh như
    // "vô hình" vì chưa tải xong lúc tính). Phải cuộn lại LẦN NỮA sau khi media báo đã có kích thước
    // thật (onMediaReady, xem renderMessageContent) -- CHỈ khi đang neo đáy (stickToBottom), không thì
    // ảnh tải xong xong lúc đang đọc tin cũ hơn phía trên sẽ giật mất chỗ đang xem.
    var onMediaReady = function () {
        if (entry.stickToBottom) smoothScrollToBottom(entry.logEl);
        positionMsgActions(row, mine);
    };

    var row = buildDmBubbleRow(entry, fromUserId, body, tsEpochMillis, mine, grouped, onMediaReady);
    // Hiệu ứng "nảy" (xem @keyframes messageIn) CHỈ cho tin sống thật sự mới tới -- suppressAutoScroll
    // chỉ true trong lúc đang nạp HÀNG LOẠT từ lịch sử (loadHistory/maybeLoadOlder/maybeLoadNewer, xem
    // các chỗ gán cờ này), tin sống qua WS luôn nạp riêng lẻ với cờ false.
    if (!entry.suppressAutoScroll) row.classList.add('liveIn');

    if (messageId) {
        row.dataset.messageId = messageId;
        row.dataset.conversationId = conversationId;
        row.querySelector('.react-btn').onclick = function (e) {
            e.stopPropagation();
            openReactionPicker(e.currentTarget, conversationId, messageId);
        };
        var pinBtn2 = row.querySelector('.pinBtn');
        if (pinBtn2) pinBtn2.onclick = function (e) {
            e.stopPropagation();
            openPinMenu(e.currentTarget, conversationId, messageId);
        };
        var replyBtn2 = row.querySelector('.replyBtn');
        if (replyBtn2) replyBtn2.onclick = function (e) {
            e.stopPropagation();
            var snip = snippetForBody(body);
            var entry2 = conversations[conversationId];
            if (entry2) {
                // use closure pendingReplyTo via entry reference stored on card
                var card2 = entry2.el;
                // find the replyBar via entry
                var rb = card2.querySelector('.replyBar');
                if (rb) {
                    // build replyTo from current message
                    var th1 = replyThumbForBody(body);
                    var rt = {messageId: messageId, fromUserId: fromUserId, snippet: snip};
                    if (th1 && th1.url) { rt.thumbUrl = th1.url; if (th1.isVideo) rt.thumbIsVideo = true; }
                    if (tsEpochMillis) rt.ts = tsEpochMillis;
                    // call setPendingReply via stored function on entry
                    if (entry2._setPendingReply) entry2._setPendingReply(rt);
                    else { entry2.pendingReplyToRef(rt); rb.querySelector('.replyBarName').innerText = 'Tr\u1ea3 l\u1eddi ' + displayName(fromUserId); rb.querySelector('.replyBarSnippet').innerText = snip; rb.classList.add('show'); }
                    var inp = card2.querySelector('.composeInput');
                    if (inp) inp.focus();
                }
            }
        };
        var deleteBtn = row.querySelector('.msgDeleteBtn');
        if (deleteBtn) deleteBtn.onclick = function (e) {
            e.stopPropagation();
            deleteMessage(conversationId, messageId);
        };
    }
    // render reply quote inside bubble (before media/text)
    if (body && body.replyTo) {
        var bubbleEl2 = row.querySelector('.bubble');
        if (bubbleEl2) bubbleEl2.insertBefore(buildReplyQuoteEl(body.replyTo, conversationId), bubbleEl2.firstChild);
    }
    if (mine) updateSeenDisplay(row, !!seen);
    if (deleted) renderDeletedPlaceholder(row);

    entry.logEl.appendChild(row);

    // "Đã đọc" (READ) CHỈ gửi khi tin THỰC SỰ lọt vào khung nhìn (IntersectionObserver, xem
    // ensureConversationCard) -- áp dụng như nhau cho tin sống VÀ tin nạp lại từ lịch sử (kể cả đoạn
    // "chưa đọc" nạp lúc mở lại conversation, xem loadHistory): quan sát vẫn hoạt động đúng dù card
    // đang ẩn (conversation khác đang mở) -- trình duyệt tự báo intersect khi card đó được hiện ra
    // sau này và tin nằm trong vùng nhìn thấy, không cần code riêng cho "vừa chuyển sang xem".
    //
    // KHÔNG dùng cờ "seen" trả về từ API để quyết định có quan sát hay không -- "seen" nghĩa là "CÓ
    // AI KHÁC người gửi (bất kỳ ai) đã đọc chưa" (xem MessageHistoryRegistry#listMessages), không
    // phải "CHÍNH MÌNH đã đọc chưa" -- trong nhóm >2 người, 1 tin có thể đã được người khác đọc
    // (seen=true) dù mình chưa từng thấy nó, dùng "seen" ở đây sẽ bỏ sót không gửi READ của mình.
    // entry.skipReadTracking: cờ tạm CHỈ bật khi nạp đoạn lịch sử ĐÃ CHẮC CHẮN mình đọc rồi (đoạn
    // "before" con trỏ đã đọc, xem loadHistory) -- nạp NHỮNG TIN ĐÓ không cần quan sát lại.
    //
    // Thêm vào entry.unreadIdSet NGAY (KHÔNG phụ thuộc suppressAutoScroll) -- Set này CHỈ để biết
    // đang "chờ observer" đúng những tin nào (phục vụ readObserver.observe/unobserve), KHÔNG dùng
    // size() để hiển thị số nữa (xem updateJumpBadge) -- số hiển thị lấy từ entry.totalUnreadCount,
    // khởi tạo CHÍNH XÁC từ server (GET /read-cursor) lúc mở lại, xem loadAroundReadCursor.
    var trackRead = !!(messageId && !deleted && !mine && !entry.skipReadTracking);
    if (trackRead) {
        entry.unreadIdSet.add(messageId);
        entry.readObserver.observe(row);
        // CHỈ cộng thêm khi đây là tin SỐNG thật sự mới tới (suppressAutoScroll luôn false ở đường
        // tin sống qua WS) -- lazy-load nạp tiếp phần vốn đã nằm trong entry.totalUnreadCount ban đầu
        // (server đã đếm luôn phần đó rồi, xem loadAroundReadCursor/maybeLoadNewer) thì KHÔNG được
        // cộng thêm lần nữa, không thì số sẽ tăng gấp đôi so với thực tế.
        if (!entry.suppressAutoScroll) entry.totalUnreadCount = (entry.totalUnreadCount || 0) + 1;
    }

    // suppressAutoScroll: đang giữa 1 đợt nạp lịch sử/nạp thêm hàng loạt (xem loadHistory,
    // maybeLoadNewer) -- vị trí cuộn do CHÍNH nơi gọi tự quyết định (neo vào tin/vạch "chưa đọc", hay
    // đứng yên tuyệt đối không đụng tới nếu chỉ là nạp ngầm thêm), không để từng dòng tự cuộn theo
    // kiểu tin SỐNG thường (mine luôn cuộn xuống, theirs cuộn xuống nếu đang neo đáy).
    if (!entry.suppressAutoScroll) {
        if (mine) {
            // Tự gửi thì LUÔN neo xuống đáy để thấy ngay tin vừa gửi -- kể cả đang cuộn lên đọc tin cũ.
            entry.stickToBottom = true;
            smoothScrollToBottom(entry.logEl);
        } else if (entry.stickToBottom) {
            smoothScrollToBottom(entry.logEl);
        } else if (trackRead) {
            // Tin mới tới trong lúc đang đọc tin cũ hơn -- không tự cuộn (sẽ giật mất chỗ đang xem),
            // chỉ báo qua nút "xuống cuối" (đã add vào unreadIdSet ở trên), tự bấm mới cuộn xuống xem.
            bumpJumpBadge(entry);
        }
    }

    // PHẢI đo/canh cụm nút SAU khi row đã lên DOM thật (getBoundingClientRect cần layout thật).
    if (messageId) positionMsgActions(row, mine);
    entry.lastGroupRow = row;

    // PHẢI vẽ reaction SAU khi row đã gắn vào DOM (appendChild ở trên) -- renderReactions tìm phần
    // tử qua document.querySelector, một node vừa tạo bằng createElement() nhưng CHƯA appendChild
    // thì chưa nằm trong cây DOM thật, querySelector sẽ không thấy (tìm ra null, âm thầm bỏ qua,
    // không báo lỗi gì cả) -- đã gặp thật: reaction nạp từ lịch sử (GET /messages) không bao giờ
    // hiện ra dù dữ liệu trong DB đúng, đúng vì gọi renderReactions() TRƯỚC dòng appendChild này.
    if (messageId && reactions && reactions.length) {
        reactionsByMessageId[messageId] = {};
        reactions.forEach(function (r) { reactionsByMessageId[messageId][r.userId] = r.emoji; });
        renderReactions(messageId, row);
    }

    if (tsEpochMillis != null) {
        entry.oldestLoadedTs = entry.oldestLoadedTs == null ? tsEpochMillis : Math.min(entry.oldestLoadedTs, tsEpochMillis);
        entry.newestLoadedTs = entry.newestLoadedTs == null ? tsEpochMillis : Math.max(entry.newestLoadedTs, tsEpochMillis);
    }
}

// Bong bóng chat kiểu Facebook Messenger (bo tròn đều, phẳng, không viền/đổ bóng) -- DÙNG CHUNG cho
// DM lẫn nhóm: avatar + tên (chỉ "theirs") + nội dung + giờ, avatar/giờ "trôi" xuống đúng dòng cuối
// cùng của 1 khối gộp liên tiếp cùng người gửi (xem entry.lastGroupRow trong appendMessageBubble).
// Trả về row CHƯA gắn vào DOM -- appendMessageBubble tự appendChild + gọi positionMsgActions (cần
// layout thật).
function buildDmBubbleRow(entry, fromUserId, body, tsEpochMillis, mine, grouped, onMediaReady) {
    var row = document.createElement('div');
    row.className = 'bubble-row ' + (mine ? 'mine' : 'theirs') + (grouped ? ' grouped' : '');
    var name = displayName(fromUserId);
    row.innerHTML =
        (mine ? '' : '<span class="avatar"></span>') +
        '<div class="bubble-col">' +
        (mine ? '' : '<div class="sender-name"></div>') +
        '<div class="bubble-wrap"><div class="bubble"></div><div class="reactions-row"></div></div>' +
        '<div class="bubble-time"><span class="bubble-time-text"></span><span class="seen-status"></span></div>' +
        '</div>' +
        '<div class="msg-actions"><button type="button" class="replyBtn fa-solid fa-reply" title="Trả lời"></button><button type="button" class="react-btn fa-solid fa-face-smile" title="Thả cảm xúc"></button>' +
        '<button type="button" class="pinBtn fa-solid fa-thumbtack" title="Ghim tin nhắn"></button>' +
        (mine ? '<button type="button" class="msgDeleteBtn fa-solid fa-trash" title="Xoá tin nhắn"></button>' : '') +
        '</div>';
    if (!mine) {
        var avatarEl = row.querySelector('.avatar');
        var nameEl = row.querySelector('.sender-name');
        var senderColor = avatarColor(fromUserId);
        avatarEl.style.background = senderColor;
        avatarEl.innerText = avatarInitial(name);
        // Kiểu Messenger: TÊN chỉ hiện ở tin ĐẦU nhóm (trên cùng). Avatar/giờ xử lý chung bên dưới.
        // Tô màu tên theo avatarColor() (kiểu Discord) -- hữu ích nhất trong nhóm đông người (phân
        // biệt ai nói gì); với DM chỉ có đúng 1 người "theirs" nên luôn ra cùng 1 màu, vô hại.
        if (grouped) nameEl.style.display = 'none'; else { nameEl.innerText = name; nameEl.style.color = senderColor; }
    }
    // onMediaReady (từ appendMessageBubble) vừa cuộn lại khung chat, vừa canh lại nút react SAU KHI
    // media tải xong kích thước thật -- không thì nút bị lệch vì bubble còn cao 0px lúc đo.
    renderMessageContent(row.querySelector('.bubble'), body, onMediaReady);
    row.querySelector('.bubble-time-text').innerText = formatTime(tsEpochMillis);
    // AVATAR + GIỜ chỉ hiện ở tin CUỐI khối gộp (dòng mới nhất) -- vì thêm dần theo thời gian (chưa
    // biết trước "đây có phải cuối nhóm không" lúc vừa vẽ), mỗi khi có tin mới CÙNG nhóm thì ẩn
    // avatar/giờ của dòng vừa mất "ngôi mới nhất" đi, để chúng luôn "trôi" xuống đúng dòng cuối cùng.
    if (grouped && entry.lastGroupRow) {
        var prevTimeEl = entry.lastGroupRow.querySelector('.bubble-time');
        if (prevTimeEl) prevTimeEl.style.display = 'none';
        if (!mine) {
            var prevAvatarEl = entry.lastGroupRow.querySelector('.avatar');
            if (prevAvatarEl) prevAvatarEl.style.visibility = 'hidden';
        }
        // Dòng TRƯỚC giờ biết là có tin theo sau (chính dòng này) -- bo phẳng góc DƯỚI của nó (xem CSS .has-next).
        entry.lastGroupRow.classList.add('has-next');
    }
    return row;
}

// Canh cụm nút (.msg-actions -- thả cảm xúc, + xoá nếu là tin của mình) đúng chính giữa chiều cao
// CỦA BUBBLE (không phải cả .bubble-row) và cách đúng REACT_BTN_GAP_PX so với mép bubble -- CHỈ dùng
// cho bong bóng DM (nhóm dùng vị trí tĩnh cố định qua CSS .msg-actions, không cần đo). Không dùng CSS
// tĩnh được vì bubble-col có thể có phần tử khác RỘNG HƠN bubble (tên người gửi dài, giờ+dấu đã xem),
// canh theo hàng sẽ lệch xa khỏi bubble thật. Đo CHÍNH cụm (offsetWidth) thay vì hằng số cố định --
// cụm có thể rộng 1 nút (chỉ react) hoặc 2 nút (react + xoá, khi là tin của mình).
var REACT_BTN_GAP_PX = 10;
// Khớp .linkPreviewCard{max-width:320px} và .bubble-col{max-width:68%} (xem CSS) -- phải tự nhân tay
// ở đây thay vì đọc lại từ CSS, xem chú thích trong positionMsgActions.
var LINK_PREVIEW_MAX_W = 320;
var BUBBLE_COL_MAX_RATIO = 0.68;
function positionMsgActions(row, mine) {
    var actionsEl = row.querySelector('.msg-actions');
    var bubbleEl = row.querySelector('.bubble');
    if (!actionsEl || !bubbleEl) return;
    // Card preview link (.linkPreviewCard/.skeleton, xem renderMessageContent) dùng width:100% cho
    // <img>/thanh skeleton bên trong -- % đó chỉ resolve đúng khi CHÍNH .bubble có sẵn 1 width XÁC
    // ĐỊNH (px). .bubble bình thường tự co theo nội dung (shrink-to-fit, để tin ngắn giữ bubble hẹp) --
    // nhưng shrink-to-fit + %-width con là vòng lặp kinh điển: trình duyệt không biết trước <img> rộng
    // bao nhiêu nên tạm coi như 0, khiến card co gần về 0 lúc CHƯA tải ảnh xong rồi "nở" đột ngột lúc
    // tải xong -- ngược hẳn ý muốn (card preview phải luôn rộng tối đa cho phép, không co theo độ dài
    // URL, giống Messenger/Telegram). Gán THẲNG 1 width cố định (px) để cắt đứt vòng lặp này.
    // PHẢI làm Ở ĐÂY (không phải lúc renderMessageContent build card): positionMsgActions là chỗ DUY
    // NHẤT chắc chắn chạy SAU KHI row đã lên DOM thật -- renderMessageContent chạy lúc row còn
    // detached (buildDmBubbleRow build xong mới trả cho caller appendChild), getComputedStyle trên
    // node detached trả về CHUỖI RỖNG (đã kiểm chứng thực tế). Kể cả đo được max-width của .bubble-col
    // lúc đã gắn DOM, trình duyệt cũng trả nguyên chuỗi "68%" chứ KHÔNG tự resolve ra px cho property
    // này -- nên tự nhân tay theo rowRect (đã full-width/xác định, xem CSS .bubble-row) thay vì đọc lại
    // từ CSS. Chỉ tính 1 LẦN (đánh dấu qua dataset) vì hàm này bị gọi lại nhiều lần cho cùng 1 bubble
    // (lúc mount, rồi lại mỗi khi ảnh/card đổi -- xem onMediaReady trong renderLinkPreview*).
    if (bubbleEl.classList.contains('media-only') && bubbleEl.querySelector('.linkPreviewCard') && !bubbleEl.dataset.linkWidthFixed) {
        var rowWidthNow = row.getBoundingClientRect().width;
        bubbleEl.style.width = Math.min(LINK_PREVIEW_MAX_W, rowWidthNow * BUBBLE_COL_MAX_RATIO) + 'px';
        bubbleEl.dataset.linkWidthFixed = '1';
    }
    var bubbleRect = bubbleEl.getBoundingClientRect();
    var rowRect = row.getBoundingClientRect();
    var actionsWidth = actionsEl.offsetWidth;
    actionsEl.style.top = ((bubbleRect.top - rowRect.top) + bubbleRect.height / 2) + 'px';
    if (mine) {
        actionsEl.style.left = ((bubbleRect.left - rowRect.left) - REACT_BTN_GAP_PX - actionsWidth) + 'px';
        actionsEl.style.right = 'auto';
    } else {
        actionsEl.style.left = ((bubbleRect.right - rowRect.left) + REACT_BTN_GAP_PX) + 'px';
        actionsEl.style.right = 'auto';
    }
}

// Cập nhật hiển thị "đã gửi/đã xem" cho 1 tin CỦA CHÍNH MÌNH -- ✓/✓✓ ngắn gọn cạnh giờ, đúng kiểu
// Messenger (dùng chung cho cả DM lẫn nhóm, xem buildDmBubbleRow).
function updateSeenDisplay(row, seen) {
    var seenEl = row.querySelector('.seen-status');
    if (!seenEl) return;
    seenEl.innerText = seen ? '✓✓' : '✓';
    seenEl.classList.toggle('seen', seen);
}

// Nhận frame SEEN (ai đó vừa xem 1 tin) -- chỉ có ý nghĩa cho bubble "mine" (tin CHÍNH MÌNH gửi):
// tìm đúng row theo messageId, đổi ✓ (đã gửi) thành ✓✓ (đã xem). Tìm trong TOÀN BỘ #chatMain (kể cả
// card không đang active) vì có thể xem lúc đang mở conversation khác.
function handleSeenReceived(conversationId, messageId) {
    if (!messageId) return;
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (!row) return;
    updateSeenDisplay(row, true);
}

