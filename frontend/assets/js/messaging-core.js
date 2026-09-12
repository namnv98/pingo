// PHẢI là UUID thật -- server dùng làm primary key bảng messages, id không phải UUID sẽ khiến seen/reactions mất khớp sau reload (bug thật đã gặp).
function newId() {
    return crypto.randomUUID();
}

// Trả về true/false thay vì ném lỗi ra ngoài -- ws.send() ném exception nếu socket chưa/không còn
// OPEN (đang connecting/closing/closed), trước đây không ai bọc try/catch nên 1 lần gửi lúc mất kết
// nối sẽ làm rớt cả hàm gọi nó giữa chừng (bug thật đã gặp -- xem sendChatMessage). Không đổi gì cho
// các loại frame khác (TYPING/READ/REACTION/PIN/DELETE...) -- vẫn gọi y hệt, chỉ là giờ AN TOÀN hơn
// khi mất kết nối, không cần sửa gì thêm ở các chỗ gọi đó.
function send(frame) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    try {
        ws.send(JSON.stringify(frame));
        return true;
    } catch (e) {
        return false;
    }
}

// ===== Optimistic send: hiện bubble NGAY lúc bấm gửi, tự cập nhật trạng thái đang gửi/đã gửi/lỗi =====
// Trước đây bubble CHỈ hiện khi server echo frame MESSAGE quay lại (fan-out) -- nếu vì lý do gì đó
// (mất kết nối, lỗi server, echo bị rớt) mà echo không bao giờ tới thì người gửi không thấy GÌ CẢ,
// không có cách nào biết tin đã gửi hay chưa (bug thật đã gặp/được hỏi). Giờ render local trước bằng
// đúng id client tự sinh (appendMessageBubble đã có sẵn cơ chế khử trùng theo id, xem
// entry.renderedMessageIds), rồi 3 tín hiệu sau tự "nhận" lại đúng bubble đó qua data-message-id:
//   - ACK (frame.id trùng)      -> markMessageSent
//   - MESSAGE echo (fromUserId === mình, frame.id trùng) -> markMessageSent (thường tới TRƯỚC ACK
//     vì fan-out cục bộ chạy trước dòng gửi ACK bên colony, xem ChatSessionManager#handleMessage --
//     nhưng không chắc chắn tuyệt đối nên xử lý cả 2 đường, idempotent nếu trùng)
//   - ERROR (frame.id trùng)   -> markMessageFailed
// Không có tín hiệu nào tới trong SEND_TIMEOUT_MS (mất gói, server treo...) cũng tự coi là lỗi.
var pendingSentMessages = {}; // id -> {type, id, conversationId, body} -- giữ nguyên frame gốc để "gửi lại" dùng lại được
var sendTimeoutTimers = {}; // id -> setTimeout handle
var SEND_TIMEOUT_MS = 12000; // dư dả so với round-trip WS bình thường -- tránh báo lỗi oan lúc mạng chỉ hơi chậm

function scheduleSendTimeout(messageId) {
    clearSendTimeout(messageId);
    sendTimeoutTimers[messageId] = setTimeout(function () {
        markMessageFailed(messageId, 'Không nhận được xác nhận từ server (hết thời gian chờ)');
    }, SEND_TIMEOUT_MS);
}
function clearSendTimeout(messageId) {
    if (sendTimeoutTimers[messageId]) {
        clearTimeout(sendTimeoutTimers[messageId]);
        delete sendTimeoutTimers[messageId];
    }
}

function markMessageSent(messageId) {
    if (!pendingSentMessages[messageId]) return; // ACK lẫn echo đều gọi tới đây -- lần thứ 2 (nếu có) là no-op vô hại
    delete pendingSentMessages[messageId];
    clearSendTimeout(messageId);
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (!row) return;
    row.classList.remove('pending', 'failed');
    var seenEl = row.querySelector('.seen-status');
    if (seenEl) { seenEl.classList.remove('failed'); seenEl.onclick = null; seenEl.title = ''; }
    updateSeenDisplay(row, false); // '✓' -- đã gửi, chưa ai xem; '✓✓' sẽ tới sau qua handleSeenReceived như bình thường
}

function markMessageFailed(messageId, reason) {
    // REACTION/PIN/DELETE frame cũng dùng ĐÚNG messageId làm frame.id (xem openReactionPicker/openPinMenu/
    // deleteMessage) -- 1 frame ERROR cho các hành động đó sẽ trùng data-message-id với chính tin nhắn
    // đang bị react/ghim/xoá. Phải chặn ở đây bằng pendingSentMessages (chỉ chứa id của tin ĐANG chờ gửi
    // qua sendChatMessage) để không gắn nhầm trạng thái "gửi lỗi" lên 1 bubble đã gửi xong từ trước.
    if (!pendingSentMessages[messageId]) return;
    clearSendTimeout(messageId);
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (!row) return;
    row.classList.remove('pending');
    row.classList.add('failed');
    var seenEl = row.querySelector('.seen-status');
    if (seenEl) {
        seenEl.classList.add('failed');
        seenEl.innerText = '!';
        seenEl.title = (reason || 'Gửi lỗi') + ' -- bấm để gửi lại';
        seenEl.onclick = function (e) { e.stopPropagation(); retrySendMessage(messageId); };
    }
}

// Gửi lại NGUYÊN payload cũ dưới đúng id cũ -- vẫn đúng 1 bubble đó chuyển lại trạng thái "đang gửi", không tạo bubble mới.
function retrySendMessage(messageId) {
    var frame = pendingSentMessages[messageId];
    if (!frame) return;
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (row) {
        row.classList.remove('failed');
        row.classList.add('pending');
        var seenEl = row.querySelector('.seen-status');
        if (seenEl) { seenEl.classList.remove('failed'); seenEl.innerText = '○'; seenEl.title = ''; seenEl.onclick = null; }
    }
    if (send(frame)) scheduleSendTimeout(messageId);
    else markMessageFailed(messageId, 'Mất kết nối');
}

