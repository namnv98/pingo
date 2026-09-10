function fetchJson(path, withAuth) {
    var opts = withAuth ? {headers: {'Authorization': 'Bearer ' + authToken}} : undefined;
    return fetch(HISTORY_API_BASE + path, opts).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    });
}

// ===== Quả chuông thông báo (kiểu Microsoft Teams) =====
// LƯU THẬT trong Postgres (bảng notifications, xem NotificationRegistry) -- colony tự tạo dòng
// "mention"/"reply"/"reaction" LUÔN, không điều kiện online/offline (xem ChatSessionManager
// #notifyReplyAndMentions/handleReaction bên colony). Client ở đây CHỈ đọc lại + đánh dấu đã đọc qua
// API thật của herald (GET/PUT /notifications) -- KHÔNG tự dựng object thông báo từ WS frame nữa
// (bản trước làm vậy: mất khi F5, và không có id DB thật nên "đánh dấu đã đọc" không lưu lại được).
// Bù việc phải chờ HTTP round-trip, badge số đếm vẫn TĂNG NGAY khi 1 frame sống bay tới có vẻ liên
// quan tới mình (xem maybeBumpNotifBadge) -- nội dung CHI TIẾT/chuẩn thì đồng bộ lại thật khi mở popup.
var notifications = []; // cache hiển thị gần nhất từ GET /notifications, mới nhất trước
var notifUnreadCount = 0;

function updateNotifBadge() {
    var badgeEl = document.getElementById('notifBadge');
    if (!badgeEl) return;
    badgeEl.innerText = notifUnreadCount > 9 ? '9+' : String(notifUnreadCount);
    badgeEl.style.display = notifUnreadCount > 0 ? 'flex' : 'none';
}

function notifIconFor(type) {
    if (type === 'reaction') return '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" y1="9" x2="9.01" y2="9"/><line x1="15" y1="9" x2="15.01" y2="9"/></svg>';
    if (type === 'reply') return '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 17 4 12 9 7"/><path d="M20 18v-2a4 4 0 0 0-4-4H4"/></svg>';
    return '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 4h16v12H8l-4 4V4z"/><line x1="8" y1="9" x2="8.01" y2="9"/><line x1="12" y1="9" x2="12.01" y2="9"/><line x1="16" y1="9" x2="16.01" y2="9"/></svg>'; // 'mention' và 'message' (noti "tin nhắn mới" cũ) dùng chung icon bong bóng chat
}

function notifText(n) {
    var name = '<b>' + displayName(n.fromUserId) + '</b>';
    if (n.type === 'reaction') return name + ' đã bày tỏ cảm xúc ' + (n.bodyPreview || '') + ' với tin nhắn của bạn';
    if (n.type === 'reply') return name + ' đã trả lời tin nhắn của bạn: ' + (n.bodyPreview || '');
    if (n.type === 'mention') return name + ' đã nhắc đến bạn: ' + (n.bodyPreview || '');
    return name + ' đã gửi cho bạn 1 tin nhắn mới'; // type 'message' -- noti "bỏ lỡ tin nhắn" cũ (xem NotificationConsumer bên herald)
}

function renderNotifMenu() {
    var listEl = document.getElementById('notifMenuList');
    var emptyEl = document.getElementById('notifMenuEmpty');
    if (!listEl) return;
    listEl.innerHTML = '';
    emptyEl.style.display = notifications.length ? 'none' : 'block';
    notifications.forEach(function (n) {
        var item = document.createElement('div');
        item.className = 'notifItem' + (n.read ? '' : ' unread');
        item.innerHTML =
            '<span class="notifItemIcon ' + n.type + '">' + notifIconFor(n.type) + '</span>' +
            '<div class="notifItemBody"><div class="notifItemText"></div><div class="notifItemTime"></div></div>';
        item.querySelector('.notifItemText').innerHTML = notifText(n);
        item.querySelector('.notifItemTime').innerText = relativeTime(n.ts);
        item.onclick = function () {
            closeNotifMenu();
            // openConversation() (KHÔNG phải selectConversation() trần) -- conversation trong noti có
            // thể CHƯA TỪNG được mở trong phiên này (đúng lý do noti tồn tại: server tự lưu bất kể
            // client có đang xem hay không, xem ChatSessionManager#notifyReplyAndMentions bên colony),
            // nên chưa có card nào trong conversations{} cả -- selectConversation/jumpToMessage đều
            // âm thầm return ngay dòng đầu nếu thiếu card (bug thật đã gặp: bấm noti không mở gì cả).
            // openConversation tự ensureConversationCard trước, cùng đường đúng như bấm 1 mục trong
            // sidebar (xem buildConvListItem) -- label/subtitle tính lại từ lastConvList nếu có sẵn.
            var conv = lastConvList.filter(function (c) { return c.conversationId === n.conversationId; })[0];
            openConversation(n.conversationId, conv && conversationLabel(conv), conv && membersSubtitle(conv));
            if (isNarrowViewport()) document.getElementById('layout').classList.add('mobileChatOpen');
            // messageTs (giờ tin GỐC thật sự được gửi) chứ KHÔNG phải n.ts (giờ SỰ KIỆN xảy ra --
            // với reaction có thể lệch xa: react vào 1 tin rất cũ) -- dùng nhầm n.ts làm mốc "seek"
            // khi tin chưa có sẵn trong khung chat sẽ tìm sai hẳn quanh "bây giờ" thay vì quanh lúc
            // tin gốc, luôn báo "không tìm thấy tin gốc" dù tin còn nguyên (bug thật đã gặp). Dữ liệu
            // noti cũ (trước khi có cột message_ts) không có field này -- rơi về n.ts, tốt hơn null.
            if (n.messageId) jumpToMessage(n.conversationId, n.messageId, n.messageTs != null ? n.messageTs : n.ts);
        };
        listEl.appendChild(item);
    });
}

