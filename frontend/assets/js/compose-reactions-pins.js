// .attachMenu là phần tử riêng của từng card (không phải singleton) nhưng vẫn cần biến toàn cục để các popup khác đóng nó khi chúng mở lên, và ngược lại.
var openAttachMenuEl = null;
function closeAttachMenu() {
    if (openAttachMenuEl) openAttachMenuEl.classList.remove('show');
    openAttachMenuEl = null;
}
document.addEventListener('click', closeAttachMenu);

// Popup reaction (Facebook-style, 6 icon cố định trong REACTIONS) khác hẳn popup chèn emoji vào ô nhập (ensureComposeEmojiPicker, bộ Unicode đầy đủ) -- không dùng chung vì mục đích khác nhau.

var pickerOnSelect = null; // callback(emoji) -- gán bởi openReactionPicker

function ensureReactionPicker() {
    var picker = document.getElementById('reactionPicker');
    if (picker) return picker;
    picker = document.createElement('div');
    picker.id = 'reactionPicker';
    document.body.appendChild(picker);
    return picker;
}

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
    // picker gắn vào document.body (ngoài .bubble-row) nên rời chuột mất :hover -- ép hiện .msg-actions bằng class "picker-open" để không tự ẩn khi popup còn mở.
    var actionsEl = triggerEl.closest('.msg-actions');
    if (actionsEl) actionsEl.classList.add('picker-open');

    // Hiện trước rồi mới đo offsetWidth -- kích thước popup tuỳ font/emoji hệ điều hành, không đoán cứng.
    picker.classList.add('show');
    var rect = triggerEl.getBoundingClientRect();
    var buttonCenterX = rect.left + rect.width / 2;
    var margin = 8;
    var pickerWidth = picker.offsetWidth;
    var idealLeft = buttonCenterX - pickerWidth / 2;
    var clampedLeft = Math.max(margin, Math.min(window.innerWidth - pickerWidth - margin, idealLeft));
    picker.style.left = clampedLeft + 'px';
    picker.style.top = Math.max(margin, rect.top - 46) + 'px';
    // Popup bị đẩy lệch khỏi tâm nút thì mũi tên phải tự bù lại để luôn trỏ đúng vào nút kích hoạt.
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

// Popup ghim (2 lựa chọn chung/riêng) -- "Bỏ ghim" không có ở đây, làm ở tab Pins vì menu này không biết trước tin đang ghim ở chế độ nào (không giữ state ghim cho mọi tin đang hiển thị).
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
    // Cùng bug :hover như #reactionPicker (xem positionAndShowPicker) -- ép hiện .msg-actions bằng "picker-open" khi menu còn mở.
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

// Ghim/bỏ ghim qua WS -- server chỉ fan-out lại frame PIN cho scope 'shared', nên scope 'private' phải tự refresh tab Pins ở đây vì không có xác nhận nào bay về.
function sendPin(conversationId, messageId, scope, pinned) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    send({type: 'PIN', id: messageId, conversationId: conversationId, body: {scope: scope, pinned: pinned}});
    if (scope === 'private' && infoActiveTab === 'pins' && activeConversationId === conversationId) {
        loadConversationPins(conversationId);
    }
}

// Frame PIN chỉ bay tới với scope 'shared' (xem MessageType#PIN) -- cập nhật tab Pins nếu đang mở đúng conversation đó.
function handlePinReceived(conversationId, messageId, fromUserId, pinned) {
    if (infoActiveTab === 'pins' && activeConversationId === conversationId) {
        loadConversationPins(conversationId);
    }
}

// Popup chèn emoji dùng thư viện emoji-picker-element (custom element, tự quản lý nội dung trong Shadow DOM) -- phần này chỉ lo tạo/định vị/ẩn-hiện.

var composeEmojiPickerTarget = null; // callback(emoji) -- gán mỗi lần mở, giống pickerOnSelect của reaction
var composeEmojiPickerTriggerEl = null;