// Điểm vào DUY NHẤT để gửi 1 tin MESSAGE (chữ/file/GIF-sticker đều gọi qua đây) -- xem sendMsg,
// uploadAndSendFiles, sendExternalImageMessage. body đã đúng shape server mong đợi (message/files/replyTo/...).
function sendChatMessage(conversationId, body) {
    var id = newId();
    appendMessageBubble(conversationId, myUserId, body, Date.now(), id, false, null, false, true);
    var frame = {type: 'MESSAGE', id: id, conversationId: conversationId, body: body};
    pendingSentMessages[id] = frame;
    if (send(frame)) scheduleSendTimeout(id);
    else markMessageFailed(id, 'Mất kết nối');
    return id;
}

// Gửi READ cho 1 tin (dùng chung cho mọi đường đánh dấu "đã đọc") -- tab đang nền/mất focus thì xếp hàng vào pendingReadAcks, gửi bù khi tab quay lại active.
function sendReadReceipt(conversationId, messageId) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (document.visibilityState === 'visible' && document.hasFocus()) {
        send({type: 'READ', id: messageId, conversationId: conversationId});
    } else {
        pendingReadAcks.push({id: messageId, conversationId: conversationId});
    }
}

// Dùng ngưỡng thay vì so tuyệt đối -- scrollTop lẻ vài px do làm tròn subpixel gần như luôn khác scrollHeight-clientHeight.
var NEAR_BOTTOM_PX = 64;
function isNearBottom(logEl) {
    return logEl.scrollHeight - logEl.scrollTop - logEl.clientHeight < NEAR_BOTTOM_PX;
}

// Cuộn mượt xuống đáy cho các trường hợp "cuộn thấy được" -- KHÔNG dùng ở composeResizeObserver/applyScrollAnchor, 2 chỗ đó cần đúng ngay lập tức, animate vào sẽ gây giật/lệch.
function smoothScrollToBottom(logEl) {
    logEl.scrollTo({top: logEl.scrollHeight, behavior: 'smooth'});
}

// entry.totalUnreadCount khởi tạo từ số CHÍNH XÁC server đếm (GET /read-cursor), không phải số phần tử client đã lazy-load được (bug thật đã gặp: số bị chặn ở cỡ 1 trang).
function updateJumpBadge(entry) {
    if (!entry.jumpBadgeEl) return;
    var count = entry.totalUnreadCount || 0;
    if (count > 0) {
        // Hiện đúng số thật, không rút gọn thành "9+" -- yêu cầu hiển thị chính xác số tin chưa đọc.
        entry.jumpBadgeEl.innerText = String(count);
        entry.jumpBadgeEl.classList.add('show');
    } else {
        entry.jumpBadgeEl.classList.remove('show');
    }
}

// Có tin mới tới lúc đang cuộn lên xem tin cũ (không neo đáy) -- KHÔNG tự cuộn xuống (sẽ giật mất chỗ đang đọc), chỉ hiện nút.
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
// Card mặc định ẩn (CSS ".conv" không ".active") -- conversation không đang mở vẫn ghi log/giữ trạng thái, chỉ là chưa hiển thị.
// ===== Hình nền riêng cho từng cuộc trò chuyện =====
// Lưu thuần client-side (localStorage) -- tuỳ biến hiển thị cá nhân, không phải dữ liệu nghiệp vụ cần đồng bộ qua server.
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
// #0084ff là màu mặc định gốc (đứng đầu danh sách), còn lại chọn màu đủ đậm để chữ trắng lên nổi rõ.
var BUBBLE_PRESET_COLORS = ['#0084ff', '#6d5bf5', '#00b894', '#e17055', '#e84393', '#00b8d9', '#2d3436', '#636e72', '#d63031', '#00cec9'];
// Ảnh tự tải lên lưu dạng data: URI trong localStorage (không có API upload nền ở server) -- giới hạn nhỏ để tránh vượt hạn mức localStorage.
var MAX_BG_IMAGE_BYTES = 1.5 * 1024 * 1024;

function loadBgMap() {
    try { return JSON.parse(localStorage.getItem(BG_STORAGE_KEY)) || {}; } catch (e) { return {}; }
}
function saveBgMap(map) {
    try {
        localStorage.setItem(BG_STORAGE_KEY, JSON.stringify(map));
    } catch (e) {
        // Tràn hạn mức localStorage -- bỏ qua lặng lẽ, nền chỉ là tuỳ biến phụ, không đáng chặn cả tính năng.
        appAlert('Không lưu được (bộ nhớ trình duyệt đầy) -- thử chọn màu/gradient thay vì ảnh, hoặc xoá bớt tuỳ biến đã đặt ở cuộc trò chuyện khác.', 'Không lưu được');
    }
}
// Tự nhận diện bản ghi cũ (trước khi có màu bong bóng, map[id] là thẳng object nền {type, value}) để không mất dữ liệu nền đã chọn từ trước.
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

// Vẽ nền đã lưu (nếu có) lên .conv-log -- gọi lúc dựng card và mỗi lần đổi lựa chọn trong popup.
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
        logEl.style.backgroundColor = '';
        logEl.style.backgroundImage = bg.type === 'image' ? 'url(' + bg.value + ')' : bg.value;
    }
}

// Chọn chữ trắng/đen tuỳ độ sáng màu nền -- người dùng có thể chọn bất kỳ màu nào nên không thể cố định chữ trắng như mặc định gốc nữa.
function contrastTextColor(hex) {
    var c = hex.replace('#', '');
    if (c.length === 3) c = c.split('').map(function (ch) { return ch + ch; }).join('');
    var r = parseInt(c.substr(0, 2), 16), g = parseInt(c.substr(2, 2), 16), b = parseInt(c.substr(4, 2), 16);
    var luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.6 ? '#1b1d29' : '#ffffff';
}