// GET /notifications thật (herald) -- gọi lúc vào app (enterApp) + mỗi lần mở popup, đủ mới cho 1
// demo (không cần polling/WS riêng cho việc này).
function loadNotifications() {
    return fetch(HERALD_API_BASE + '/notifications?limit=30', {headers: {'Authorization': 'Bearer ' + authToken}})
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (list) {
            notifications = list;
            notifUnreadCount = list.filter(function (n) { return !n.read; }).length;
            renderNotifMenu();
            updateNotifBadge();
        })
        .catch(function (err) {
            console.warn('không load được thông báo', err);
        });
}

function toggleNotifMenu(e) {
    e.stopPropagation();
    var menu = document.getElementById('notifMenu');
    var opening = !menu.classList.contains('show');
    menu.classList.toggle('show');
    if (!opening) return;
    // Đồng bộ lại DANH SÁCH THẬT trước (không dùng cache cũ) -- những gì maybeBumpNotifBadge mới
    // tăng tạm ở badge đã kịp có dòng DB thật ở đây rồi (colony ghi ngay lúc persist tin/reaction).
    loadNotifications().then(function () {
        var unread = notifications.filter(function (n) { return !n.read; });
        if (!unread.length) return;
        // Mở ra là coi như đã xem hết -- giống chuông Teams/Messenger, không cần bấm từng cái. Đánh
        // dấu THẬT qua PUT (mỗi noti 1 lần gọi -- herald chưa có endpoint đánh dấu hàng loạt, chấp
        // nhận được vì tối đa 30 dòng/lần mở).
        unread.forEach(function (n) {
            n.read = true;
            fetch(HERALD_API_BASE + '/notifications?id=' + encodeURIComponent(n.id), {method: 'PUT', headers: {'Authorization': 'Bearer ' + authToken}})
                .catch(function (err) { console.warn('không đánh dấu đã đọc được noti', n.id, err); });
        });
        notifUnreadCount = 0;
        renderNotifMenu();
        updateNotifBadge();
    });
}
function closeNotifMenu() {
    document.getElementById('notifMenu').classList.remove('show');
}
document.getElementById('notifBtn').innerHTML =
    '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>';
document.getElementById('notifBtn').onclick = toggleNotifMenu;
document.addEventListener('click', closeNotifMenu);
document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeNotifMenu(); });
renderNotifMenu();

// Đồng bộ lại badge NGAY khi có tín hiệu 1 MESSAGE/REACTION từ NGƯỜI KHÁC có thể vừa sinh ra thông
// báo cho mình -- hỏi THẲNG server (loadNotifications, nguồn dữ liệu thật) thay vì tự đoán ở client
// (bản trước đoán bằng cách "tin này của mình" qua 1 Set client-side (myMessageIds) hoặc regex khớp
// "@username" -- đoán SAI/THIẾU đúng lúc quan trọng nhất: reaction/mention vào tin ở 1 conversation
// CHƯA TỪNG mở trong phiên này thì client không hề biết gì về tin đó để mà đoán, badge im re dù DB
// đã có dòng thật -- bug thật đã gặp 2 lần). Debounce nhẹ (không hỏi lại nếu đã có 1 lượt đang chờ)
// vì server LUÔN biết chính xác, gọi dồn nhiều tin/reaction liên tiếp về vẫn chỉ cần 1 lượt hỏi cuối.
var notifRefreshTimer = null;
function refreshNotifBadgeSoon() {
    if (notifRefreshTimer) return;
    notifRefreshTimer = setTimeout(function () {
        notifRefreshTimer = null;
        loadNotifications();
    }, 500);
}

// Cỡ 1 trang -- dùng CHUNG cho cả 2 hướng phân trang: "cuộn lên xem tin cũ hơn" (before, xem
// maybeLoadOlder) VÀ "đoạn chưa đọc, cuộn xuống xem tiếp" (after, xem maybeLoadNewer). Cố tình
// KHÔNG nạp hết toàn bộ đoạn chưa đọc trong 1 lần mở lại dù có bao nhiêu tin chưa đọc đi nữa -- đúng
// yêu cầu "lazy load", tin chưa đọc cũng phải nạp dần theo từng trang khi cuộn tới, không phải 1 cục
// to ngay lúc mở (trước đây từng nạp hẳn 100 tin chưa đọc 1 lần, coi như "hết" chỉ là cái trần cao
// hơn -- không đúng tinh thần lazy load nếu thật sự có nhiều tin chưa đọc).
var HISTORY_PAGE_SIZE = 30;

