function fetchJson(path, withAuth) {
    var opts = withAuth ? {headers: {'Authorization': 'Bearer ' + authToken}} : undefined;
    return fetch(HISTORY_API_BASE + path, opts).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    });
}

// Quả chuông thông báo: dữ liệu THẬT lấy qua API herald (GET/PUT /notifications), không tự dựng từ WS frame nữa (bản trước làm vậy mất khi F5, không có id DB nên đánh dấu đã đọc không lưu được); badge vẫn tăng ngay khi có frame liên quan, nội dung đồng bộ lại khi mở popup.
var notifications = []; // cache từ GET /notifications, mới nhất trước
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
            // Dùng openConversation() chứ không phải selectConversation() trần -- conversation của noti có thể chưa từng mở nên chưa có card, selectConversation/jumpToMessage âm thầm return nếu thiếu card (bug thật đã gặp).
            var conv = lastConvList.filter(function (c) { return c.conversationId === n.conversationId; })[0];
            openConversation(n.conversationId, conv && conversationLabel(conv), conv && membersSubtitle(conv));
            if (isNarrowViewport()) document.getElementById('layout').classList.add('mobileChatOpen');
            // Dùng messageTs (giờ tin gốc) chứ không phải n.ts (giờ sự kiện, có thể lệch xa với reaction) -- seek sai mốc từng gây "không tìm thấy tin gốc" dù tin còn (bug thật đã gặp); noti cũ thiếu field này thì rơi về n.ts.
            if (n.messageId) jumpToMessage(n.conversationId, n.messageId, n.messageTs != null ? n.messageTs : n.ts);
        };
        listEl.appendChild(item);
    });
}

// Gọi lúc vào app + mỗi lần mở popup -- đủ mới mà không cần polling/WS riêng cho notifications.
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
    // Đồng bộ lại danh sách thật trước khi dùng (không dùng cache cũ, badge tạm có thể đã lệch).
    loadNotifications().then(function () {
        var unread = notifications.filter(function (n) { return !n.read; });
        if (!unread.length) return;
        // Mở popup là coi như đã xem hết -- đánh dấu qua PUT từng noti (herald chưa có endpoint đánh dấu hàng loạt).
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

// Hỏi thẳng server (loadNotifications) thay vì tự đoán ở client -- đoán qua Set/regex từng sai khi tin thuộc conversation chưa mở (bug thật đã gặp); debounce vì gọi dồn nhiều event chỉ cần 1 lượt hỏi cuối.
var notifRefreshTimer = null;
function refreshNotifBadgeSoon() {
    if (notifRefreshTimer) return;
    notifRefreshTimer = setTimeout(function () {
        notifRefreshTimer = null;
        loadNotifications();
    }, 500);
}

// Cỡ 1 trang, dùng chung cho cả 2 hướng phân trang (before/after) -- cố tình không nạp hết đoạn chưa đọc 1 lần, phải lazy-load dần theo từng trang kể cả lúc mở lại.
var HISTORY_PAGE_SIZE = 30;

// Load lịch sử (GET /messages trên colony) ngay sau SUBSCRIBE_OK -- cuộn tới đúng con trỏ đã đọc lần trước (không còn luôn nhảy xuống tin mới nhất); cache Promise trên entry.historyLoadPromise để gọi lặp chỉ đợi chung 1 fetch.
function loadHistory(conversationId) {
    var entry = ensureConversationCard(conversationId, 'Conversation');
    if (entry.historyLoadPromise) return entry.historyLoadPromise;
    entry.historyLoaded = true;

    entry.historyLoadPromise = fetchJson('/read-cursor?conversationId=' + encodeURIComponent(conversationId), true)
        .then(function (res) {
            var cursor = res && res.data;
            return cursor ? loadAroundReadCursor(conversationId, cursor) : loadLatestPage(conversationId);
        })
        .catch(function (err) {
            console.warn('không load được lịch sử cho ' + conversationId, err);
            logToConversation(conversationId, '(không load được lịch sử: ' + err.message + ')');
            // Vẫn phải reset các cờ này dù load lỗi giữa chừng, nếu không tin sống tới qua WS sau đó sẽ mãi kẹt không cuộn/không gửi READ được.
            entry.suppressAutoScroll = false;
            entry.skipReadTracking = false;
        });
    return entry.historyLoadPromise;
}

// Tách riêng khỏi lúc tính scrollAnchor vì card có thể đang display:none khi lịch sử tải xong (mọi phép đo hình học tính ra 0) -- gọi lại hàm này khi conversation thật sự hiện ra (xem selectConversation).
function applyScrollAnchor(entry) {
    if (!entry.scrollAnchor) return;
    // Luôn cuộn xuống đáy thật -- vạch "Tin nhắn mới" chỉ còn tác dụng ngữ cảnh, không còn được đẩy sát đáy để "giấu" đoạn chưa đọc như bản trước (phản trực giác: tin mới bị che khuất).
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

// Nạp 1 trang trước/tại con trỏ (context, đã đọc) rồi 1 trang đầu của phần chưa đọc (phần còn lại nạp dần qua maybeLoadNewer) -- chèn vạch "Tin nhắn mới" và cuộn tới đó, đúng chỗ dừng lần trước.
function loadAroundReadCursor(conversationId, cursor) {
    var entry = conversations[conversationId];
    entry.suppressAutoScroll = true;
    entry.skipReadTracking = true;
    // totalUnreadCount lấy từ cursor.unreadCount (server COUNT thật) -- không bị giới hạn bởi cỡ 1 trang lazy-load.
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
                updateJumpBadge(entry);
                if (entry.jumpBtnEl) entry.jumpBtnEl.classList.add('show');
            } else {
                entry.stickToBottom = true;
                entry.scrollAnchor = {type: 'bottom'};
            }
            applyScrollAnchor(entry);
        });
}