// Gán 2 CSS custom property lên .conv (card), cascade xuống .bubble-row.mine .bubble mà không đụng conversation khác.
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

// Đánh dấu swatch khớp với nền/màu bong bóng đang áp -- ảnh tự tải lên không có ô đại diện sẵn nên không được đánh dấu (chấp nhận được).
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

// Quét "@token" khớp ĐÚNG username thành viên (không tính chính mình) -- "@all" (nhóm >=2 người) = nhắc hết, còn lại phải khớp nguyên username để tránh dương tính giả kiểu "@nam2" ăn nhầm "@nam".
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
        '<button class="icon searchToggleBtn" title="Tìm trong đoạn chat">' + ICON.search + '</button>' +
        '<button class="icon starBtn" title="Gắn sao">' + ICON.star + '</button>' +
        '<button class="icon muteBtn fa-solid fa-bell-slash" title="Tắt thông báo"></button>' +
        '<button class="icon renameBtn" title="Đổi tên riêng">' + ICON.edit + '</button>' +
        '<button class="icon bgBtn" title="Đổi hình nền &amp; màu chat">' + ICON.image + '</button>' +
        '<button class="icon leaveBtn fa-solid fa-right-from-bracket" title="Rời nhóm" style="display:none"></button>' +
        '<button class="icon deleteBtn fa-solid fa-trash" title="Xoá hẳn cuộc trò chuyện này"></button>' +
        '<button class="icon infoBtn" title="Ẩn/hiện panel thông tin" onclick="toggleInfoPanel()">' + ICON.info + '</button>' +
        '</div></div>' +
        '<div class="convSearchBar"><span class="convSearchIcon">' + ICON.search + '</span>' +
        '<input type="text" class="convSearchInput" placeholder="Tìm trong đoạn chat...">' +
        '<span class="convSearchCount"></span>' +
        '<button type="button" class="icon convSearchPrevBtn" title="Kết quả trước">' + ICON.down + '</button>' +
        '<button type="button" class="icon convSearchNextBtn" title="Kết quả sau">' + ICON.down + '</button>' +
        '<button type="button" class="icon convSearchCloseBtn" title="Đóng (Esc)">' + ICON.closeSm + '</button>' +
        '</div>' +
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
    card.querySelector('.leaveBtn').onclick = function () { leaveConversation(conversationId); };
    var starBtnEl = card.querySelector('.starBtn');
    starBtnEl.classList.toggle('active', isConversationStarred(conversationId));
    starBtnEl.title = isConversationStarred(conversationId) ? 'Bỏ gắn sao' : 'Gắn sao';
    starBtnEl.onclick = function () { toggleConversationStarred(conversationId); };
    var muteBtnEl = card.querySelector('.muteBtn');
    muteBtnEl.onclick = function () { toggleConversationMuted(conversationId); };
    card.querySelector('.subtitle').innerText = subtitle || '';
    document.getElementById('chatMain').appendChild(card);

    var logEl = card.querySelector('.conv-log');
    // Áp nền/màu bong bóng NGAY lúc dựng card, trước khi tin nhắn đầu tiên render, để không bị "nháy" từ mặc định sang đã chọn.
    applyConversationBackground(conversationId, logEl);
    applyConversationBubbleColor(conversationId, card);
    card.querySelector('.bgBtn').onclick = function () { openBackgroundPicker(conversationId, logEl, card); };
    var convSendEl = card.querySelector('.conv-send');
    var inputEl = card.querySelector('.composeInput');
    // Textarea "gương" ẩn để đo chiều cao (xem autoGrowComposeInput). rows=1 phải đặt tay -- createElement mặc định rows=2, khiến scrollHeight luôn tính tối thiểu 2 dòng dù chỉ gõ 1 từ (bug thật đã gặp).
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
    // Khối xem trước link ngay trong ô nhập -- chèn ngay sau composePreview file, không đè lên vị trí file.
    var composeLinkPreviewEl = null;
    var composeLinkPreviewState = {url: null, meta: null, declined: false};
    function ensureComposeLinkPreviewEl() {
        if (composeLinkPreviewEl) return composeLinkPreviewEl;
        composeLinkPreviewEl = document.createElement('div');
        // Phải insertBefore vào ĐÚNG cha .composeCard (không phải .conv-send) -- nhầm cha sẽ ném NotFoundError và làm chết cả handler input (bug thật đã gặp: "dán link không hiện gì").
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

    // Nút "xuống cuối" -- bấm mới thật sự cuộn xuống xem tin mới; các tin hiện ra sau đó tự trôi qua IntersectionObserver bên dưới nên tự gửi READ, không cần code riêng ở đây.
    jumpBtnEl.onclick = function () {
        entry.stickToBottom = true;
        smoothScrollToBottom(logEl);
        // KHÔNG tự xoá entry.unreadIdSet/badge -- nhảy thẳng xuống đáy có thể "lướt qua" tin chưa đọc nằm giữa, readObserver sẽ tự xoá đúng tin nào thật sự lọt khung nhìn.
        jumpBtnEl.classList.remove('show');
    };

    // stickToBottom quyết định tin mới có tự cuộn xuống hay chỉ báo (xem appendMessageBubble); cũng là nơi bắn tải thêm tin cũ hơn khi cuộn gần tới đỉnh.
    logEl.addEventListener('scroll', function () {
        var nearBottom = isNearBottom(logEl);
        entry.stickToBottom = nearBottom;
        if (nearBottom) {
            // KHÔNG tự xoá entry.unreadIdSet -- ẩn nút là đủ, để nguyên Set cho readObserver tự xoá đúng tin đã lọt khung nhìn.
            jumpBtnEl.classList.remove('show');
            maybeLoadNewer(conversationId);
        } else {
            jumpBtnEl.classList.add('show');
        }
        maybeLoadOlder(conversationId);
    });

    // Đánh dấu READ chỉ khi tin thực sự lọt khung nhìn (threshold 0.6) -- khác cách cũ coi như đã xem chỉ vì tin tới lúc conversation đang mở. root=logEl vì log là vùng cuộn riêng, không phải cả trang.
    var readObserver = new IntersectionObserver(function (obsEntries) {
        obsEntries.forEach(function (obsEntry) {
            if (!obsEntry.isIntersecting) return;
            var row = obsEntry.target;
            readObserver.unobserve(row);
            var messageId = row.dataset.messageId;
            var msgConversationId = row.dataset.conversationId;
            if (messageId && msgConversationId) sendReadReceipt(msgConversationId, messageId);
            if (messageId && entry.unreadIdSet.delete(messageId)) {
                entry.totalUnreadCount = Math.max(0, (entry.totalUnreadCount || 0) - 1);
                updateJumpBadge(entry);
            }
        });
    }, {root: logEl, threshold: 0.6});

    // Chọn ảnh/video xong KHÔNG gửi ngay -- giữ trong entry.pendingFiles tới khi bấm Gửi mới thật sự upload (giống Messenger/Zalo/Telegram). Chọn thêm lần nữa sẽ nối thêm vào danh sách, không thay thế.
    var composeCardEl = card.querySelector('.composeCard');
    function updateSendButtonState() {
        var hasContent = inputEl.value.trim().length > 0 || entry.pendingFiles.length > 0;
        sendBtnEl.disabled = !hasContent;
        sendBtnEl.classList.toggle('active', hasContent);
    }
    // Đo qua bản gương ẩn (inputMirrorEl), KHÔNG đo trực tiếp trên ô nhập thật -- đặt height='auto' trên ô thật để đo từng làm .conv-log tạm rộng ra và bị trình duyệt tự clamp scrollTop, gây lệch cộng dồn mỗi lần gõ thêm dòng (bug thật đã gặp).
    function autoGrowComposeInput() {
        inputMirrorEl.style.width = inputEl.clientWidth + 'px';
        inputMirrorEl.value = inputEl.value;
        inputEl.style.height = inputMirrorEl.scrollHeight + 'px';
    }
    // Bù scrollTop của .conv-log mỗi khi .conv-send đổi chiều cao (gõ nhiều dòng, hiện/ẩn preview file...) qua ResizeObserver để không bỏ sót trường hợp nào (chỉ xử lý lúc gõ chữ từng để sót đính kèm file/cuộn xem tin cũ, bug thật đã gặp).
    // 0 = "chưa đo được" (card còn display:none) -- lần đo đầu tiên chỉ lấy mốc, không trừ nhầm vào vị trí cuộn ban đầu.
    // BUG THẬT ĐÃ GẶP: không được lấy mốc từ chính lần ResizeObserver báo đầu tiên -- card hiện ra + gõ phím đầu tiên có thể bị trình duyệt GỘP thành 1 lần báo (coalesce), nuốt mất phần cần bù và làm scrollTop lệch vĩnh viễn; entry.resetComposeSendBaseline bên dưới đo offsetHeight ĐỒNG BỘ ngay sau khi card active để tránh việc này.
    var lastConvSendHeight = 0;
    var composeResizeObserver = new ResizeObserver(function () {
        var newHeight = convSendEl.offsetHeight;
        if (lastConvSendHeight > 0 && newHeight > 0) {
            var delta = newHeight - lastConvSendHeight;
            if (delta !== 0) {
                // BUG THẬT ĐÃ GẶP: khi compose co lại (delta<0) lúc đang neo đáy, trình duyệt đã tự clamp scrollTop trước khi dòng này chạy -- cộng thêm delta âm lên giá trị đã clamp sẽ trừ 2 lần, nên tính lại thẳng vị trí đáy thay vì cộng dồn delta.
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
    // Vẽ lại toàn bộ dải xem trước mỗi lần -- đơn giản hơn vá từng thẻ, số lượng luôn nhỏ (tối đa MAX_PENDING_FILES) nên không tốn kém.
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
    // Nối thêm vào entry.pendingFiles đang có (không thay thế); chặn ở đúng MAX_PENDING_FILES và báo rõ nếu chọn dư, không cắt bớt âm thầm.
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
    // .attachMenu gắn ngay trong card này (khác #reactionPicker/#gifPicker dùng chung 1 phần tử toàn cục) -- bấm chỉ bật/tắt đúng cái của card này, đóng mọi popup khác đang mở.
    attachMenuBtnEl.onclick = function (e) {
        e.stopPropagation();
        if (openAttachMenuEl === attachMenuEl) { closeAttachMenu(); return; }
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
    // Định vị theo attachMenuBtnEl (luôn hiển thị), không theo nút bên trong .attachMenu -- 1 phần tử trong tổ tiên display:none luôn cho getBoundingClientRect() ra toàn số 0 (bug thật đã gặp).
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
        // .files là FileList SỐNG gắn liền với input -- .value='' xoá luôn nó, nên phải snapshot thành mảng thường trước khi reset (bug thật đã gặp: stagePendingFiles luôn nhận rỗng).
        var files = Array.prototype.slice.call(fileInputEl.files);
        fileInputEl.value = ''; // cho phép chọn lại đúng file đó lần nữa
        if (files.length) stagePendingFiles(files);
    });
    var sendMsg = function () {
        var text = inputEl.value.trim();
        if (entry.pendingFiles.length) {
            // Tin đính kèm file thì phần chữ là CAPTION, không phải "tin chỉ có 1 link" -- huỷ khối xem trước link đang hiện cho khỏi hiểu lầm.
            if (composeLinkDebounce) { clearTimeout(composeLinkDebounce); composeLinkDebounce = null; }
            dismissComposeLinkPreview();
            composeLinkPreviewState.declined = false;
            // Gửi tất cả file đã chọn trong cùng 1 tin (giống Telegram/Messenger gộp album) thay vì tách rời thành nhiều tin lẻ.
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
        // Tin chỉ chứa đúng 1 link thì gửi kèm body.preview đã resolve từ lúc đang gõ, để mọi client (kể cả echo về chính mình) render card đồng bộ đúng kích thước ngay từ đầu, không "nở" sau. Chưa có meta thì cứ gửi không preview -- server tự enrich sau khi persist (xem ChatSessionManager#enrichLinkPreview).
        var body = {message: text};
        if (pendingReplyTo) body.replyTo = pendingReplyTo;
        var mentionedUserIds = computeMentionedUserIds(text, conversationId);
        if (mentionedUserIds.length) body.mentionedUserIds = mentionedUserIds;
        var soleUrlToSend = extractSoleUrl(text);
        if (soleUrlToSend && !composeLinkPreviewState.declined && composeLinkPreviewState.meta
            && composeLinkPreviewState.url === soleUrlToSend) {
            body.preview = composeLinkPreviewState.meta;
        }
        sendChatMessage(conversationId, body);
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
    // Chèn như 1 khối flex ngay trên .composeRow (không absolute-position như attachMenu/reactionPicker) vì popup này không neo theo 1 nút bấm cụ thể.
    var mentionSuggestEl = document.createElement('div');
    mentionSuggestEl.className = 'mentionSuggest';
    card.querySelector('.composeCard').insertBefore(mentionSuggestEl, card.querySelector('.composeRow'));
    var mentionCandidates = []; // [{id, username}, ...] hoặc {id:'all', username:'all', label:'Tất cả mọi người'} -- danh sách ĐANG hiện trong popup, khớp theo thứ tự với các .mentionSuggestItem
    var mentionQueryStart = -1; // vị trí ký tự "@" trong inputEl.value ứng với lượt gợi ý đang mở, -1 = đang đóng
    var mentionSelectedIndex = 0;
    // @ phải đứng đầu dòng/ngay sau khoảng trắng (tránh khớp nhầm email a@b) và không có khoảng trắng nào sau nó.
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
        // Chỉ nhóm (>2 thành viên) mới có "Tất cả mọi người" -- DM 2 người thì "mention all" vô nghĩa.
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
            if (u.id === 'all') { applyAvatar(avatarEl, {color: 'var(--ink-faint)', icon: ICON.users}); }
            else {
                var mentionImageUrl = userAvatarUrl(u.id);
                applyAvatar(avatarEl, mentionImageUrl ? {imageUrl: mentionImageUrl} : {color: avatarColor(u.id), initial: avatarInitial(u.username)});
            }
            var nameEl = document.createElement('span');
            nameEl.className = 'mentionSuggestName';
            nameEl.innerText = u.label || u.username;
            row.appendChild(avatarEl);
            row.appendChild(nameEl);
            // mousedown (không phải click) + preventDefault -- giữ focus trong ô nhập, không thì textarea bị blur trước khi click kịp bắn.
            row.onmousedown = function (e) { e.preventDefault(); confirmMentionSelect(i); };
            mentionSuggestEl.appendChild(row);
        });
        mentionSuggestEl.classList.add('show');
        renderMentionSuggestHighlight();
    }
    // Đăng ký TRƯỚC handler "Enter = gửi" bên dưới -- Enter/Tab/mũi tên khi popup mở phải chặn handler gửi tin chạy tiếp (stopImmediatePropagation), tránh vừa chọn mention vừa lỡ gửi tin.
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
        // Trễ 1 nhịp làm lưới an toàn cho các cách rời focus khác (Tab, bấm nút khác...) -- click chọn gợi ý đã tự tránh blur qua mousedown+preventDefault ở trên.
        setTimeout(hideMentionSuggest, 120);
    });

    // Enter = gửi, Shift+Enter = xuống dòng -- <textarea> mặc định Enter luôn xuống dòng nên phải preventDefault() khi không giữ Shift.
    inputEl.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMsg();
        }
    });
    // Báo "đang gõ" throttle theo TYPING_THROTTLE_MS (không gửi mỗi keystroke), im lặng bỏ qua nếu socket chưa sẵn sàng (best-effort).
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

    // stickToBottom mặc định true -- card mới tạo coi như "đang ở đáy" nên tin đầu tiên tự cuộn xuống bình thường.
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
        searchResults: [], searchIndex: -1, searchDebounce: null, searchTerm: null, // xem toggleConvSearch/runConvSearch
        hasMoreOlder: false, hasMoreNewer: false,
        loadingOlder: false, loadingNewer: false, oldestLoadedTs: null, newestLoadedTs: null,
        pendingReplyToRef: function(v){ if(arguments.length) pendingReplyTo=v; return pendingReplyTo; },
        clearPendingReply: null
    };
    entry._setPendingReply = setPendingReply;
    entry.clearPendingReply = clearPendingReply;
    // Đo lại mốc .conv-send NGAY lúc card hiện ra (gọi từ selectConversation) -- offsetHeight đồng bộ ở đây tránh phụ thuộc lần ResizeObserver báo đầu tiên có thể đã gộp mất thay đổi thật (xem composeResizeObserver phía trên).
    entry.resetComposeSendBaseline = function () { lastConvSendHeight = convSendEl.offsetHeight; };
    conversations[conversationId] = entry;

    // ===== Tìm trong đoạn chat đang mở (xem GET /messages/search, hall) =====
    card.querySelector('.searchToggleBtn').onclick = function (e) { e.stopPropagation(); toggleConvSearch(conversationId); };
    card.querySelector('.convSearchCloseBtn').onclick = function (e) { e.stopPropagation(); toggleConvSearch(conversationId); };
    var searchInputEl = card.querySelector('.convSearchInput');
    searchInputEl.addEventListener('input', function () {
        if (entry.searchDebounce) clearTimeout(entry.searchDebounce);
        var term = searchInputEl.value.trim();
        entry.searchDebounce = setTimeout(function () { runConvSearch(conversationId, term); }, 300);
    });
    searchInputEl.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); stepConvSearch(conversationId, e.shiftKey ? -1 : 1); }
        else if (e.key === 'Escape') { e.preventDefault(); toggleConvSearch(conversationId); }
    });
    card.querySelector('.convSearchPrevBtn').onclick = function (e) { e.stopPropagation(); stepConvSearch(conversationId, -1); };
    card.querySelector('.convSearchNextBtn').onclick = function (e) { e.stopPropagation(); stepConvSearch(conversationId, 1); };
    updateConvHeadPresence(conversationId);
    return entry;
}