// Load lịch sử tin nhắn (GET /messages trên colony, không phải harbor — xem MessageHistoryRegistry)
// ngay sau khi SUBSCRIBE_OK, tránh gọi lặp nếu SUBSCRIBE_OK lỡ tới nhiều lần cho cùng 1 conversation.
// KHÔNG còn nạp trắng "N tin mới nhất" như trước -- mở lại 1 conversation ĐàẤ từng đọc sẽ cuộn tới
// ĐÚNG chỗ lần trước dừng lại (con trỏ đã đọc, xem GET /read-cursor), không phải luôn nhảy xuống
// cuối -- đúng yêu cầu "khi vào thì ở tin đã đọc lần cuối chứ không phải mới nhất".
// Trả về Promise resolve khi đã NẠP + VẼ XONG HẲN trang mặc định (không chỉ "đã bắt đầu gọi") --
// cache lại trên entry.historyLoadPromise để gọi lặp (vd jumpToMessage gọi lại ngay sau
// selectConversation cũng vừa gọi) chỉ ĐỢI CHUNG 1 lần fetch, không bắn thêm request trùng.
function loadHistory(conversationId) {
    var entry = ensureConversationCard(conversationId, 'Conversation');
    if (entry.historyLoadPromise) return entry.historyLoadPromise;
    entry.historyLoaded = true;

    entry.historyLoadPromise = fetchJson('/read-cursor?conversationId=' + encodeURIComponent(conversationId), true)
        .then(function (res) {
            var cursor = res && res.data;
            // Chưa từng đọc tin nào trong conversation này (mới toanh, hoặc mở lần đầu) -- nạp trang
            // mới nhất, neo đáy, giống hành vi cũ.
            return cursor ? loadAroundReadCursor(conversationId, cursor) : loadLatestPage(conversationId);
        })
        .catch(function (err) {
            console.warn('không load được lịch sử cho ' + conversationId, err);
            logToConversation(conversationId, '(không load được lịch sử: ' + err.message + ')');
            // Lỡ hỏng giữa chừng (vd mất mạng ngay lúc đang nạp đoạn "before") vẫn phải TRẢ VỀ true
            // cho những cờ tạm này -- nếu không, mọi tin SỐNG tới sau đó qua WS (không liên quan gì
            // tới lần load lỗi này nữa) sẽ mãi mãi không cuộn/không gửi READ được, vì các cờ đang kẹt
            // ở trạng thái "giữa batch" từ lần load hỏng.
            entry.suppressAutoScroll = false;
            entry.skipReadTracking = false;
        });
    return entry.historyLoadPromise;
}

// Áp lại vị trí cuộn "đúng lẽ ra phải ở đâu" (đáy, hoặc vạch "Tin nhắn mới") -- TÁCH RIÊNG khỏi lúc
// tính ra nó (loadLatestPage/loadAroundReadCursor) vì card có thể đang display:none (conversation
// chưa từng được mở, chỉ mới subscribe ngầm -- xem CSS ".conv") lúc lịch sử tải xong: mọi phép đo
// hình học (scrollHeight, offsetTop) đều tính ra 0 trên 1 phần tử không có layout, gán scrollTop lúc
// đó là vô nghĩa. entry.scrollAnchor lưu lại Ý ĐỊNH, gọi lại đúng hàm này lần đầu conversation THẬT
// SỰ hiện ra (xem selectConversation) để áp dụng lại với layout thật.
function applyScrollAnchor(entry) {
    if (!entry.scrollAnchor) return;
    // CẢ 2 loại anchor đều cuộn thẳng xuống ĐÁY THẬT (scrollHeight) -- vạch "Tin nhắn mới" (type
    // 'divider') vẫn chèn vào DOM để ĐÁNH DẤU ranh giới đã đọc/chưa đọc (context), nhưng KHÔNG còn cố
    // đẩy nó ra sát đáy khung nhìn để giấu hẳn đoạn chưa đọc xuống dưới màn hình nữa (bản trước cố ý
    // làm vậy -- xem lịch sử sửa đổi -- nhưng hoá ra phản trực giác: tin ĐÃ đọc từ lâu chiếm hết màn
    // hình, tin MỚI/chưa đọc lại biến mất hẳn phải tự cuộn tay mới thấy). Giờ tin mới nhất (dù đã đọc
    // hay chưa) luôn nằm trong tầm nhìn ngay khi mở, giống mọi app chat khác -- vạch chỉ còn tác dụng
    // NGỮ CẢNH (biết đâu là ranh giới), không còn quyết định vị trí cuộn.
    entry.logEl.scrollTop = entry.logEl.scrollHeight;
}

function loadLatestPage(conversationId) {
    var entry = conversations[conversationId];
    return fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + HISTORY_PAGE_SIZE)
        .then(function (messages) {
            entry.hasMoreOlder = messages.length === HISTORY_PAGE_SIZE;
            entry.suppressAutoScroll = true;
            // API trả mới nhất trước (ORDER BY created_at DESC) — đảo lại để hiển thị đúng thứ tự thời gian.
            messages.slice().reverse().forEach(function (m) {
                appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
            });
            entry.suppressAutoScroll = false;
            entry.stickToBottom = true;
            entry.scrollAnchor = {type: 'bottom'};
            applyScrollAnchor(entry);
        });
}

