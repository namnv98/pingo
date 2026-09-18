// .attachMenu là phần tử riêng của từng card (không phải singleton) nhưng vẫn cần biến toàn cục để các popup khác đóng nó khi chúng mở lên, và ngược lại.
var openAttachMenuEl = null;
function closeAttachMenu() {
    if (openAttachMenuEl) openAttachMenuEl.classList.remove('show');
    openAttachMenuEl = null;
}
document.addEventListener('click', closeAttachMenu);

// Popup reaction (Facebook-style, 6 icon cố định trong REACTIONS) khác hẳn popup chèn emoji vào ô nhập (ensureComposeEmojiPicker, bộ Unicode đầy đủ) -- không dùng chung vì mục đích khác nhau.

var pickerOnSelect = null; // callback(emoji) -- gán bởi openReactionPicker
var reactionPickerOnMore = null; // callback() cho nút "+" -- luôn trỏ tới lần open GẦN NHẤT, xem populatePicker
var reactionPickerBuilt = false; // đã dựng DOM 6 icon 1 lần chưa, xem javadoc populatePicker

function ensureReactionPicker() {
    var picker = document.getElementById('reactionPicker');
    if (picker) return picker;
    picker = document.createElement('div');
    picker.id = 'reactionPicker';
    document.body.appendChild(picker);
    return picker;
}

// BUG THẬT của thư viện @lottiefiles/lottie-player (đã tự đo bằng getBBox()/childElementCount, không
// đoán -- repo GITHUB ĐÃ ARCHIVE 20/06/2026, đúng issue #231 "<lottie-player> tự biến mất sau khi chạy 1
// lúc" từ bản 1.4.0 tới 2.0.2 = ĐÚNG BẢN đang tải qua CDN "@2", không có bản vá): huỷ nhiều <lottie-player>
// đang chạy CÙNG LÚC dựng lại nhiều cái mới (chính là picker.innerHTML='' + tạo lại 6 nút mỗi lần mở) làm
// MỌI lottie-player tạo ra SAU thời điểm đó vĩnh viễn render RỖNG (không throw, không lỗi console,
// currentState vẫn báo "playing" bình thường) cho tới khi F5 lại trang -- tự tái hiện 100% qua UI thật:
// mở picker lần 1 luôn ổn, đóng rồi mở lại lần 2 trắng tinh mãi mãi. issue #240 (rò rỉ listener trong
// disconnectedCallback) càng khẳng định thư viện dọn dẹp nội bộ không đáng tin cậy khi bị huỷ/dựng lại
// liên tục -- không phải chuyện sửa được bằng cách đổi thứ tự gọi destroy()/stop() phía code mình (đã tự
// thử, không ăn thua).
//
// Sửa: REACTIONS là 6 icon CỐ ĐỊNH, không bao giờ đổi nội dung -- không có lý do gì phải huỷ+dựng lại DOM
// (và do đó huỷ+dựng lại <lottie-player>) mỗi lần mở popup. Dựng ĐÚNG 1 LẦN cho cả phiên trang, các lần mở
// sau chỉ show/hide lại (xem positionAndShowPicker/closeReactionPicker) -- icon animation cứ chạy liên tục
// ở hậu trường, không bao giờ bị huỷ nên không bao giờ dính bug trên. pickerOnSelect (đã có sẵn, biến toàn
// cục) và reactionPickerOnMore (thêm mới, cùng cơ chế) đảm bảo nút bấm luôn gọi ĐÚNG callback của lần mở
// gần nhất dù DOM không dựng lại.
function populatePicker(picker, onMore) {
    reactionPickerOnMore = onMore;
    if (reactionPickerBuilt) return;
    reactionPickerBuilt = true;
    picker.innerHTML = '';
    REACTIONS.forEach(function (r, idx) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.style.setProperty('--i', idx);
        mountReactionAnim(btn, r.emoji, 28);
        btn.onclick = function (e) {
            e.stopPropagation();
            if (pickerOnSelect) pickerOnSelect(r.emoji);
            closeReactionPicker();
        };
        picker.appendChild(btn);
    });
    // Nút "+" mở bộ emoji ĐẦY ĐỦ (dùng lại chính #composeEmojiPicker/emoji-picker-element, xem
    // openComposeEmojiPicker) để thả BẤT KỲ emoji nào làm reaction, không giới hạn 6 icon cố định kiểu
    // Facebook ở trên -- đúng ý "ấn vào load thêm reaction".
    var moreBtn = document.createElement('button');
    moreBtn.type = 'button';
    moreBtn.className = 'reactionPickerMore';
    moreBtn.style.setProperty('--i', REACTIONS.length);
    moreBtn.title = 'Thêm reaction khác';
    moreBtn.innerHTML = '<i class="fa-solid fa-plus"></i>';
    moreBtn.onclick = function (e) { e.stopPropagation(); if (reactionPickerOnMore) reactionPickerOnMore(); };
    picker.appendChild(moreBtn);
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
    populatePicker(ensureReactionPicker(), function () {
        closeReactionPicker();
        openReactionMorePicker(triggerEl, function (emoji) { sendReaction(conversationId, messageId, emoji); });
    });
    positionAndShowPicker(triggerEl);
}