// ===== Tìm trong 1 đoạn chat đang mở -- xem .convSearchBar trong ensureConversationCard, dùng
// jumpToMessage() có sẵn (messages-render.js) để nhảy tới đúng tin thay vì tự viết lại phần
// scroll/tải thêm/tô sáng (hàm đó đã lo hết). =====

function toggleConvSearch(conversationId) {
    var entry = conversations[conversationId];
    if (!entry) return;
    var bar = entry.el.querySelector('.convSearchBar');
    var opening = !bar.classList.contains('show');
    bar.classList.toggle('show');
    if (opening) {
        entry.el.querySelector('.convSearchInput').focus();
    } else {
        entry.searchResults = [];
        entry.searchIndex = -1;
        entry.el.querySelector('.convSearchInput').value = '';
        entry.el.querySelector('.convSearchCount').innerText = '';
    }
}

function updateConvSearchCount(entry) {
    var countEl = entry.el.querySelector('.convSearchCount');
    if (entry.searchResults.length) countEl.innerText = (entry.searchIndex + 1) + '/' + entry.searchResults.length;
    else countEl.innerText = entry.el.querySelector('.convSearchInput').value.trim() ? '0/0' : '';
}

function runConvSearch(conversationId, term) {
    var entry = conversations[conversationId];
    if (!entry) return;
    if (!term) {
        entry.searchResults = [];
        entry.searchIndex = -1;
        updateConvSearchCount(entry);
        return;
    }
    fetchJson('/messages/search?conversationId=' + encodeURIComponent(conversationId) + '&q=' + encodeURIComponent(term), true)
        .then(function (results) {
            // Người dùng có thể đã gõ tiếp/đóng thanh tìm trong lúc chờ HTTP -- bỏ kết quả trễ nếu ô nhập không còn khớp term này nữa.
            if (entry.el.querySelector('.convSearchInput').value.trim() !== term) return;
            entry.searchResults = results;
            entry.searchIndex = results.length ? 0 : -1;
            entry.searchTerm = term; // giữ lại để stepConvSearch (▲▼) tô đúng chữ khớp khi chuyển kết quả
            updateConvSearchCount(entry);
            if (results.length) jumpToMessage(conversationId, results[0].id, results[0].ts, term);
        })
        .catch(function (err) { console.warn('tìm trong đoạn chat lỗi', err); });
}