// Nạp 2 đoạn quanh con trỏ đã đọc: (1) 1 trang NGAY TRƯỚC/tại con trỏ (context, chắc chắn đã đọc rồi
// -- entry.skipReadTracking=true, không cần quan sát lại), rồi (2) CHỈ 1 TRANG ĐẦU của phần CHƯA đọc
// từ ngay sau con trỏ (cùng cỡ HISTORY_PAGE_SIZE -- KHÔNG nạp hết toàn bộ đoạn chưa đọc dù có bao
// nhiêu tin đi nữa, đúng tinh thần lazy load), phần còn lại nạp dần lúc cuộn xuống gần đáy (xem
// maybeLoadNewer). Chèn 1 vạch "Tin nhắn mới" ngay trước tin chưa đọc đầu tiên, rồi cuộn sao cho vạch
// đó nằm sát đáy khung nhìn -- đúng chỗ người dùng dừng lại lần trước, không phải tin mới nhất.
function loadAroundReadCursor(conversationId, cursor) {
    var entry = conversations[conversationId];
    entry.suppressAutoScroll = true;
    entry.skipReadTracking = true;
    // Khởi tạo số "chưa đọc" từ ĐÚNG con số server đã COUNT bằng SQL (cursor.unreadCount) -- không
    // đợi lazy-load nạp xong mới biết, và không bị chặn ở cỡ 1 trang lazy-load như trước đây (xem
    // updateJumpBadge). Các batch append bên dưới (afterMessages, cả batch sau qua maybeLoadNewer)
    // sẽ KHÔNG cộng thêm nữa vì đang suppressAutoScroll=true (xem appendMessageBubble).
    entry.totalUnreadCount = cursor.unreadCount || 0;
    return fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + HISTORY_PAGE_SIZE + '&before=' + (cursor.lastReadTs + 1))
        .then(function (beforeMessages) {
            entry.hasMoreOlder = beforeMessages.length === HISTORY_PAGE_SIZE;
            beforeMessages.slice().reverse().forEach(function (m) {
                appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
            });
            entry.skipReadTracking = false;
            return fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + HISTORY_PAGE_SIZE + '&after=' + cursor.lastReadTs);
        })
        .then(function (afterMessages) {
            entry.hasMoreNewer = afterMessages.length === HISTORY_PAGE_SIZE;
            var dividerEl = null;
            if (afterMessages.length) {
                dividerEl = document.createElement('div');
                dividerEl.className = 'unreadDivider';
                dividerEl.innerText = 'Tin nhắn mới';
                entry.logEl.appendChild(dividerEl);
            }
            // after trả TĂNG DẦN sẵn (ORDER BY created_at ASC) -- không cần đảo lại như before/latest.
            afterMessages.forEach(function (m) {
                appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
            });
            entry.suppressAutoScroll = false;
            if (dividerEl) {
                entry.stickToBottom = false;
                entry.scrollAnchor = {type: 'divider', el: dividerEl};
                // Hiện nút "xuống cuối" + chấm đỏ NGAY -- entry.totalUnreadCount đã được set từ
                // cursor.unreadCount ở đầu hàm, updateJumpBadge() ở đây chỉ cần vẽ lại cho khớp.
                updateJumpBadge(entry);
                if (entry.jumpBtnEl) entry.jumpBtnEl.classList.add('show');
            } else {
                // Không có tin nào chưa đọc -- đã bắt kịp hết, neo đáy như bình thường.
                entry.stickToBottom = true;
                entry.scrollAnchor = {type: 'bottom'};
            }
            applyScrollAnchor(entry);
        });
}

// Cuộn gần tới ĐỈNH log -- tải thêm 1 trang tin CŨ hơn nữa (phân trang lùi dần, "lazy load" thật sự:
// không nạp cả lịch sử conversation cùng lúc). Chèn LÊN ĐẦU log, giữ nguyên đúng vị trí đang xem
// (xem prependOlderMessages) -- không giật màn hình lên/xuống lúc đang nạp.
function maybeLoadOlder(conversationId) {
    var entry = conversations[conversationId];
    if (!entry || entry.loadingOlder || !entry.hasMoreOlder || entry.oldestLoadedTs == null) return;
    if (entry.logEl.scrollTop > 80) return;
    entry.loadingOlder = true;
    fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + HISTORY_PAGE_SIZE + '&before=' + entry.oldestLoadedTs)
        .then(function (messages) {
            entry.hasMoreOlder = messages.length === HISTORY_PAGE_SIZE;
            if (messages.length) prependOlderMessages(conversationId, messages);
        })
        .catch(function (err) { console.warn('không tải thêm được tin cũ hơn: ' + err.message); })
        .finally(function () { entry.loadingOlder = false; });
}

