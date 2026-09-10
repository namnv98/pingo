// .attachMenu (nút "+" trong khu soạn tin) là phần tử RIÊNG của TỪNG card (không phải 1 singleton toàn
// cục như #reactionPicker) -- vẫn cần 1 biến TOÀN CỤC theo dõi "cái nào đang mở" (nếu có) để CÁC popup
// KHÁC (reaction/compose-emoji/gif/sticker) đóng được nó khi chúng mở lên, và ngược lại -- xem
// openAttachMenu bên trong ensureConversationCard.
var openAttachMenuEl = null;
function closeAttachMenu() {
    if (openAttachMenuEl) openAttachMenuEl.classList.remove('show');
    openAttachMenuEl = null;
}
document.addEventListener('click', closeAttachMenu);

// --- popup THẢ CẢM XÚC lên tin kiểu Facebook (1 người chỉ có 1 reaction/tin, chọn lại đúng emoji
// đang chọn để huỷ, chọn khác THAY THẾ, xem ChatSessionManager#handleReaction) -- LUÔN đúng 6 icon cố
// định trong images/chat (REACTIONS ở trên), KHÔNG liên quan gì tới popup CHÈN EMOJI vào ô nhập tin
// (xem khối riêng ensureComposeEmojiPicker bên dưới, dùng emoji-picker-element, đầy đủ bộ Unicode) --
// 2 khái niệm khác hẳn nhau: reaction là bộ nhỏ cố định có ý nghĩa xã hội, còn chèn emoji vào chữ thì
// cần đầy đủ như bàn phím thật, không thể dùng chung 1 popup nữa. ---

var pickerOnSelect = null; // callback(emoji) -- gán bởi openReactionPicker

function ensureReactionPicker() {
    var picker = document.getElementById('reactionPicker');
    if (picker) return picker;
    picker = document.createElement('div');
    picker.id = 'reactionPicker';
    document.body.appendChild(picker);
    return picker;
}

// Vẽ lại 6 nút icon reaction (images/chat) mỗi lần mở picker.
function populatePicker(picker) {
    picker.innerHTML = '';
    REACTIONS.forEach(function (r, idx) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.style.setProperty('--i', idx);
        btn.innerHTML = '<img src="' + r.icon + '" width="28" height="28" alt="' + r.emoji + '">';
        btn.onclick = function (e) {
            e.stopPropagation();
            if (pickerOnSelect) pickerOnSelect(r.emoji);
            closeReactionPicker();
        };
        picker.appendChild(btn);
    });
}

function openReactionPicker(triggerEl, conversationId, messageId) {
    closeReactionPicker(); // đóng picker cũ (nếu có) trước -- trả lại nút cũ về trạng thái ẩn theo hover bình thường
    closeComposeEmojiPicker(); // các popup emoji/sticker/GIF/attach không nên cùng mở 1 lúc
    closeGifPicker();
    closeStickerPicker();
    closeAttachMenu();
    closePinMenu();
    reactionPickerTarget = {conversationId: conversationId, messageId: messageId};
    pickerOnSelect = function (emoji) { sendReaction(conversationId, messageId, emoji); };
    populatePicker(ensureReactionPicker());
    positionAndShowPicker(triggerEl);
}

function positionAndShowPicker(triggerEl) {
    var picker = ensureReactionPicker();
    reactionPickerTriggerEl = triggerEl;
    // Giữ CẢ CỤM .msg-actions (không chỉ riêng nút 🙂) hiện CƯỠNG BỨC trong lúc popup còn mở --
    // picker gắn vào document.body (ngoài .bubble-row), nên chuột rời hàng tin để bấm chọn emoji sẽ
    // mất :hover, cả cụm sẽ tự ẩn theo CSS mặc định (opacity theo :hover) nếu không có class này ghi
    // đè, trong khi popup vẫn đang hiện -- nhìn rất kỳ (mũi tên popup trỏ xuống 1 chỗ trống).
    var actionsEl = triggerEl.closest('.msg-actions');
    if (actionsEl) actionsEl.classList.add('picker-open');

    // Hiện trước rồi mới đo (offsetWidth) -- kích thước thật của popup tuỳ font/emoji hệ điều hành,
    // không đoán cứng 1 con số (dễ sai lệch giữa các máy khác nhau).
    picker.classList.add('show');
    var rect = triggerEl.getBoundingClientRect();
    var buttonCenterX = rect.left + rect.width / 2;
    var margin = 8;
    var pickerWidth = picker.offsetWidth;
    var idealLeft = buttonCenterX - pickerWidth / 2;
    // Kẹp popup trong viewport -- sát lề trái/phải thì tự đẩy sang phía ngược lại cho đủ chỗ thay vì
    // bị cắt (tràn ra ngoài màn hình, phần bị cắt không bấm được).
    var clampedLeft = Math.max(margin, Math.min(window.innerWidth - pickerWidth - margin, idealLeft));
    picker.style.left = clampedLeft + 'px';
    picker.style.top = Math.max(margin, rect.top - 46) + 'px';
    // Popup bị đẩy lệch khỏi vị trí "giữa nút" lý tưởng thì mũi tên phải tự bù lại, luôn trỏ đúng
    // vào giữa nút kích hoạt -- không để mũi tên trỏ vào khoảng trống do popup đã bị đẩy sang bên.
    var arrowLeft = buttonCenterX - clampedLeft;
    arrowLeft = Math.max(14, Math.min(pickerWidth - 14, arrowLeft));
    picker.style.setProperty('--arrow-left', arrowLeft + 'px');
}