// ===== Popup "+" -- reaction ĐỘNG ngoài 6 icon cố định, tìm theo tên, lazy-load animation =====
// Không dùng emoji-picker-element ở đây (khác hẳn ô chèn emoji vào text, xem openComposeEmojiPicker):
// nó chỉ render glyph tĩnh theo font hệ điều hành, không có chỗ nào để nhét icon động vào -- phải tự vẽ
// lưới riêng. ~1900 emoji mà bật <lottie-player> (rAF thật) CÙNG LÚC cho tất cả chắc chắn giật (đã tự
// cân nhắc, không phải đoán) -- IntersectionObserver dưới đây CHỈ dựng lottie-player cho ô đang thật sự
// nằm trong khung nhìn của lưới cuộn, ô rời khung nhìn tự huỷ về lại glyph Unicode tĩnh (rẻ) -- đúng
// nghĩa "lazy-load" cho animation, không phải lazy-load DOM (DOM 1900 nút vẫn dựng hết 1 lần, nhẹ hơn
// animation rất nhiều).
var reactionMoreData = null; // [{emoji, name, group}] -- nạp 1 lần, cache lại (fetch lại thì query DevTools thấy request mới ngay nếu có gì sai)
var reactionMoreTarget = null; // callback(emoji) -- gán bởi openReactionMorePicker
var reactionMoreObserver = null;
// Đang gõ tìm kiếm thì null (xem input handler dưới) -- tab đang chọn để tô sáng + nhớ lại lúc mở popup lần sau.
var reactionMoreActiveGroup = null;
// "group" y hệt field "group" trong unicode-emoji-json (9 nhóm chuẩn Unicode CLDR, xem ensureReactionMoreData)
// -- icon đại diện dùng luôn glyph Unicode tĩnh (không animate, chỉ để nhận diện nhóm, đỡ tốn tải/dựng
// <lottie-player> cho có 9 nút). Nhãn tiếng Việt hiện qua title (hover) vì khung popup 352px không đủ chỗ
// cho 9 tab có chữ.
var REACTION_MORE_GROUPS = [
    {group: 'Smileys & Emotion', icon: '😀', label: 'Mặt cười & Cảm xúc'},
    {group: 'People & Body', icon: '🙌', label: 'Người & Cơ thể'},
    {group: 'Animals & Nature', icon: '🐶', label: 'Động vật & Thiên nhiên'},
    {group: 'Food & Drink', icon: '🍔', label: 'Đồ ăn & Thức uống'},
    {group: 'Travel & Places', icon: '✈️', label: 'Du lịch & Địa điểm'},
    {group: 'Activities', icon: '⚽', label: 'Hoạt động'},
    {group: 'Objects', icon: '💡', label: 'Đồ vật'},
    {group: 'Symbols', icon: '❤️', label: 'Ký hiệu'},
    {group: 'Flags', icon: '🏳️', label: 'Cờ'}
];

function ensureReactionMoreData() {
    if (reactionMoreData) return Promise.resolve(reactionMoreData);
    // unicode-emoji-json (MIT, ~1900 emoji gốc + tên tiếng Anh) -- đủ cho "tìm theo tên", không cần tự
    // vẽ/host lại font-data khổng lồ mà emoji-picker-element tự lo riêng cho việc CHÈN CHỮ. "group" (field
    // có sẵn trong data, đúng 9 nhóm CLDR chuẩn ở REACTION_MORE_GROUPS) dùng để chia tab.
    // fetchJsonWithPersistentCache (state.js) -- cache lại qua Cache Storage, sống sót qua F5, không phải
    // tải lại ~1900 dòng JSON mỗi lần mở lại app dù CDN đã có Cache-Control riêng.
    return fetchJsonWithPersistentCache('https://cdn.jsdelivr.net/npm/unicode-emoji-json/data-by-emoji.json')
        .then(function (obj) {
            reactionMoreData = Object.keys(obj).map(function (emoji) {
                return {emoji: emoji, name: obj[emoji].name, group: obj[emoji].group};
            });
            return reactionMoreData;
        });
}