// Cuộn gần tới ĐÁY log lúc vẫn còn phần "chưa đọc" chưa kịp nạp hết lúc mở conversation (đoạn sau
// con trỏ còn nhiều hơn 1 trang, xem loadAroundReadCursor) -- nạp tiếp XUÔI theo thời gian, TỪNG
// TRANG một (đúng tinh thần lazy load, không nạp hết 1 lần), append bình thường vào cuối (giống hệt
// tin sống tới qua WS, kể cả việc tự cuộn theo/gắn quan sát đọc -- xem appendMessageBubble).
function maybeLoadNewer(conversationId) {
    var entry = conversations[conversationId];
    if (!entry || entry.loadingNewer || !entry.hasMoreNewer || entry.newestLoadedTs == null) return;
    entry.loadingNewer = true;
    fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + HISTORY_PAGE_SIZE + '&after=' + entry.newestLoadedTs)
        .then(function (messages) {
            entry.hasMoreNewer = messages.length === HISTORY_PAGE_SIZE;
            // suppressAutoScroll: nạp thêm (dù chiều nào, lên hay xuống) là hành động NGẦM, KHÔNG
            // được phép đụng tới vị trí đang xem -- kể cả khi entry.stickToBottom đang true (chỉ vì
            // trước đó cuộn gần chạm đáy của những gì ĐÃ CÓ, không có nghĩa là "cứ có thêm thì tự theo
            // xuống"). Khác appendMessageBubble bình thường (dùng cho tin SỐNG thật sự mới tới qua WS
            // lúc đang neo đáy -- đó vẫn tự cuộn theo, hợp lý vì đúng nghĩa "đang xem trực tiếp").
            entry.suppressAutoScroll = true;
            messages.forEach(function (m) {
                appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
            });
            entry.suppressAutoScroll = false;
            // Sau khi âm thầm nối thêm vào cuối, phần "đã xem" trước đó KHÔNG còn thật sự là đáy nữa
            // (nội dung mới nằm dưới, ngoài màn hình) -- tính lại đúng thực tế thay vì giữ nguyên cờ
            // cũ, để tin SỐNG kế tiếp không bị hiểu lầm "đang neo đáy" mà tự động kéo màn hình xuống.
            entry.stickToBottom = isNearBottom(entry.logEl);
            // entry.totalUnreadCount KHÔNG đổi ở batch này (suppressAutoScroll=true suốt forEach ở
            // trên -- đúng ý: đây là phần vốn đã nằm trong con số server đếm lúc mở lại, xem
            // loadAroundReadCursor, không phải tin mới phát sinh) -- chỉ cần vẽ lại badge cho chắc.
            updateJumpBadge(entry);
            if (!entry.stickToBottom && entry.jumpBtnEl) entry.jumpBtnEl.classList.add('show');
        })
        .catch(function (err) { console.warn('không tải thêm được tin mới hơn: ' + err.message); })
        .finally(function () { entry.loadingNewer = false; });
}

// Chèn 1 trang tin CŨ hơn LÊN ĐẦU log (xem maybeLoadOlder) -- KHÔNG dùng appendMessageBubble (nó luôn
// gắn vào CUỐI log + cập nhật entry.lastMessageMeta/lastGroupRow/lastDividerDateKey đại diện cho DÒNG
// CUỐI CÙNG/mới nhất, sẽ sai hoàn toàn nếu dùng để chèn lên đầu). Gộp nhóm/vạch ngày tính RIÊNG trong
// phạm vi batch này (không nối tiếp trạng thái gộp nhóm của nội dung đã render phía dưới -- chấp nhận
// 1 đường nối không gộp tuyệt đối giữa 2 trang, đổi lại đơn giản hơn nhiều). Tin ở đây LUÔN cũ hơn con
// trỏ đã đọc ban đầu nên chắc chắn đã đọc từ trước -- không cần gắn IntersectionObserver.
function prependOlderMessages(conversationId, messagesDesc) {
    var entry = conversations[conversationId];
    var messages = messagesDesc.slice().reverse(); // cũ nhất trước -- đúng thứ tự chèn lên đầu log
    var frag = document.createDocumentFragment();
    var batchState = {lastGroupRow: null};
    var localDividerKey = null;
    var rowsInfo = [];
    // Theo dõi RIÊNG cho batch này -- ảnh/video trong đoạn vừa chèn LÊN ĐẦU có thể tải xong (đổi từ
    // cao 0px sang cao thật) SAU KHI đã canh lại scrollTop lần đầu (xem cuối hàm) -- lúc đó phải bù
    // THÊM đúng phần chênh lệch mới phát sinh, không thì nội dung đang xem (nằm dưới đoạn vừa chèn)
    // bị đẩy trôi xuống mất, y hệt bug ảnh/video từng gặp ở appendMessageBubble nhưng theo hướng
    // ngược lại (chèn lên đầu, không phải nối vào cuối). Khởi tạo SAU khi chèn xong (xem bên dưới) --
    // gán vào 1 biến let-hoisted ở đây để closure onMediaReady của TỪNG dòng cùng dùng chung.
    var lastKnownScrollHeight;
    messages.forEach(function (m) {
        var mine = m.fromUserId === myUserId;
        var d = new Date(m.ts);
        var dateKey = d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate();
        var dividerInserted = localDividerKey !== dateKey;
        if (dividerInserted) {
            localDividerKey = dateKey;
            var divider = document.createElement('div');
            divider.className = 'dateDivider';
            divider.innerText = formatDateDivider(m.ts);
            frag.appendChild(divider);
        }
        var grouped = !dividerInserted && !!batchState.lastGroupRow && batchState.lastFromUserId === m.fromUserId && (m.ts - batchState.lastTs) < GROUP_WINDOW_MS;
        var onMediaReady = function () {
            var newHeight = entry.logEl.scrollHeight;
            entry.logEl.scrollTop += (newHeight - lastKnownScrollHeight);
            lastKnownScrollHeight = newHeight;
            positionMsgActions(row, mine);
        };
        var row = buildDmBubbleRow(batchState, m.fromUserId, m.body, m.ts, mine, grouped, onMediaReady);
        if (m.id) {
            row.dataset.messageId = m.id;
            row.dataset.conversationId = conversationId;
            row.querySelector('.react-btn').onclick = function (e) {
                e.stopPropagation();
                openReactionPicker(e.currentTarget, conversationId, m.id);
            };
            var pinBtn3 = row.querySelector('.pinBtn');
            if (pinBtn3) (function(mid){ pinBtn3.onclick = function(e){ e.stopPropagation(); openPinMenu(e.currentTarget, conversationId, mid); }; })(m.id);
            var replyBtn3 = row.querySelector('.replyBtn');
            if (replyBtn3) (function(mid, fuid, bdy, ts){ replyBtn3.onclick = function(e){ e.stopPropagation(); var sn=snippetForBody(bdy); var th=replyThumbForBody(bdy); var ent=conversations[conversationId]; if(ent&&ent._setPendingReply){ var rt={messageId:mid, fromUserId:fuid, snippet:sn}; if(th&&th.url){ rt.thumbUrl=th.url; if(th.isVideo) rt.thumbIsVideo=true; } if(ts) rt.ts=ts; ent._setPendingReply(rt); var inp2=ent.el.querySelector('.composeInput'); if(inp2) inp2.focus(); } }; })(m.id, m.fromUserId, m.body, m.ts);
            var deleteBtn = row.querySelector('.msgDeleteBtn');
            if (deleteBtn) deleteBtn.onclick = function (e) {
                e.stopPropagation();
                deleteMessage(conversationId, m.id);
            };
        }
        if (m.body && m.body.replyTo) { var be=row.querySelector('.bubble'); if(be) be.insertBefore(buildReplyQuoteEl(m.body.replyTo, conversationId), be.firstChild); }
        if (mine) updateSeenDisplay(row, !!m.seen);
        if (m.deleted) renderDeletedPlaceholder(row);
        frag.appendChild(row);
        batchState.lastGroupRow = row;
        batchState.lastFromUserId = m.fromUserId;
        batchState.lastTs = m.ts;
        rowsInfo.push({row: row, mine: mine, id: m.id, reactions: m.reactions, deleted: m.deleted});
    });

    // Neo lại đúng vị trí đang xem sau khi chèn thêm nội dung PHÍA TRÊN (không thì scrollTop giữ
    // nguyên số cũ sẽ khiến toàn bộ nội dung "nhảy" xuống dưới đúng bằng chiều cao batch vừa chèn).
    var prevScrollHeight = entry.logEl.scrollHeight;
    entry.logEl.insertBefore(frag, entry.logEl.firstChild);
    lastKnownScrollHeight = entry.logEl.scrollHeight;
    entry.logEl.scrollTop += (lastKnownScrollHeight - prevScrollHeight);

    // Đo/canh nút + vẽ reaction SAU khi cả batch đã thật sự lên DOM (giống lý do trong appendMessageBubble).
    rowsInfo.forEach(function (info) {
        if (!info.id) return;
        positionMsgActions(info.row, info.mine);
        if (info.reactions && info.reactions.length && !info.deleted) {
            reactionsByMessageId[info.id] = {};
            info.reactions.forEach(function (r) { reactionsByMessageId[info.id][r.userId] = r.emoji; });
            renderReactions(info.id, info.row);
        }
    });

    entry.oldestLoadedTs = messages[0].ts; // messages đã đảo -- phần tử đầu là CŨ NHẤT trong trang vừa nạp
}