function ensureComposeEmojiPicker() {
    var picker = document.getElementById('composeEmojiPicker');
    if (picker) return picker;
    picker = document.createElement('emoji-picker');
    picker.id = 'composeEmojiPicker';
    // Chặn bubble để bấm trong picker không bị document click listener (đóng picker) coi là "bấm ra ngoài".
    picker.addEventListener('click', function (e) { e.stopPropagation(); });
    // "emoji-click" là sự kiện riêng của emoji-picker-element -- e.detail.unicode là ký tự emoji thật.
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

// Dùng chung cho nhiều nút (composeEmojiBtn, attachMenuBtn...) nên căn giữa theo TÂM nút, không áp mép -- bug đã gặp: áp mép phải chỉ đúng khi hàm này còn viết riêng cho composeEmojiBtn (nằm rìa phải).
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

// GIF và sticker dùng chung UI (API Giphy, 2 catalog riêng /v1/gifs và /v1/stickers) -- cần tự đăng ký GIPHY_API_KEY miễn phí tại developers.giphy.com (không có key demo dùng chung nào còn sống).
var GIPHY_API_KEY = 'PipdjFdjE9cnkC0B7sykyRPP5HJyiKgl'; // key riêng của bạn, tự đăng ký tại developers.giphy.com
var mediaPickerTarget = null; // callback(item) -- gán mỗi lần mở, giống composeEmojiPickerTarget
var mediaPickerSearchDebounce = null;
// Request gõ sau có thể trả lời về trước request gõ trước (mạng chập chờn) -- so số thứ tự, chỉ render nếu là request mới nhất.
var mediaPickerRequestSeq = 0;

// kind: 'gifs' hoặc 'stickers' -- 2 phần tử DOM tách riêng để không phải render lại lưới khi đổi qua lại.
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
    // Chặn bubble, cùng lý do với #composeEmojiPicker.
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

// q rỗng -- nạp trending; có q -- tìm theo từ khoá. previewUrl (nhỏ, để hiện lưới) khác sendUrl (ảnh gửi đi) -- không gửi nhầm bản xem trước bé tí.
function loadMediaItems(kind, q) {
    var grid = document.querySelector('#' + mediaPickerElId(kind) + ' .mediaPickerGrid');
    if (!grid) return;
    // Báo rõ thiếu key thay vì âm thầm gọi API chắc chắn lỗi rồi hiện "Lỗi tải" chung chung.
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
    // Field url (fixed_height.url) LUÔN là .gif, kể cả catalog /stickers/ (đã kiểm chứng qua API thật) -- không đoán mime theo kind.
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

// Chèn emoji đúng vị trí con trỏ (không nối cuối chuỗi) rồi bắn lại sự kiện "input" để các listener khác (updateSendButtonState...) tự chạy như gõ tay.
function insertEmojiAtCursor(inputEl, emoji) {
    var start = inputEl.selectionStart != null ? inputEl.selectionStart : inputEl.value.length;
    var end = inputEl.selectionEnd != null ? inputEl.selectionEnd : inputEl.value.length;
    inputEl.value = inputEl.value.slice(0, start) + emoji + inputEl.value.slice(end);
    var newPos = start + emoji.length;
    inputEl.focus();
    inputEl.setSelectionRange(newPos, newPos);
    inputEl.dispatchEvent(new Event('input', {bubbles: true}));
}

// Đọc trước width/height thật của ảnh/video ngay trên máy người gửi (qua File local, không qua mạng) để gắn kèm vào tin, giúp bên nhận chừa đúng chỗ khung hình trước khi ảnh/video tải xong.
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
        // Gắn vào DOM thật (ẩn off-screen, không display:none) -- Safari/WebKit cũ không bắn loadedmetadata cho <video> chưa từng gắn vào DOM.
        el.style.cssText = 'position:fixed; left:-9999px; top:-9999px; width:1px; height:1px; opacity:0; pointer-events:none;';
        document.body.appendChild(el);
        // Timeout an toàn -- file hỏng/codec lạ hiếm khi không bắn onload lẫn onerror, không được treo Promise mãi.
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

// Upload multipart thẳng lên file-server (không qua WS vì file có thể nặng) -- fileMime/fileName/token đi qua query string vì upload.lua đọc content-type qua "?fileMime=" và jad.create_file_path không forward header Authorization.
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

    // Đọc kích thước local song song với upload -- không kéo dài thời gian chờ thực tế vì đằng nào cũng phải đợi upload xong.
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
        // fileId riêng (không chỉ fileUrl) -- cần để tự dựng URL /v2/api/thumbnail cho video.
        var result = {fileUrl: fileUrl, fileId: data.id, fileMime: data.mime || mime, fileName: file.name};
        if (dims) { result.width = dims.width; result.height = dims.height; }
        return result;
    });
}

// Upload tất cả file song song rồi gửi đúng 1 frame MESSAGE với body.files -- nếu 1 file lỗi giữa chừng thì không gửi tin nào cả (tránh thiếu file so với người dùng đã chọn).
function uploadAndSendFiles(conversationId, files, caption, replyTo) {
    Promise.all(files.map(function (file) { return uploadOneFile(conversationId, file); }))
        .then(function (uploaded) {
            var b = {message: caption || '', files: uploaded};
            if (replyTo) b.replyTo = replyTo;
            sendChatMessage(conversationId, b);
        })
        .catch(function (err) {
            appAlert('Gửi file lỗi: ' + err.message, 'Lỗi gửi file');
        });
}

// Gửi ảnh trỏ thẳng tới URL ngoài (GIF/sticker) thay vì upload lại qua file-server -- server không validate domain của fileUrl, width/height gán thẳng để tránh layout nhảy lúc ảnh tải xong.
function sendExternalImageMessage(conversationId, url, mime, width, height, fileName) {
    sendChatMessage(conversationId, {message: '', files: [{fileUrl: url, fileMime: mime, fileName: fileName, width: width, height: height}]});
}