function closeReactionPicker() {
    var picker = document.getElementById('reactionPicker');
    if (picker) picker.classList.remove('show');
    if (reactionPickerTriggerEl) {
        var actionsEl = reactionPickerTriggerEl.closest('.msg-actions');
        if (actionsEl) actionsEl.classList.remove('picker-open');
    }
    reactionPickerTarget = null;
    reactionPickerTriggerEl = null;
    pickerOnSelect = null;
}
document.addEventListener('click', closeReactionPicker);

// --- popup GHIM (pinBtn -- .msg-actions) -- menu nhỏ 2 lựa chọn (chung/riêng), dùng lại đúng cách
// định vị/kẹp-trong-viewport của #reactionPicker (positionAndShowPicker) nhưng KHÔNG dùng chung 1
// phần tử -- #reactionPicker là lưới emoji cố định 6 icon, #pinMenu là danh sách nút chữ dọc, khác
// hẳn cấu trúc. "Bỏ ghim" KHÔNG có ở đây -- làm ở tab Pins (nút ✕ trên từng dòng, xem
// renderPinsList) vì menu này không biết trước tin đang được ghim ở chế độ nào (không giữ state
// ghim cho MỌI tin đang hiển thị, tránh phải gọi thêm API cho từng bubble).
var pinMenuTarget = null;
var pinMenuTriggerEl = null; // nút 📌 đã mở menu hiện tại -- giữ hiện cưỡng bức (class "picker-open") tới khi đóng, cùng cơ chế reactionPickerTriggerEl

function ensurePinMenu() {
    var menu = document.getElementById('pinMenu');
    if (menu) return menu;
    menu = document.createElement('div');
    menu.id = 'pinMenu';
    menu.innerHTML =
        '<button type="button" class="fa-solid fa-thumbtack" data-scope="shared"><span>Ghim cho mọi người</span></button>' +
        '<button type="button" class="fa-solid fa-lock" data-scope="private"><span>Chỉ ghim cho tôi</span></button>';
    menu.addEventListener('click', function (e) { e.stopPropagation(); });
    Array.prototype.forEach.call(menu.querySelectorAll('button'), function (btn) {
        btn.onclick = function (e) {
            e.stopPropagation();
            if (pinMenuTarget) sendPin(pinMenuTarget.conversationId, pinMenuTarget.messageId, btn.dataset.scope, true);
            closePinMenu();
        };
    });
    document.body.appendChild(menu);
    return menu;
}

function openPinMenu(triggerEl, conversationId, messageId) {
    closeReactionPicker();
    closeComposeEmojiPicker();
    closeGifPicker();
    closeStickerPicker();
    closeAttachMenu();
    closePinMenu();
    pinMenuTarget = {conversationId: conversationId, messageId: messageId};
    var menu = ensurePinMenu();
    // Giữ CẢ CỤM .msg-actions hiện cưỡng bức trong lúc menu còn mở -- menu gắn vào document.body
    // (ngoài .bubble-row), nên rê chuột từ nút 📌 sang menu sẽ mất :hover của hàng tin, cả cụm nút
    // (react/reply/delete) sẽ tự ẩn theo CSS mặc định nếu không có class này ghi đè -- đúng bug đã
    // gặp với #reactionPicker trước đây (xem positionAndShowPicker), quên áp dụng lại ở đây.
    pinMenuTriggerEl = triggerEl;
    var actionsEl = triggerEl.closest('.msg-actions');
    if (actionsEl) actionsEl.classList.add('picker-open');
    menu.classList.add('show');
    var rect = triggerEl.getBoundingClientRect();
    var margin = 8;
    var menuWidth = menu.offsetWidth || 180;
    var idealLeft = rect.left + rect.width / 2 - menuWidth / 2;
    var clampedLeft = Math.max(margin, Math.min(window.innerWidth - menuWidth - margin, idealLeft));
    menu.style.left = clampedLeft + 'px';
    menu.style.top = Math.max(margin, rect.top - menu.offsetHeight - 10) + 'px';
}