function filterReactionMoreData(q) {
    if (!reactionMoreData) return [];
    if (!q) return reactionMoreData;
    var qLower = q.toLowerCase();
    return reactionMoreData.filter(function (e) { return e.name.indexOf(qLower) !== -1; });
}

function filterReactionMoreDataByGroup(group) {
    if (!reactionMoreData) return [];
    return reactionMoreData.filter(function (e) { return e.group === group; });
}

// Vẽ lại hàng tab, tô sáng đúng nhóm đang chọn -- activeGroup=null nghĩa là đang ở chế độ tìm kiếm (không
// tab nào được tô, xem input handler trong ensureReactionMorePicker).
function renderReactionMoreTabs(activeGroup) {
    var tabs = document.querySelector('#reactionMorePicker .reactionMoreTabs');
    if (!tabs) return;
    tabs.innerHTML = '';
    REACTION_MORE_GROUPS.forEach(function (g) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'reactionMoreTab' + (g.group === activeGroup ? ' active' : '');
        btn.title = g.label;
        btn.innerText = g.icon;
        btn.onclick = function (e) {
            e.stopPropagation();
            reactionMoreActiveGroup = g.group;
            var search = document.querySelector('#reactionMorePicker .mediaPickerSearch');
            if (search) search.value = '';
            renderReactionMoreTabs(g.group);
            renderReactionMoreGrid(filterReactionMoreDataByGroup(g.group));
        };
        tabs.appendChild(btn);
    });
}

function renderReactionMoreGrid(list) {
    var grid = document.querySelector('#reactionMorePicker .reactionMoreGrid');
    if (!grid) return;
    if (reactionMoreObserver) reactionMoreObserver.disconnect();
    grid.innerHTML = '';
    if (!list.length) {
        grid.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Không tìm thấy reaction nào.</div>';
        return;
    }
    reactionMoreObserver = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
            var btn = entry.target;
            var emoji = btn.dataset.emoji;
            if (entry.isIntersecting) {
                if (btn.querySelector('lottie-player')) return; // đã dựng sẵn (hiếm khi observer bắn lại lúc chưa kịp huỷ)
                // mountReactionAnim (state.js) -- cache theo URL nên cùng 1 emoji cuộn ra/vào NHIỀU LẦN
                // (hoặc gõ tìm kiếm làm dựng lại cả lưới) chỉ tải+parse JSON THẬT SỰ 1 LẦN DUY NHẤT, các
                // lần sau chỉ dựng lại <lottie-player> từ dữ liệu đã có sẵn trong bộ nhớ. Kích thước LUÔN
                // cố định 26px, không tự bù to/nhỏ theo nội dung vẽ bên trong (xem javadoc mountReactionAnim).
                // autoplay=false -- BUG THẬT đã tự thấy: cả lưới cùng lúc có thể tới ~40-90 icon trong khung
                // nhìn (rootMargin 100px), mỗi icon tự chạy 1 vòng lặp rAF SONG SONG -> giật/lag rõ lúc cuộn.
                // Chỉ play() thật sự lúc hover (xem addEventListener mouseenter/mouseleave dưới), đứng yên ở
                // khung hình đầu lúc còn lại -- tại 1 thời điểm hiếm khi có quá 1-2 icon đang chạy animation.
                mountReactionAnim(btn, emoji, 26, function () { btn.innerHTML = '<span class="static">' + emoji + '</span>'; }, false);
            } else {
                btn.innerHTML = '<span class="static">' + emoji + '</span>'; // rời khung nhìn -- huỷ hẳn lottie-player (dừng rAF), không chỉ ẩn
            }
        });
    }, {root: grid, rootMargin: '100px 0px'});
    list.forEach(function (e) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'reactionMoreItem';
        btn.title = e.name;
        btn.dataset.emoji = e.emoji;
        btn.innerHTML = '<span class="static">' + e.emoji + '</span>';
        btn.onclick = function (ev) {
            ev.stopPropagation();
            if (reactionMoreTarget) reactionMoreTarget(e.emoji);
            closeReactionMorePicker();
        };
        // Gắn 1 LẦN DUY NHẤT lúc tạo nút (không phải mỗi lần mount/huỷ animation) -- chỉ có tác dụng khi
        // đang thật sự nằm trong khung nhìn (có <lottie-player>, xem mountReactionAnim autoplay=false ở
        // trên); rời khung nhìn thì nút đã bị thay bằng glyph tĩnh nên querySelector dưới tự trả về null,
        // hover lúc đó vô hại (không có gì để play/stop).
        btn.addEventListener('mouseenter', function () {
            var lp = btn.querySelector('lottie-player');
            if (lp) lp.play();
        });
        btn.addEventListener('mouseleave', function () {
            var lp = btn.querySelector('lottie-player');
            if (lp) lp.stop(); // stop() (không phải pause()) -- về lại khung hình đầu, hover lần sau luôn xem từ đầu, đồng nhất
        });
        grid.appendChild(btn);
        reactionMoreObserver.observe(btn);
    });
}