// Gọi POST /conversations (hall) để tạo conversation MỚI (dùng chung cho cả DM lẫn group, không
// còn suy tất định/lazy-create qua SUBSCRIBE nữa — xem ARCHITECTURE.md mục 12, giống cách
// Slack (conversations.create)/Discord (POST /users/@me/channels) bắt buộc gọi API tạo trước khi
// gửi tin được). Server tự thêm mình vào members, tự publish để harbor wake-subscribe hộ (kể cả
// chính mình) — không cần tự gửi SUBSCRIBE gì cả sau khi tạo xong.
function createConversation(memberUserIds, name) {
    return fetch(HISTORY_API_BASE + '/conversations', {
        method: 'POST',
        headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken},
        body: JSON.stringify({memberUserIds: memberUserIds, name: name || undefined})
    }).then(function (res) {
        return res.json().then(function (data) {
            if (!res.ok) throw new Error(data.error || ('HTTP ' + res.status));
            return data;
        });
    });
}

// Bấm 1 cái tên trong tab "Nhắn 1-1" là gửi đi ngay -- không còn bước nhập/xác nhận riêng, không
// ai cần gõ tay 1 UUID nữa (xem renderUserPickers).
function startDm(peerUserId) {
    createConversation([peerUserId])
        .then(function (data) {
            openConversation(data.conversationId, displayName(peerUserId));
            logToConversation(data.conversationId, 'đã tạo DM với ' + displayName(peerUserId));
            document.getElementById('newConvPanel').open = false;
        })
        .catch(function (err) {
            appAlert('tạo DM lỗi: ' + err.message, 'Lỗi');
        });
}

function startGroup() {
    if (selectedGroupMemberIds.length === 0) {
        appAlert('cần ít nhất 1 thành viên khác', 'Thiếu thành viên');
        return;
    }
    var members = selectedGroupMemberIds.slice();
    var groupNameInput = document.getElementById('groupNameInput');
    var groupName = groupNameInput.value.trim();
    createConversation(members, groupName)
        .then(function (data) {
            var memberNames = joinNamesCapped(members.map(displayName));
            var label = data.name || memberNames;
            var subtitle = members.length > 1 ? memberNames : '';
            openConversation(data.conversationId, label, subtitle);
            logToConversation(data.conversationId, 'đã tạo group, thành viên: ' + memberNames);
            document.getElementById('newConvPanel').open = false;
            groupNameInput.value = '';
            selectedGroupMemberIds = [];
            renderUserPickers();
        })
        .catch(function (err) {
            appAlert('tạo group lỗi: ' + err.message, 'Lỗi');
        });
}