function closePinMenu() {
    var menu = document.getElementById('pinMenu');
    if (menu) menu.classList.remove('show');
    if (pinMenuTriggerEl) {
        var actionsEl = pinMenuTriggerEl.closest('.msg-actions');
        if (actionsEl) actionsEl.classList.remove('picker-open');
    }
    pinMenuTarget = null;
    pinMenuTriggerEl = null;
}
document.addEventListener('click', closePinMenu);

/**
 * Ghim/bỏ ghim tin qua WS -- server tự persist (bảng message_pins_shared/message_pins_private tuỳ
 * scope) rồi fan-out (CHỈ scope 'shared', xem ChatSessionManager#handlePin bên colony). Ghim riêng
 * không có xác nhận nào bay về -- refresh lại tab Pins (nếu đang mở) để thấy ngay.
 */
function sendPin(conversationId, messageId, scope, pinned) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    send({type: 'PIN', id: messageId, conversationId: conversationId, body: {scope: scope, pinned: pinned}});
    if (scope === 'private' && infoActiveTab === 'pins' && activeConversationId === conversationId) {
        loadConversationPins(conversationId);
    }
}

// Nhận frame PIN (chỉ bay tới với scope 'shared', xem javadoc MessageType#PIN) -- cập nhật lại tab
// Pins nếu đang mở đúng conversation đó.
function handlePinReceived(conversationId, messageId, fromUserId, pinned) {
    if (infoActiveTab === 'pins' && activeConversationId === conversationId) {
        loadConversationPins(conversationId);
    }
}

// --- popup CHÈN EMOJI vào ô nhập tin (composeEmojiBtn) -- dùng emoji-picker-element (open-source,
// MIT, xem <script type="module"> ở <head>) thay vì tự vẽ tay: đầy đủ bộ Unicode + tìm kiếm + phân
// loại + skin-tone, không giới hạn 6 icon như popup reaction. Custom element <emoji-picker> tự quản lý
// toàn bộ nội dung/scroll/tìm kiếm bên trong Shadow DOM -- phần này chỉ lo tạo/định vị/ẩn-hiện, không
// đụng vào bên trong nó. ---

var composeEmojiPickerTarget = null; // callback(emoji) -- gán mỗi lần mở, giống pickerOnSelect của reaction
var composeEmojiPickerTriggerEl = null;

function ensureComposeEmojiPicker() {
    var picker = document.getElementById('composeEmojiPicker');
    if (picker) return picker;
    picker = document.createElement('emoji-picker');
    picker.id = 'composeEmojiPicker';
    // Bấm bất kỳ đâu TRONG picker (ô tìm kiếm, đổi tab phân loại, chọn emoji...) không được coi là
    // "bấm ra ngoài" -- không thì document click listener (đóng picker, xem cuối hàm) đóng ngay giữa
    // lúc đang gõ tìm kiếm hay chỉ mới đổi tab, chưa kịp chọn gì.
    picker.addEventListener('click', function (e) { e.stopPropagation(); });
    // "emoji-click" là sự kiện riêng của emoji-picker-element, bắn ra ngay khi người dùng bấm chọn 1
    // emoji -- e.detail.unicode là ký tự emoji thật (vd "😀"), đúng thứ insertEmojiAtCursor cần.
    picker.addEventListener('emoji-click', function (e) {
        if (composeEmojiPickerTarget) composeEmojiPickerTarget(e.detail.unicode);
        closeComposeEmojiPicker();
    });
    document.body.appendChild(picker);
    return picker;
}

function openComposeEmojiPicker(triggerEl, inputEl) {
    closeReactionPicker(); // các popup emoji/sticker/GIF/attach không nên cùng mở 1 lúc
    closeGifPicker();
    closeStickerPicker();
    closeAttachMenu();
    var picker = ensureComposeEmojiPicker();
    composeEmojiPickerTarget = function (emoji) { insertEmojiAtCursor(inputEl, emoji); };
    composeEmojiPickerTriggerEl = triggerEl;
    picker.classList.add('show');
    positionComposeEmojiPicker(triggerEl, picker);
}