function stepConvSearch(conversationId, delta) {
    var entry = conversations[conversationId];
    if (!entry || !entry.searchResults.length) return;
    entry.searchIndex = (entry.searchIndex + delta + entry.searchResults.length) % entry.searchResults.length;
    updateConvSearchCount(entry);
    var r = entry.searchResults[entry.searchIndex];
    jumpToMessage(conversationId, r.id, r.ts, entry.searchTerm);
}

// Dòng hệ thống (sys) -- xác nhận subscribe, thông báo tạo conversation..., không phải tin nhắn chat thật (xem appendMessageBubble cho bong bóng chat).
function logToConversation(conversationId, text, cssClass) {
    var entry = ensureConversationCard(conversationId, 'Conversation');
    var line = document.createElement('div');
    line.className = cssClass || 'sys';
    line.innerText = text;
    entry.logEl.appendChild(line);
    if (entry.stickToBottom !== false) smoothScrollToBottom(entry.logEl);
}

// Gom nhóm tin liên tiếp cùng người gửi trong ngưỡng thời gian (không phải "liên tiếp về vị trí DOM", để dòng "sys" xen giữa không làm vỡ nhóm). Theo dõi qua entry.lastMessageMeta thay vì dò lại DOM.
var GROUP_WINDOW_MS = 5 * 60 * 1000;