// Cuộn gần đỉnh log thì tải thêm 1 trang cũ hơn, chèn lên đầu mà giữ nguyên vị trí đang xem (xem prependOlderMessages).
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

// Cuộn gần đáy mà vẫn còn phần chưa đọc chưa nạp hết (xem loadAroundReadCursor) thì nạp tiếp từng trang, append như tin sống qua WS (dùng chung appendMessageBubble).
function maybeLoadNewer(conversationId) {
    var entry = conversations[conversationId];
    if (!entry || entry.loadingNewer || !entry.hasMoreNewer || entry.newestLoadedTs == null) return;
    entry.loadingNewer = true;
    fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + HISTORY_PAGE_SIZE + '&after=' + entry.newestLoadedTs)
        .then(function (messages) {
            entry.hasMoreNewer = messages.length === HISTORY_PAGE_SIZE;
            // Nạp lazy-load là hành động ngầm, không được đụng vị trí đang xem -- khác tin sống qua WS (vẫn tự cuộn theo khi đang neo đáy).
            entry.suppressAutoScroll = true;
            messages.forEach(function (m) {
                appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
            });
            entry.suppressAutoScroll = false;
            // Tính lại stickToBottom theo thực tế -- nội dung mới vừa nối vào cuối có thể đã đẩy vị trí "đã xem" ra khỏi đáy.
            entry.stickToBottom = isNearBottom(entry.logEl);
            // totalUnreadCount không đổi ở đây (đã tính trong cursor.unreadCount lúc mở lại) -- chỉ vẽ lại badge cho chắc.
            updateJumpBadge(entry);
            if (!entry.stickToBottom && entry.jumpBtnEl) entry.jumpBtnEl.classList.add('show');
        })
        .catch(function (err) { console.warn('không tải thêm được tin mới hơn: ' + err.message); })
        .finally(function () { entry.loadingNewer = false; });
}