// Canh popup ngay TRÊN nút bấm (composeEmojiBtn/attachMenuBtn/... luôn nằm ở khu vực soạn tin, sát đáy
// màn hình) -- picker khá to (search box + lưới emoji/GIF) nên mở LÊN TRÊN mới đủ chỗ, chỉ mở XUỐNG nếu
// màn hình quá thấp (không đủ chỗ phía trên, hiếm khi xảy ra với layout chat bình thường).
// CĂN GIỮA theo TÂM nút (không phải áp mép phải) -- bug thật đã gặp: áp mép phải chỉ đúng cho MỖI
// composeEmojiBtn (hàm này viết riêng cho nó lúc đầu, nút luôn nằm gần rìa PHẢI khu soạn tin nên áp mép
// phải giữ popup không tràn qua phải) -- giờ hàm này dùng CHUNG cho cả attachMenuBtn (nằm rìa TRÁI) nên
// áp mép phải làm popup lệch hẳn sang 1 bên thay vì đứng giữa nút. Căn giữa theo tâm nút mới ĐÚNG cho
// MỌI nút bất kể nằm bên nào của hàng, và vẫn được kẹp trong viewport như cũ nên không lo tràn màn hình.
function positionComposeEmojiPicker(triggerEl, picker) {
    var rect = triggerEl.getBoundingClientRect();
    var margin = 8;
    var pickerWidth = picker.offsetWidth || 352;
    var pickerHeight = picker.offsetHeight || 420;
    var idealLeft = rect.left + rect.width / 2 - pickerWidth / 2;
    var clampedLeft = Math.max(margin, Math.min(window.innerWidth - pickerWidth - margin, idealLeft));
    var top = rect.top - pickerHeight - margin;
    if (top < margin) top = Math.min(rect.bottom + margin, window.innerHeight - pickerHeight - margin);
    picker.style.left = clampedLeft + 'px';
    picker.style.top = top + 'px';
}

function closeComposeEmojiPicker() {
    var picker = document.getElementById('composeEmojiPicker');
    if (picker) picker.classList.remove('show');
    composeEmojiPickerTarget = null;
    composeEmojiPickerTriggerEl = null;
}
document.addEventListener('click', closeComposeEmojiPicker);

// --- popup GIF (.gifTrayBtn) VÀ STICKER (.stickerTrayBtn) -- CÙNG dùng API Giphy, DÙNG CHUNG 1 bộ UI
// (tìm kiếm + lưới ảnh) vì hệt nhau, chỉ khác đường dẫn API -- Giphy có 2 catalog TÁCH RIÊNG hẳn nhau:
// /v1/gifs/... (GIF thường) và /v1/stickers/... (is_sticker:1 -- ảnh minh hoạ nhân vật/biểu cảm, nền
// trong suốt, ĐÚNG NGHĨA "sticker" thật, khác hẳn GIF thường) -- xem lịch sử sửa: bản đầu lỡ dùng ảnh
// Twemoji (chỉ là emoji, không phải sticker) cho mục sticker, giờ đổi đúng sang catalog sticker thật
// của chính Giphy, dùng LUÔN key đang có, không cần đăng ký thêm dịch vụ nào khác.
//
// KHÔNG có lựa chọn mã nguồn mở cho GIF/sticker: đây là nội dung có bản quyền, không ai tự host nổi 1
// bộ "hàng nghìn GIF/sticker" mã nguồn mở như đã trao đổi. ĐÃ THỬ 2 key demo công khai phổ biến (Giphy
// "dc6zaTOxFJmzC" -- Giphy tự chặn luôn rồi, trả 403 BANNED khi gọi thật; Tenor v1 "LIVDSRZULELA" --
// Tenor đã khai tử hẳn API v1 từ lâu, v2 bắt buộc đăng ký key riêng qua Google Cloud, không còn key demo
// dùng chung) -- CẢ 2 đều chết, không dùng được nữa. Để trống GIPHY_API_KEY, tự đăng ký 1 key MIỄN PHÍ
// tại developers.giphy.com (vài phút, không cần thẻ) rồi dán vào đây là chạy được ngay cho cả 2 -- không
// giả vờ chạy được rồi lỗi khó hiểu, xem nhánh "chưa có key" trong loadMediaItems. Tự vẽ tay ô tìm kiếm
// + lưới ảnh (không có SDK vanilla JS gọn nhẹ nào tương đương emoji-picker-element cho GIF/sticker),
// dùng CHUNG logic định vị popup (positionComposeEmojiPicker) cho đồng bộ vị trí/kích thước. ---
var GIPHY_API_KEY = 'PipdjFdjE9cnkC0B7sykyRPP5HJyiKgl'; // key riêng của bạn, tự đăng ký tại developers.giphy.com
var mediaPickerTarget = null; // callback(item) -- gán mỗi lần mở, giống composeEmojiPickerTarget
var mediaPickerSearchDebounce = null;
// Gõ nhanh (mỗi ký tự bắn 1 request debounce) -- request GÕ SAU có thể trả lời VỀ TRƯỚC request gõ
// trước nếu mạng chập chờn, ghi đè nhầm kết quả mới hơn bằng kết quả cũ -- so số thứ tự, chỉ render nếu
// đúng là request MỚI NHẤT đã gửi, bỏ qua mọi phản hồi trễ của request cũ hơn.
var mediaPickerRequestSeq = 0;