// So theo NGÀY DƯƠNG LỊCH thật (00:00 local), không phải "cách nhau 24 tiếng" -- 23h50 và 00h10 hôm sau vẫn là 2 ngày khác nhau, phải có vạch ngăn.
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

// Trả về true nếu vừa chèn vạch ngăn ngày (để appendMessageBubble cắt nhóm gộp). entry.lastDividerDateKey tách riêng khỏi entry.lastMessageMeta vì "cùng ngày" và "cùng nhóm 5 phút" là 2 khái niệm độc lập.
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

// Mọi conversation (DM lẫn nhóm) đều dùng chung 1 kiểu hiển thị bong bóng Messenger (buildDmBubbleRow) -- theo yêu cầu bỏ giao diện chat nhóm kiểu Slack riêng, làm giống chat riêng.
function appendMessageBubble(conversationId, fromUserId, body, tsEpochMillis, messageId, seen, reactions, deleted, pending) {
    var entry = ensureConversationCard(conversationId, 'Conversation');
    // Tin có thể tới trùng qua 2 đường (lazy-load đúng lúc tin đó cũng vừa đẩy sống qua WS) -- bỏ qua nếu id đã render rồi, tránh vẽ trùng row và cộng trùng totalUnreadCount.
    if (messageId && entry.renderedMessageIds.has(messageId)) return;
    if (messageId) entry.renderedMessageIds.add(messageId);
    var mine = fromUserId === myUserId;
    // Sang ngày mới LUÔN cắt nhóm (dù cùng người gửi, cách nhau vài giây) -- không ai mong nhóm tin từ hôm qua "tiếp tục" sang hôm nay.
    var dateInserted = maybeInsertDateDivider(entry, tsEpochMillis);
    var prev = entry.lastMessageMeta;
    var grouped = !dateInserted && !!prev && prev.fromUserId === fromUserId && tsEpochMillis >= prev.ts && (tsEpochMillis - prev.ts) < GROUP_WINDOW_MS;
    entry.lastMessageMeta = {fromUserId: fromUserId, ts: tsEpochMillis};

    // Ảnh/video cao 0px tới khi tải xong nên scrollTop tính lúc gắn DOM bị ngắn hơn thật (bug thật đã gặp: gửi ảnh xong không tự cuộn xuống) -- phải cuộn lại khi media báo có kích thước thật (onMediaReady), chỉ khi đang neo đáy.
    var onMediaReady = function () {
        if (entry.stickToBottom) smoothScrollToBottom(entry.logEl);
        positionMsgActions(row, mine);
    };

    var row = buildDmBubbleRow(entry, fromUserId, body, tsEpochMillis, mine, grouped, onMediaReady);
    // Hiệu ứng "nảy" chỉ cho tin sống mới tới -- suppressAutoScroll chỉ true khi đang nạp hàng loạt từ lịch sử.
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
                var card2 = entry2.el;
                var rb = card2.querySelector('.replyBar');
                if (rb) {
                    var th1 = replyThumbForBody(body);
                    var rt = {messageId: messageId, fromUserId: fromUserId, snippet: snip};
                    if (th1 && th1.url) { rt.thumbUrl = th1.url; if (th1.isVideo) rt.thumbIsVideo = true; }
                    if (tsEpochMillis) rt.ts = tsEpochMillis;
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
        var forwardBtn2 = row.querySelector('.forwardBtn');
        if (forwardBtn2) forwardBtn2.onclick = function (e) {
            e.stopPropagation();
            openForwardModal(conversationId, messageId, body, fromUserId);
        };
    }
    if (body && body.replyTo) {
        var bubbleEl2 = row.querySelector('.bubble');
        if (bubbleEl2) bubbleEl2.insertBefore(buildReplyQuoteEl(body.replyTo, conversationId), bubbleEl2.firstChild);
    }
    if (body && body.forwardedFrom) {
        var bubbleEl3 = row.querySelector('.bubble');
        if (bubbleEl3) bubbleEl3.insertBefore(buildForwardedBadgeEl(body.forwardedFrom), bubbleEl3.firstChild);
    }
    if (mine) {
        updateSeenDisplay(row, !!seen);
        // Bubble optimistic (xem sendChatMessage) -- chờ ACK/echo/ERROR xác nhận, xem markMessageSent/markMessageFailed.
        if (pending) { row.classList.add('pending'); var seenEl0 = row.querySelector('.seen-status'); if (seenEl0) seenEl0.innerText = '○'; }
    }
    if (deleted) renderDeletedPlaceholder(row);

    entry.logEl.appendChild(row);

    // READ chỉ gửi khi tin thực sự lọt readObserver -- KHÔNG dùng cờ "seen" API để quyết định vì nó nghĩa "có AI KHÁC đã đọc chưa", không phải "chính mình đã đọc chưa" (trong nhóm >2 người seen=true dù mình chưa từng thấy tin). entry.skipReadTracking bật khi nạp đoạn lịch sử đã chắc chắn mình đọc rồi.
    var trackRead = !!(messageId && !deleted && !mine && !entry.skipReadTracking);
    if (trackRead) {
        entry.unreadIdSet.add(messageId);
        entry.readObserver.observe(row);
        // Chỉ cộng khi là tin SỐNG mới tới -- lazy-load nạp lại phần đã nằm trong totalUnreadCount ban đầu (server đã đếm rồi) thì không cộng thêm, tránh tăng gấp đôi.
        if (!entry.suppressAutoScroll) entry.totalUnreadCount = (entry.totalUnreadCount || 0) + 1;
    }

    // suppressAutoScroll: đang giữa 1 đợt nạp lịch sử hàng loạt -- vị trí cuộn do nơi gọi tự quyết định, không để từng dòng tự cuộn theo kiểu tin sống thường.
    if (!entry.suppressAutoScroll) {
        if (mine) {
            entry.stickToBottom = true;
            smoothScrollToBottom(entry.logEl);
        } else if (entry.stickToBottom) {
            smoothScrollToBottom(entry.logEl);
        } else if (trackRead) {
            bumpJumpBadge(entry);
        }
    }

    // Phải đo/canh cụm nút SAU khi row đã lên DOM thật (getBoundingClientRect cần layout thật).
    if (messageId) positionMsgActions(row, mine);
    entry.lastGroupRow = row;

    // Phải vẽ reaction SAU khi appendChild -- renderReactions tìm qua querySelector, node chưa lên DOM thật sẽ tìm ra null và âm thầm bỏ qua (bug thật đã gặp: reaction từ lịch sử không bao giờ hiện ra).
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

// Bong bóng kiểu Messenger dùng chung cho DM lẫn nhóm -- avatar/giờ "trôi" xuống dòng cuối cùng của 1 khối gộp (xem entry.lastGroupRow). Trả về row chưa gắn DOM -- caller tự appendChild + positionMsgActions.
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
        '<button type="button" class="forwardBtn fa-solid fa-share-from-square" title="Chuyển tiếp"></button>' +
        (mine ? '<button type="button" class="msgDeleteBtn fa-solid fa-trash" title="Xoá tin nhắn"></button>' : '') +
        '</div>';
    if (!mine) {
        var avatarEl = row.querySelector('.avatar');
        var nameEl = row.querySelector('.sender-name');
        var senderColor = avatarColor(fromUserId);
        var senderImageUrl = userAvatarUrl(fromUserId);
        applyAvatar(avatarEl, senderImageUrl ? {imageUrl: senderImageUrl} : {color: senderColor, initial: avatarInitial(name)});
        // Tên chỉ hiện ở tin đầu nhóm. Tô màu tên theo avatarColor() (kiểu Discord) -- hữu ích nhất trong nhóm đông người, với DM vô hại vì chỉ 1 người "theirs".
        if (grouped) nameEl.style.display = 'none'; else { nameEl.innerText = name; nameEl.style.color = senderColor; }
    }
    renderMessageContent(row.querySelector('.bubble'), body, onMediaReady);
    row.querySelector('.bubble-time-text').innerText = formatTime(tsEpochMillis);
    // Avatar/giờ chỉ hiện ở tin cuối khối gộp -- mỗi khi có tin mới cùng nhóm thì ẩn avatar/giờ của dòng vừa mất "ngôi mới nhất", để chúng luôn trôi xuống đúng dòng cuối.
    if (grouped && entry.lastGroupRow) {
        var prevTimeEl = entry.lastGroupRow.querySelector('.bubble-time');
        if (prevTimeEl) prevTimeEl.style.display = 'none';
        if (!mine) {
            var prevAvatarEl = entry.lastGroupRow.querySelector('.avatar');
            if (prevAvatarEl) prevAvatarEl.style.visibility = 'hidden';
        }
        entry.lastGroupRow.classList.add('has-next'); // bo phẳng góc dưới vì có tin theo sau (xem CSS .has-next)
    }
    return row;
}