function connect() {
    // Chưa đăng nhập (xem enterApp/showLoginForm) -- không tự mở WebSocket. No-op an toàn cho mọi
    // nơi còn lỡ gọi connect() (vd ws.onclose auto-retry bên dưới) thay vì phải sửa từng nơi gọi.
    if (!identityConfirmed) return;

    // Đang trỏ tới NodePort của harbor trên cụm k3s local (xem harbor/helm/templates/services.yaml).
    // Nếu chạy local bằng "mvn exec:java" thay vì k3s thì đổi lại thành ws://localhost:8888/connect
    ws = new WebSocket("ws://localhost:31003/connect");

    ws.onopen = function () {
        reconnectDelayMs = 1000; // reset backoff mỗi khi nối thành công
        setStatus('đã kết nối, đang xác thực...', 'pending');
        send({type: "AUTH", id: newId(), token: authToken});

        if (pingIntervalId) clearInterval(pingIntervalId);
        pingIntervalId = setInterval(function () {
            if (ws && ws.readyState === WebSocket.OPEN) send({type: "PING", id: newId()});
        }, PING_INTERVAL_MS);
    };

    ws.onclose = function () {
        if (pingIntervalId) { clearInterval(pingIntervalId); pingIntervalId = null; }
        if (!identityConfirmed) return; // dong chu dong vi dang doi dat/xac nhan lai ten -- khong phai mat ket noi that, khong can bao/retry
        setStatus('mất kết nối, thử lại sau ' + (reconnectDelayMs / 1000) + 's...', 'bad');
        setTimeout(connect, reconnectDelayMs);
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_DELAY_MS);
    };

    ws.onmessage = function (event) {
        var frame = JSON.parse(event.data);
        console.log(frame);
        switch (frame.type) {
            case "AUTH_OK":
                // frame.serverId = ten pod harbor dang phuc vu session nay (giong host_id trong
                // frame "hello" cua Slack that, xem app.slack.com.har) -- tien debug khi co nhieu
                // pod harbor, biet ngay dang noi chuyen voi pod nao ma khong can vao k8s check.
                setStatus('đã xác thực', 'ok');
                document.getElementById('podBadge').innerText = frame.serverId ? 'pod ' + frame.serverId : '';
                break;
            case "AUTH_ERROR":
                // Token thiếu/sai/hết hạn -- đăng nhập lại từ đầu thay vì auto-retry vô hạn với 1 token đã hỏng.
                setStatus('auth lỗi: ' + frame.reason, 'bad');
                clearAuth();
                showLoginForm();
                if (ws) ws.close();
                break;
            case "SUBSCRIBE_OK":
                logToConversation(frame.conversationId, '✓ subscribe thành công');
                loadHistory(frame.conversationId);
                refreshConversationList(); // vd vừa tạo conversation mới -- cập nhật ngay vào danh sách
                break;
            case "SUBSCRIBE_ERROR":
                logToConversation(frame.conversationId, '✗ subscribe lỗi: ' + frame.reason);
                break;
            case "ACK":
                countSent++;
                document.getElementById('countSent').innerText = countSent;
                break;
            case "ERROR":
                console.warn('error:', frame.reason);
                break;
            case "MESSAGE":
                countReceived++;
                document.getElementById('countReceived').innerText = countReceived;
                // Tin SỐNG cho 1 conversation CHƯA TỪNG được mở trong phiên này (chưa ai gọi
                // loadHistory -- entry.historyLoadPromise chưa có, kể cả card vừa được tạo ngay bởi
                // CHÍNH tin này) KHÔNG được vẽ tay thẳng vào đây -- nó sẽ nằm sai chỗ: đứng NGAY ĐẦU
                // log (vì lúc này log đang rỗng), rồi khi user thật sự mở conversation đó sau này,
                // loadHistory() nạp về các tin CŨ HƠN và chỉ biết append XUỐNG DƯỚI cái tin "mới nhất"
                // đã lỡ vẽ sẵn kia -- đảo ngược hẳn thứ tự thời gian (bug thật đã gặp: "tin mới lại ở
                // trên đầu"). Trường hợp này chỉ cần đảm bảo card tồn tại + kích hoạt loadHistory --
                // tin đã lưu DB rồi, lần nạp lịch sử đầu tiên sẽ tự thấy nó ở ĐÚNG vị trí theo
                // timestamp cùng các tin khác, không cần vẽ tay trước.
                //
                // READ (đã THỰC SỰ xem) không còn gửi mù ở đây nữa -- appendMessageBubble tự gắn
                // IntersectionObserver cho tin của người khác, chỉ gửi READ khi tin THẬT SỰ lọt vào
                // khung nhìn (xem ensureConversationCard, sendReadReceipt) -- kể cả conversation
                // không đang active/tin nạp từ lịch sử cũng dùng chung 1 cơ chế này.
                var msgEntry = conversations[frame.conversationId];
                if (msgEntry && msgEntry.historyLoadPromise) {
                    appendMessageBubble(frame.conversationId, frame.fromUserId, frame.body, frame.ts, frame.id);
                } else {
                    ensureConversationCard(frame.conversationId, 'Conversation');
                    loadHistory(frame.conversationId);
                }
                // Tin nhắn thật (không phải typing) -- coi như người gửi đã "dừng gõ", xoá chỉ báo typing ngay.
                if (typingTimers[frame.conversationId] && typingTimers[frame.conversationId][frame.fromUserId]) {
                    clearTimeout(typingTimers[frame.conversationId][frame.fromUserId]);
                    delete typingTimers[frame.conversationId][frame.fromUserId];
                    renderTypingIndicator(frame.conversationId);
                }
                // Vá thẳng "tin/giờ gần nhất" (+ tăng tạm badge "chưa đọc" nếu conversation này KHÔNG
                // đang mở, xem updateConvListEntryFromMessage) trong sidebar theo tin VỪA nhận, không
                // tự động chuyển màn hình sang nó (tránh giật focus khi đang gõ ở chỗ khác).
                updateConvListEntryFromMessage(frame.conversationId, frame.fromUserId, frame.body, frame.ts, false);
                if (frame.fromUserId !== myUserId) refreshNotifBadgeSoon(); // có thể @mention/trả lời mình -- để server tự biết, xem refreshNotifBadgeSoon
                break;
            case "PING":
                send({type: "PONG", id: frame.id});
                break;
            case "PONG":
                // Phan hoi cho ping minh tu gui (xem setInterval trong ws.onopen) -- khong can lam
                // gi them, nhan duoc la du biet ket noi con song.
                break;
            case "TYPING":
                handleTypingReceived(frame.conversationId, frame.fromUserId);
                break;
            case "PRESENCE":
                handlePresenceChange(frame.fromUserId, !!(frame.body && frame.body.online));
                break;
            case "SEEN":
                // frame.id = id cua tin nhan vua duoc xem (quy uoc correlation id, xem MessageType#SEEN).
                handleSeenReceived(frame.conversationId, frame.id);
                break;
            case "REACTION":
                // frame.id = id tin nhan, frame.fromUserId = ai vua react, frame.body.emoji = rong/thieu neu huy.
                handleReactionReceived(frame.id, frame.fromUserId, frame.body && frame.body.emoji);
                if (frame.fromUserId !== myUserId && frame.body && frame.body.emoji) refreshNotifBadgeSoon(); // đặt (không phải huỷ) reaction -- có thể vào tin của mình
                break;
            case "DELETE":
                // frame.id = id tin nhan vua bi xoa (server da tu kiem tra quyen truoc khi fan-out ra day).
                handleMessageDeleted(frame.conversationId, frame.id);
                break;
            case "PIN":
                // Chi bay toi day voi scope "shared" (ghim rieng khong fan-out, xem ChatSessionManager#handlePin).
                handlePinReceived(frame.conversationId, frame.id, frame.fromUserId, frame.body && frame.body.pinned);
                break;
            case "CONVERSATION_ADDED":
                // Vừa được thêm vào 1 conversation (DM lần đầu hoặc group mới) — gateway đã tự
                // subscribe ngầm hộ rồi (không rớt tin), tạo card sẵn nhưng KHÔNG tự chuyển màn hình
                // sang nó (giữ nguyên conversation đang mở nếu có) -- xuất hiện trong sidebar, tự bấm vào nếu muốn xem.
                // KHÔNG cần tự đánh dấu unread ở đây -- refreshConversationList() ngay bên dưới gọi
                // GET /conversations, server tự trả unreadCount ĐÚNG cho conversation vừa thêm này
                // (0 nếu là chính mình vừa tự mở, xem startDm/startGroup, do markRead tự chạy lúc gửi
                // tin đầu; >0 nếu có sẵn tin từ trước lúc mình được thêm vào 1 group cũ).
                ensureConversationCard(frame.conversationId, 'Mới: ' + frame.conversationId.substring(0, 8) + '…');
                loadHistory(frame.conversationId);
                refreshConversationList();
                break;
            case "CONVERSATION_DELETED":
                // Conversation vừa bị xoá hẳn (do chính mình bấm 🗑 -- tab khác của mình cũng nhận
                // được frame này -- hoặc do 1 thành viên khác xoá). Dọn UI ngay, không cần đợi
                // refreshConversationList() lần sau mới nhận ra là nó đã biến mất.
                removeConversationLocally(frame.conversationId);
                break;
            case "GOAWAY":
                // Gateway đang chuẩn bị tắt (scale down/rolling update) — chủ động đóng và
                // reconnect ngay thay vì đợi phát hiện đứt kết nối qua onclose (nhanh hơn cho user).
                setStatus('server đang rút lui, reconnect ngay...', 'pending');
                ws.close();
                break;
        }
    };
}

// --- init ---
// (Panel thông tin mặc định ẩn/hiện theo độ rộng màn hình + tự đóng khi resize xuống chế độ drawer
// đã xử lý ở infoPanelDrawerQuery phía trên, ngay chỗ khai báo infoPanelVisible -- gộp về 1 chỗ thay
// vì lặp lại y hệt 1 điều kiện matchMedia ở 2 nơi.)

// Còn token từ lần đăng nhập trước (localStorage) thì vào thẳng app -- token sai/hết hạn sẽ tự lộ
// ra qua AUTH_ERROR lúc connect() (xem ws.onmessage), lúc đó mới quay lại form đăng nhập.
myUserId = localStorage.getItem(STORAGE_ID_KEY) || '';
myUsername = localStorage.getItem(STORAGE_USERNAME_KEY) || '';
authToken = localStorage.getItem(STORAGE_TOKEN_KEY) || '';
if (authToken && myUserId) {
    enterApp();
} else {
    showLoginForm();
}