// kind: 'gifs' hoặc 'stickers' -- ĐÚNG tên nhánh API Giphy (/v1/gifs/... hay /v1/stickers/...), dùng
// LUÔN làm id DOM (#gifPicker/#stickerPicker) cho gọn -- 2 phần tử TÁCH RIÊNG (không dùng chung 1 <div>
// đổi nội dung) để tránh phải render lại toàn bộ lưới mỗi lần đổi qua lại giữa GIF/sticker.
function mediaPickerElId(kind) { return kind === 'stickers' ? 'stickerPicker' : 'gifPicker'; }

function ensureMediaPicker(kind) {
    var id = mediaPickerElId(kind);
    var picker = document.getElementById(id);
    if (picker) return picker;
    picker = document.createElement('div');
    picker.id = id;
    picker.className = 'mediaPicker';
    picker.innerHTML =
        '<input type="text" class="mediaPickerSearch" placeholder="' + (kind === 'stickers' ? 'Tìm sticker...' : 'Tìm GIF...') + '">' +
        '<div class="mediaPickerGrid"></div>';
    // Bấm bất kỳ đâu TRONG picker (gõ tìm kiếm, bấm ảnh...) không bị coi là "bấm ra ngoài" -- cùng lý
    // do với #composeEmojiPicker.
    picker.addEventListener('click', function (e) { e.stopPropagation(); });
    var searchEl = picker.querySelector('.mediaPickerSearch');
    searchEl.addEventListener('input', function () {
        if (mediaPickerSearchDebounce) clearTimeout(mediaPickerSearchDebounce);
        var q = searchEl.value.trim();
        mediaPickerSearchDebounce = setTimeout(function () { loadMediaItems(kind, q); }, 350);
    });
    document.body.appendChild(picker);
    return picker;
}

function renderMediaResults(kind, items) {
    var grid = document.querySelector('#' + mediaPickerElId(kind) + ' .mediaPickerGrid');
    if (!grid) return;
    if (!items.length) {
        grid.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Không tìm thấy kết quả nào.</div>';
        return;
    }
    grid.innerHTML = '';
    items.forEach(function (it) {
        var img = document.createElement('img');
        img.src = it.previewUrl;
        img.alt = it.title || kind;
        img.loading = 'lazy';
        img.onclick = function () {
            if (mediaPickerTarget) mediaPickerTarget(it);
            closeMediaPicker(kind);
        };
        grid.appendChild(img);
    });
}