// Canh .msg-actions chính giữa chiều cao CỦA BUBBLE (không phải cả .bubble-row) -- không dùng CSS tĩnh được vì bubble-col có thể có phần tử khác rộng hơn bubble (tên dài, giờ+seen), canh theo hàng sẽ lệch khỏi bubble thật.
var REACT_BTN_GAP_PX = 10;
// Khớp .linkPreviewCard{max-width:320px} và .bubble-col{max-width:68%} trong CSS -- phải tự nhân tay ở đây, xem positionMsgActions.
var LINK_PREVIEW_MAX_W = 320;
var BUBBLE_COL_MAX_RATIO = 0.68;
function positionMsgActions(row, mine) {
    var actionsEl = row.querySelector('.msg-actions');
    var bubbleEl = row.querySelector('.bubble');
    if (!actionsEl || !bubbleEl) return;
    // Gán width cố định (px) cho bubble chứa link preview NGAY ĐÂY (row đã lên DOM thật; lúc renderMessageContent build card thì row còn detached nên getComputedStyle trả rỗng) -- phá vòng lặp shrink-to-fit/%-width khiến card co về 0 rồi "nở" đột ngột lúc ảnh tải xong. Chỉ tính 1 lần qua dataset vì hàm này bị gọi lại nhiều lần.
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

// positionMsgActions() tính top/left theo px THEO ĐÚNG LÚC gọi (getBoundingClientRect() của bubble
// lúc đó) -- ổn khi mới render/hover, nhưng trở nên SAI ngay khi bề rộng .conv-log đổi sau đó (đóng/
// mở #infoPanel qua toggleInfoPanel, hoặc tự resize cửa sổ trình duyệt): bubble tự co giãn theo CSS
// (max-width:68% của .bubble-col) nên đổi vị trí/kích thước thật, nhưng .msg-actions vẫn đứng yên ở
// toạ độ px cũ đã lưu trong inline style -- bug thật đã gặp: "đóng info panel xong nút hành động
// không bám theo bong bóng nữa". Quan sát #chatMain (đổi kích thước bất kể do info panel hay do
// chính cửa sổ) bằng 1 ResizeObserver DUY NHẤT thay vì tự bắt riêng từng nguyên nhân, rồi định vị
// lại toàn bộ .msg-actions đang có trong card ĐANG active (card ẩn không cần, sẽ tự đúng khi
// selectConversation() sau này, xem positionMsgActions gọi lại lúc đó).
function repositionActiveMsgActions() {
    var activeCard = document.querySelector('.conv.active');
    if (!activeCard) return;
    activeCard.querySelectorAll('.bubble-row[data-message-id]').forEach(function (row) {
        positionMsgActions(row, row.classList.contains('mine'));
    });
}
if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(repositionActiveMsgActions).observe(document.getElementById('chatMain'));
}

// Cập nhật hiển thị "đã gửi/đã xem" (✓/✓✓) cho 1 tin của chính mình, kiểu Messenger.
function updateSeenDisplay(row, seen) {
    var seenEl = row.querySelector('.seen-status');
    if (!seenEl) return;
    seenEl.innerText = seen ? '✓✓' : '✓';
    seenEl.classList.toggle('seen', seen);
}

// Nhận frame SEEN -- tìm row trong TOÀN BỘ #chatMain (kể cả card không active) vì có thể xem lúc đang mở conversation khác.
function handleSeenReceived(conversationId, messageId) {
    if (!messageId) return;
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (!row) return;
    updateSeenDisplay(row, true);
}