// Không dùng appendMessageBubble ở đây -- nó cập nhật state "dòng cuối cùng" nên sẽ sai nếu dùng để chèn lên đầu; gộp nhóm/vạch ngày tính riêng cho batch này, và không cần IntersectionObserver vì tin ở đây chắc chắn đã đọc từ trước.
function prependOlderMessages(conversationId, messagesDesc) {
    var entry = conversations[conversationId];
    var messages = messagesDesc.slice().reverse(); // cũ nhất trước -- đúng thứ tự chèn lên đầu log
    var frag = document.createDocumentFragment();
    var batchState = {lastGroupRow: null};
    var localDividerKey = null;
    var rowsInfo = [];
    // Ảnh/video trong batch vừa chèn có thể đổi chiều cao sau khi đã canh scrollTop lần đầu -- phải bù thêm phần chênh lệch khi đó, không thì nội dung đang xem bị trôi (bug từng gặp, tương tự appendMessageBubble nhưng ngược hướng).
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

    // Neo lại vị trí đang xem sau khi chèn nội dung phía trên (không thì scrollTop giữ nguyên sẽ khiến nội dung "nhảy" xuống).
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

// Tạo conversation qua POST /conversations (dùng chung DM/group, không lazy-create qua SUBSCRIBE nữa -- xem ARCHITECTURE.md mục 12); server tự thêm members + publish wake-subscribe, không cần tự gửi SUBSCRIBE.
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

// Bấm 1 tên trong tab "Nhắn 1-1" là gửi ngay, không cần bước xác nhận hay gõ tay UUID (xem renderUserPickers).
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
    // Chưa đăng nhập thì không tự mở WebSocket -- no-op an toàn cho mọi nơi lỡ gọi lại connect() (vd auto-retry ở onclose).
    if (!identityConfirmed) return;

    // NodePort của harbor trên cụm k3s local (xem harbor/helm/templates/services.yaml) -- chạy bằng "mvn exec:java" thay vì k3s thì đổi thành ws://localhost:8888/connect.
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
                // frame.serverId = pod harbor đang phục vụ session này -- tiện debug khi có nhiều pod mà không cần vào k8s check.
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
                refreshConversationList();
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
                // Tin sống cho conversation chưa từng loadHistory không được vẽ tay ở đây -- sẽ đứng sai chỗ (đầu log rỗng), đảo ngược thứ tự khi loadHistory nạp về sau (bug thật đã gặp); chỉ cần đảm bảo card + kích hoạt loadHistory, nó tự nạp đúng vị trí.
                // READ chỉ gửi khi tin thật sự lọt vào khung nhìn qua IntersectionObserver trong appendMessageBubble (xem sendReadReceipt), không gửi mù ở đây.
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
                // Cập nhật sidebar theo tin vừa nhận nhưng không tự chuyển màn hình sang nó (tránh giật focus khi đang gõ chỗ khác).
                updateConvListEntryFromMessage(frame.conversationId, frame.fromUserId, frame.body, frame.ts, false);
                if (frame.fromUserId !== myUserId) refreshNotifBadgeSoon(); // có thể @mention/trả lời mình -- để server tự biết, xem refreshNotifBadgeSoon
                break;
            case "PING":
                send({type: "PONG", id: frame.id});
                break;
            case "PONG":
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
                // Gateway đã tự subscribe ngầm hộ -- tạo card sẵn nhưng không tự chuyển màn hình sang nó; unreadCount lấy đúng từ refreshConversationList() (GET /conversations) bên dưới, không cần tự đánh dấu ở đây.
                ensureConversationCard(frame.conversationId, 'Mới: ' + frame.conversationId.substring(0, 8) + '…');
                loadHistory(frame.conversationId);
                refreshConversationList();
                break;
            case "CONVERSATION_DELETED":
                // Dọn UI ngay khi conversation bị xoá (do mình hoặc thành viên khác), không đợi refreshConversationList() lần sau.
                removeConversationLocally(frame.conversationId);
                break;
            case "GOAWAY":
                // Gateway chuẩn bị tắt (scale down/rolling update) -- chủ động đóng + reconnect ngay thay vì đợi onclose phát hiện.
                setStatus('server đang rút lui, reconnect ngay...', 'pending');
                ws.close();
                break;
        }
    };
}

// --- init ---
// (Panel thông tin ẩn/hiện theo độ rộng màn hình đã xử lý ở infoPanelDrawerQuery phía trên, tránh lặp lại điều kiện matchMedia ở 2 nơi.)

// Còn token từ lần trước thì vào thẳng app -- token sai/hết hạn sẽ tự lộ ra qua AUTH_ERROR lúc connect(), lúc đó mới quay lại form đăng nhập.
myUserId = localStorage.getItem(STORAGE_ID_KEY) || '';
myUsername = localStorage.getItem(STORAGE_USERNAME_KEY) || '';
authToken = localStorage.getItem(STORAGE_TOKEN_KEY) || '';
if (authToken && myUserId) {
    enterApp();
} else {
    showLoginForm();
}