// q rỗng -- nạp THỊNH HÀNH (trending); có q -- tìm theo từ khoá. previewUrl (fixed_height_small, nhỏ/
// nhẹ) chỉ dùng để HIỆN lưới cho nhanh -- sendUrl (fixed_height, ảnh động đủ lớn) mới là thứ GỬI ĐI (xem
// openGifPicker/openStickerPicker/sendExternalImageMessage) -- 2 URL khác nhau, không gửi nhầm bản xem
// trước bé tí.
function loadMediaItems(kind, q) {
    var grid = document.querySelector('#' + mediaPickerElId(kind) + ' .mediaPickerGrid');
    if (!grid) return;
    // Chưa dán key -- báo rõ THIẾU KEY (kèm hướng dẫn) thay vì âm thầm gọi API chắc chắn lỗi (401/403)
    // rồi hiện "Lỗi tải" chung chung, khó hiểu vì sao. Xem giải thích key ở khai báo GIPHY_API_KEY.
    if (!GIPHY_API_KEY) {
        grid.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Chưa cấu hình Giphy API key.<br>Đăng ký miễn phí tại developers.giphy.com rồi dán vào biến GIPHY_API_KEY trong assets/js/compose-reactions-pins.js.</div>';
        return;
    }
    grid.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Đang tải...</div>';
    var seq = ++mediaPickerRequestSeq;
    var endpoint = q
        ? 'https://api.giphy.com/v1/' + kind + '/search?q=' + encodeURIComponent(q) + '&limit=24&rating=g'
        : 'https://api.giphy.com/v1/' + kind + '/trending?limit=24&rating=g';
    fetch(endpoint + '&api_key=' + GIPHY_API_KEY)
        .then(function (res) { return res.json(); })
        .then(function (json) {
            if (seq !== mediaPickerRequestSeq) return;
            var items = (json.data || []).map(function (item) {
                var previewImg = item.images.fixed_height_small || item.images.fixed_height || item.images.original;
                var sendImg = item.images.fixed_height || item.images.original;
                return {
                    title: item.title,
                    previewUrl: previewImg.url,
                    sendUrl: sendImg.url,
                    width: parseInt(sendImg.width, 10) || null,
                    height: parseInt(sendImg.height, 10) || null
                };
            });
            renderMediaResults(kind, items);
        })
        .catch(function (err) {
            if (seq !== mediaPickerRequestSeq) return;
            var grid2 = document.querySelector('#' + mediaPickerElId(kind) + ' .mediaPickerGrid');
            if (grid2) grid2.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Lỗi tải: ' + err.message + '</div>';
        });
}

function openMediaPicker(kind, triggerEl, conversationId) {
    closeReactionPicker();
    closeComposeEmojiPicker();
    closeAttachMenu();
    closeMediaPicker(kind === 'stickers' ? 'gifs' : 'stickers'); // đóng nốt cái CÒN LẠI (gif hoặc sticker)
    var picker = ensureMediaPicker(kind);
    // Field "url" (fixed_height.url) LUÔN là .gif -- kể cả bên catalog /stickers/ (đã tự kiểm chứng qua
    // API thật) dù Giphy CÓ trả thêm rendition .webp/.mp4 riêng -- dùng đúng field đang lấy (sendImg.url,
    // xem loadMediaItems), không đoán mime theo kind.
    var fileNamePrefix = kind === 'stickers' ? 'sticker' : 'giphy';
    mediaPickerTarget = function (it) { sendExternalImageMessage(conversationId, it.sendUrl, 'image/gif', it.width, it.height, fileNamePrefix + '.gif'); };
    picker.classList.add('show');
    positionComposeEmojiPicker(triggerEl, picker); // dùng chung logic định vị với #composeEmojiPicker
    picker.querySelector('.mediaPickerSearch').value = '';
    loadMediaItems(kind, '');
}

function closeMediaPicker(kind) {
    var picker = document.getElementById(mediaPickerElId(kind));
    if (picker) picker.classList.remove('show');
    mediaPickerTarget = null;
}
function openGifPicker(triggerEl, conversationId) { openMediaPicker('gifs', triggerEl, conversationId); }
function openStickerPicker(triggerEl, conversationId) { openMediaPicker('stickers', triggerEl, conversationId); }
function closeGifPicker() { closeMediaPicker('gifs'); }
function closeStickerPicker() { closeMediaPicker('stickers'); }
document.addEventListener('click', closeGifPicker);
document.addEventListener('click', closeStickerPicker);

// Chèn 1 emoji vào ĐÚNG vị trí con trỏ trong ô nhập (không phải luôn nối cuối chuỗi -- gõ dở giữa
// câu rồi chèn emoji phải chèn đúng chỗ đang gõ). Bắn lại sự kiện "input" để mọi thứ đang lắng nghe
// input đó (updateSendButtonState, báo "đang gõ") tự chạy lại như khi gõ tay, không cần gọi trùng.
function insertEmojiAtCursor(inputEl, emoji) {
    var start = inputEl.selectionStart != null ? inputEl.selectionStart : inputEl.value.length;
    var end = inputEl.selectionEnd != null ? inputEl.selectionEnd : inputEl.value.length;
    inputEl.value = inputEl.value.slice(0, start) + emoji + inputEl.value.slice(end);
    var newPos = start + emoji.length;
    inputEl.focus();
    inputEl.setSelectionRange(newPos, newPos);
    inputEl.dispatchEvent(new Event('input', {bubbles: true}));
}