function ensureReactionMorePicker() {
    var picker = document.getElementById('reactionMorePicker');
    if (picker) return picker;
    picker = document.createElement('div');
    picker.id = 'reactionMorePicker';
    picker.className = 'mediaPicker'; // dùng chung khung/kích thước với #gifPicker/#stickerPicker, xem CSS
    picker.innerHTML =
        '<input type="text" class="mediaPickerSearch" placeholder="Tìm reaction theo tên...">' +
        '<div class="reactionMoreTabs"></div>' +
        '<div class="reactionMoreGrid"></div>';
    picker.addEventListener('click', function (e) { e.stopPropagation(); });
    // Lọc cục bộ trong mảng đã tải sẵn (không phải gọi API như Giphy) -- không cần debounce, tính tức thời với ~1900 phần tử.
    // Gõ tìm kiếm thì BỎ QUA tab đang chọn, tìm trên toàn bộ (giống Slack/Discord/Messenger) -- xoá ô tìm
    // kiếm (vd bấm lại 1 tab) mới quay về đúng nhóm đang nhớ.
    picker.querySelector('.mediaPickerSearch').addEventListener('input', function (e) {
        var q = e.target.value.trim();
        if (q) {
            renderReactionMoreTabs(null);
            renderReactionMoreGrid(filterReactionMoreData(q));
        } else {
            var g = reactionMoreActiveGroup || REACTION_MORE_GROUPS[0].group;
            renderReactionMoreTabs(g);
            renderReactionMoreGrid(filterReactionMoreDataByGroup(g));
        }
    });
    document.body.appendChild(picker);
    return picker;
}

function openReactionMorePicker(triggerEl, onSelect) {
    closeReactionPicker();
    closeComposeEmojiPicker();
    closeGifPicker();
    closeStickerPicker();
    closeAttachMenu();
    reactionMoreTarget = onSelect;
    var picker = ensureReactionMorePicker();
    picker.classList.add('show');
    positionComposeEmojiPicker(triggerEl, picker); // dùng chung logic định vị với #composeEmojiPicker/#gifPicker
    picker.querySelector('.mediaPickerSearch').value = '';
    var grid = picker.querySelector('.reactionMoreGrid');
    grid.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Đang tải...</div>';
    ensureReactionMoreData().then(function () {
        // Bấm đóng popup trong lúc đang tải (kịp closeReactionMorePicker về null) thì thôi, không render lên popup đã đóng.
        if (!reactionMoreTarget) return;
        // Nhớ lại tab lần mở gần nhất (mặc định nhóm đầu tiên) thay vì luôn show cả 1914 emoji.
        var g = reactionMoreActiveGroup || REACTION_MORE_GROUPS[0].group;
        reactionMoreActiveGroup = g;
        renderReactionMoreTabs(g);
        renderReactionMoreGrid(filterReactionMoreDataByGroup(g));
    }).catch(function (err) {
        var grid2 = document.querySelector('#reactionMorePicker .reactionMoreGrid');
        if (grid2) grid2.innerHTML = '<div class="mediaPickerStatus" style="grid-column:1/-1">Lỗi tải: ' + err.message + '</div>';
    });
}

function closeReactionMorePicker() {
    var picker = document.getElementById('reactionMorePicker');
    if (picker) picker.classList.remove('show');
    if (reactionMoreObserver) { reactionMoreObserver.disconnect(); reactionMoreObserver = null; }
    reactionMoreTarget = null;
}
document.addEventListener('click', closeReactionMorePicker);

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
    closeReactionMorePicker();
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

// onSelect(emoji): callback -- hiện chỉ dùng để chèn emoji vào ô nhập (xem messaging-core.js). Popup
// reaction "+" KHÔNG dùng cái này nữa (xem openReactionMorePicker) vì emoji-picker-element chỉ render
// glyph tĩnh, không có chỗ nhét icon động.
function openComposeEmojiPicker(triggerEl, onSelect) {
    closeReactionPicker(); // các popup emoji/sticker/GIF/attach không nên cùng mở 1 lúc
    closeReactionMorePicker();
    closeGifPicker();
    closeStickerPicker();
    closeAttachMenu();
    var picker = ensureComposeEmojiPicker();
    composeEmojiPickerTarget = onSelect;
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
    closeReactionMorePicker();
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