// Upload ảnh/video: thẳng lên file-server (multipart, KHÔNG qua WebSocket -- file có thể nặng vài MB,
// không hợp để nhét vào 1 frame WS như tin nhắn chữ), server trả về fileId, tự dựng URL tải xuống.
// KHÔNG tự gửi frame MESSAGE ở hàm này -- xem uploadAndSendFiles bên dưới, gộp kết quả upload của
// nhiều file (nếu có) vào ĐÚNG 1 tin.
//
// fileMime/fileName gắn qua QUERY STRING (không phải multipart header) -- upload.lua bên file-server
// đọc content-type qua "?fileMime=" thay vì tự phân tích header multipart thật (nguyên bản đã vậy).
// token cũng qua query (?token=) vì jad.create_file_path không forward header Authorization cho
// route tạo file (xem HallApiHandlers#createFile).
// Upload 1 file, trả về Promise({fileUrl, fileId, fileMime, fileName}) -- KHÔNG tự gửi frame MESSAGE
// (xem uploadAndSendFiles bên dưới, gọi hàm này song song cho mọi file rồi mới gộp lại gửi ĐÚNG 1 tin
// duy nhất, giống Telegram/Messenger gộp cả album vào 1 bong bóng thay vì tách rời từng ảnh/video
// thành nhiều tin lẻ).
//
// conversationId đi kèm để hall ghi vào bảng files -- cần cho tab "Files" (liệt kê lại đúng file
// của ĐÚNG conversation này, xem loadConversationFiles/FileRegistry#listForConversation).
// Đọc trước kích thước THẬT (width/height) của ảnh/video NGAY TRÊN MÁY người gửi -- trước khi kịp
// upload lên server, không cần đợi server dựng xong thumbnail (video) hay tải xong ảnh mới biết. Đọc
// qua chính File local (URL.createObjectURL, KHÔNG qua mạng) nên gần như tức thời. Gắn kèm vào
// body.files[i] lúc gửi tin (xem uploadOneFile) để BÊN NHẬN (kể cả trên thiết bị khác, hay load lại
// trang) cũng biết trước tỷ lệ khung hình mà chừa sẵn chỗ đúng kích cỡ, xem CSS .msg-media +
// renderMessageContent -- không phải chỉ người gửi mới hưởng lợi.
function getMediaDimensions(file) {
    var mime = file.type || '';
    var isImage = mime.indexOf('image/') === 0;
    var isVideo = mime.indexOf('video/') === 0;
    if (!isImage && !isVideo) return Promise.resolve(null);
    return new Promise(function (resolve) {
        var url = URL.createObjectURL(file);
        var el = isImage ? new Image() : document.createElement('video');
        var done = false;
        var finish = function (result) {
            if (done) return; // .onerror có thể bắn SAU .onloadedmetadata/.onload nếu decode lỗi giữa chừng -- chỉ resolve 1 lần
            done = true;
            clearTimeout(timer);
            URL.revokeObjectURL(url);
            if (el.parentNode) el.parentNode.removeChild(el);
            resolve(result);
        };
        // Gắn vào DOM THẬT (ẩn ngoài màn hình, không display:none -- phần tử display:none có thể
        // KHÔNG tải metadata ở 1 số engine) thay vì để hoàn toàn rời DOM -- 1 số trình duyệt (đặc biệt
        // Safari/WebKit cũ) không đáng tin cậy bắn loadedmetadata cho <video> chưa từng gắn vào cây
        // DOM, khiến getMediaDimensions() luôn rơi vào nhánh lỗi/timeout, mất luôn kích thước thật --
        // đây chính là nguyên nhân khiến placeholder đôi khi vẫn "nẩy" (không đặt được aspect-ratio vì
        // width/height là null, xem renderMessageContent/renderLightboxCurrent).
        el.style.cssText = 'position:fixed; left:-9999px; top:-9999px; width:1px; height:1px; opacity:0; pointer-events:none;';
        document.body.appendChild(el);
        // Timeout an toàn -- file hỏng/codec lạ có thể không bao giờ bắn onload/onloadedmetadata LẪN
        // onerror ở vài trường hợp hiếm, không được treo Promise mãi mãi (uploadOneFile đang chờ nó).
        var timer = setTimeout(function () { finish(null); }, 4000);
        if (isImage) {
            el.onload = function () { finish({width: el.naturalWidth, height: el.naturalHeight}); };
            el.onerror = function () { finish(null); };
            el.src = url;
        } else {
            el.preload = 'metadata';
            el.muted = true;
            el.onloadedmetadata = function () { finish({width: el.videoWidth, height: el.videoHeight}); };
            el.onerror = function () { finish(null); };
            el.src = url;
        }
    });
}

function uploadOneFile(conversationId, file) {
    if (file.size > MAX_UPLOAD_BYTES) {
        return Promise.reject(new Error('File "' + file.name + '" quá lớn (tối đa ' + (MAX_UPLOAD_BYTES / 1024 / 1024) + 'MB)'));
    }
    var mime = file.type || 'application/octet-stream';
    var uploadUrl = FILE_SERVER_BASE + '/v2/api/upload'
        + '?token=' + encodeURIComponent(authToken)
        + '&fileMime=' + encodeURIComponent(mime)
        + '&fileName=' + encodeURIComponent(file.name)
        + '&conversationId=' + encodeURIComponent(conversationId);
    var formData = new FormData();
    formData.append('file', file, file.name);

    // Đọc kích thước local SONG SONG với upload (không đợi lẫn nhau) -- đằng nào cũng phải đợi upload
    // xong mới gửi tin được, đọc kích thước gần như tức thời nên không kéo dài thời gian chờ thực tế.
    return Promise.all([
        fetch(uploadUrl, {method: 'POST', body: formData}).then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        }),
        getMediaDimensions(file)
    ]).then(function (results) {
        var data = results[0];
        var dims = results[1];
        if (!data || !data.id) throw new Error(JSON.stringify(data));
        var fileUrl = FILE_SERVER_BASE + '/v2/api/download?id=' + encodeURIComponent(data.id);
        // fileId riêng (không chỉ fileUrl) -- cần để tự dựng URL /v2/api/thumbnail (poster cho
        // video, xem renderMessageContent) mà không phải parse ngược lại từ fileUrl.
        var result = {fileUrl: fileUrl, fileId: data.id, fileMime: data.mime || mime, fileName: file.name};
        if (dims) { result.width = dims.width; result.height = dims.height; }
        return result;
    });
}

// Upload TẤT CẢ file song song (Promise.all), rồi gửi ĐÚNG 1 frame MESSAGE với body.files = [...] --
// từ góc nhìn colony/harbor đây vẫn chỉ là 1 tin nhắn như mọi tin khác (body luôn là JSON mờ, server
// không đọc nội dung bên trong, xem ARCHITECTURE.md), không cần thêm MessageType/FrameType riêng cho
// "tin có nhiều file". Nếu 1 file lỗi giữa chừng thì KHÔNG gửi tin nào cả (tránh gửi thiếu file so với
// những gì người dùng thực sự chọn) -- báo lỗi rõ file nào hỏng.
function uploadAndSendFiles(conversationId, files, caption, replyTo) {
    Promise.all(files.map(function (file) { return uploadOneFile(conversationId, file); }))
        .then(function (uploaded) {
            var b = {message: caption || '', files: uploaded};
            if (replyTo) b.replyTo = replyTo;
            send({
                type: 'MESSAGE',
                id: newId(),
                conversationId: conversationId,
                body: b
            });
        })
        .catch(function (err) {
            appAlert('Gửi file lỗi: ' + err.message, 'Lỗi gửi file');
        });
}

// Gửi 1 tin ẢNH TRỎ THẲNG TỚI URL NGOÀI (sticker Twemoji, GIF Giphy...) -- KHÔNG upload lại qua chính
// file-server của app như uploadAndSendFiles() (ảnh/video người dùng tự chọn từ máy), vì file đã có sẵn
// 1 URL public rồi, tải về rồi tải lên lại tốn băng thông + độ trễ vô ích. body.files dùng CHUNG đúng
// shape {fileUrl, fileMime, fileName, width, height} mà getMessageFiles/renderMessageContent đã hiểu --
// server (colony) chỉ lưu nguyên JSON client gửi, không validate domain của fileUrl (giống body.preview
// của link preview), nên trỏ thẳng ra ngoài vẫn hiển thị đúng cho MỌI người trong hội thoại (ai cũng tự
// tải ảnh từ URL đó, không phải chỉ máy mình). width/height gán THẲNG (biết trước, không cần đo) để
// tránh đúng bug "layout nhảy lúc ảnh tải xong" đã sửa cho link preview trước đó.
function sendExternalImageMessage(conversationId, url, mime, width, height, fileName) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    send({
        type: 'MESSAGE',
        id: newId(),
        conversationId: conversationId,
        body: {message: '', files: [{fileUrl: url, fileMime: mime, fileName: fileName, width: width, height: height}]}
    });
}

