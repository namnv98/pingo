function displayName(userId) {
    return usernameById[userId] || (userId.substring(0, 8) + '…');
}

// Màu avatar hash theo id (ổn định, không cần lưu) -- chọn trong bảng màu cố định thay vì random HSL để tránh nhìn như cầu vồng.
var AVATAR_PALETTE = ['#6d5bf5', '#3b82c4', '#14919b', '#2f9b6e', '#c08a2e', '#d0665a', '#b0538f', '#5f6b85'];
function avatarColor(id) {
    var hash = 0;
    for (var i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) >>> 0;
    return AVATAR_PALETTE[hash % AVATAR_PALETTE.length];
}

function avatarInitial(name) {
    return (name || '?').trim().charAt(0).toUpperCase() || '?';
}

// Ảnh đại diện thật (đã upload qua PUT /users/avatar hoặc /conversations/avatar) nếu có, null nếu chưa đặt -- xem applyAvatar.
function userAvatarUrl(userId) {
    var fileId = avatarFileIdById[userId];
    return fileId ? FILE_SERVER_BASE + '/v2/api/download?id=' + encodeURIComponent(fileId) : null;
}

// Avatar cho cả conversation -- DM lấy avatar người kia (ảnh thật nếu người đó đã đặt, không thì màu+chữ cái),
// group ưu tiên ảnh riêng của group (conv.avatarFileId, xem PUT /conversations/avatar) rồi mới tới icon chung mặc định.
function conversationAvatar(conv) {
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length === 1) {
        var peerImageUrl = userAvatarUrl(others[0]);
        if (peerImageUrl) return {imageUrl: peerImageUrl};
        return {color: avatarColor(others[0]), initial: avatarInitial(displayName(others[0]))};
    }
    if (conv.avatarFileId) {
        return {imageUrl: FILE_SERVER_BASE + '/v2/api/download?id=' + encodeURIComponent(conv.avatarFileId)};
    }
    // Icon "users" màu accent thay vì emoji 👥 nền xám để đồng bộ bộ icon outline dùng khắp app.
    return {color: 'var(--accent)', icon: ICON.users};
}

// Dùng chung cho mọi nơi hiện avatar (sidebar, conv-head, Info panel, whoami) để tránh lặp nhánh if/else ảnh/initial/icon ở từng nơi gọi.
function applyAvatar(el, avatar) {
    if (avatar.imageUrl) {
        el.style.background = 'none';
        el.innerHTML = '<img src="' + avatar.imageUrl + '" alt="">';
        return;
    }
    el.style.background = avatar.color;
    if (avatar.icon) el.innerHTML = avatar.icon; else el.innerText = avatar.initial;
}

// Avatar+tên của CHÍNH MÌNH ở topbar -- gọi lại mỗi khi myUsername/avatar của mình đổi (xem refreshUserList, openProfileModal).
function renderWhoami() {
    var whoamiEl = document.getElementById('whoami');
    whoamiEl.innerHTML = '<span class="avatar"></span><span class="name"></span>';
    var myImageUrl = userAvatarUrl(myUserId);
    applyAvatar(whoamiEl.querySelector('.avatar'), myImageUrl ? {imageUrl: myImageUrl} : {color: avatarColor(myUserId), initial: avatarInitial(myUsername)});
    whoamiEl.querySelector('.name').innerText = myUsername;
}

// --- "Hồ sơ của bạn" -- đổi ảnh đại diện + tên hiển thị của CHÍNH mình (PUT /users/avatar, PUT /users) ---
// Cùng khuôn lazy-create "ensureX()" + toggle class .show với ensureBgPicker (messaging-core.js).

function ensureProfileModal() {
    var overlay = document.getElementById('profileModalOverlay');
    if (overlay) return overlay;
    overlay = document.createElement('div');
    overlay.id = 'profileModalOverlay';
    overlay.innerHTML =
        '<div class="bgPickerModal">' +
        '<div class="bgPickerHead"><span>Hồ sơ của bạn</span>' +
        '<button type="button" class="bgPickerClose" title="Đóng (Esc)">' + ICON.close + '</button></div>' +
        '<div class="bgPickerBody">' +
        '<div class="profileAvatarRow"><span class="avatar" id="profileAvatarPreview"></span>' +
        '<input type="file" accept="image/*" id="profileAvatarInput" style="display:none">' +
        '<button type="button" class="bgUploadBtn" id="profileChangeAvatarBtn">' + ICON.image + ' Đổi ảnh</button></div>' +
        '<div class="profileUsernameRow"><span id="profileUsernameValue"></span>' +
        '<button type="button" class="icon" id="profileRenameBtn" title="Đổi tên hiển thị">' + ICON.edit + '</button></div>' +
        '</div></div>';
    // Bấm ra NGOÀI modal (đúng vào nền tối) mới đóng -- giống #bgPickerOverlay/#mediaLightbox.
    overlay.querySelector('.bgPickerClose').onclick = function (e) { e.stopPropagation(); closeProfileModal(); };
    overlay.onclick = function (e) { if (e.target === overlay) closeProfileModal(); };
    document.body.appendChild(overlay);
    overlay.querySelector('#profileChangeAvatarBtn').onclick = function () { overlay.querySelector('#profileAvatarInput').click(); };
    overlay.querySelector('#profileAvatarInput').addEventListener('change', function (e) {
        var file = e.target.files[0];
        e.target.value = '';
        if (file) changeMyAvatar(file);
    });
    overlay.querySelector('#profileRenameBtn').onclick = function () { changeMyUsername(); };
    return overlay;
}
document.addEventListener('keydown', function (e) {
    var overlay = document.getElementById('profileModalOverlay');
    if (overlay && overlay.classList.contains('show') && e.key === 'Escape') closeProfileModal();
});

function refreshProfileModalContent() {
    var overlay = document.getElementById('profileModalOverlay');
    if (!overlay) return;
    var myImageUrl = userAvatarUrl(myUserId);
    applyAvatar(overlay.querySelector('#profileAvatarPreview'), myImageUrl ? {imageUrl: myImageUrl} : {color: avatarColor(myUserId), initial: avatarInitial(myUsername)});
    overlay.querySelector('#profileUsernameValue').innerText = myUsername;
}

function openProfileModal() {
    ensureProfileModal().classList.add('show');
    refreshProfileModalContent();
}

function closeProfileModal() {
    var overlay = document.getElementById('profileModalOverlay');
    if (overlay) overlay.classList.remove('show');
}

// --- Tìm kiếm tin nhắn TOÀN CỤC (xuyên mọi cuộc trò chuyện) -- GET /messages/search không kèm
// conversationId. Cùng khuôn lazy-create "ensureX()" + portal document.body với ensureProfileModal/
// ensureBgPicker (KHÔNG lồng trong DOM tại chỗ -- xem bug thật đã gặp với #notifMenu/#themeMenu). ---

var globalSearchDebounce = null;

function ensureSearchModal() {
    var overlay = document.getElementById('searchModalOverlay');
    if (overlay) return overlay;
    overlay = document.createElement('div');
    overlay.id = 'searchModalOverlay';
    overlay.innerHTML =
        '<div class="bgPickerModal searchModal">' +
        '<div class="bgPickerHead"><span>Tìm kiếm tin nhắn</span>' +
        '<button type="button" class="bgPickerClose" title="Đóng (Esc)">' + ICON.close + '</button></div>' +
        '<div class="bgPickerBody">' +
        '<div class="searchModalInputWrap"><span class="searchModalIcon">' + ICON.search + '</span>' +
        '<input type="text" class="searchModalInput" placeholder="Tìm trong mọi cuộc trò chuyện..."></div>' +
        '<div class="searchModalResults"></div>' +
        '<div class="searchModalEmpty" style="display:none">Không tìm thấy tin nhắn nào khớp.</div>' +
        '</div></div>';
    overlay.querySelector('.bgPickerClose').onclick = function (e) { e.stopPropagation(); closeSearchModal(); };
    overlay.onclick = function (e) { if (e.target === overlay) closeSearchModal(); };
    document.body.appendChild(overlay);
    var inputEl = overlay.querySelector('.searchModalInput');
    inputEl.addEventListener('input', function () {
        if (globalSearchDebounce) clearTimeout(globalSearchDebounce);
        var term = inputEl.value.trim();
        if (!term) { renderSearchResults([]); return; }
        globalSearchDebounce = setTimeout(function () { runGlobalSearch(term); }, 300);
    });
    return overlay;
}
document.addEventListener('keydown', function (e) {
    var overlay = document.getElementById('searchModalOverlay');
    if (overlay && overlay.classList.contains('show') && e.key === 'Escape') closeSearchModal();
});
// Ctrl/Cmd+K mở tìm kiếm toàn cục (kiểu Slack) -- chặn hành vi mặc định của trình duyệt (thường là focus thanh địa chỉ).
document.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k' && identityConfirmed) {
        e.preventDefault();
        openSearchModal();
    }
});

function openSearchModal() {
    var overlay = ensureSearchModal();
    overlay.classList.add('show');
    var inputEl = overlay.querySelector('.searchModalInput');
    inputEl.focus();
    inputEl.select();
}

function closeSearchModal() {
    var overlay = document.getElementById('searchModalOverlay');
    if (overlay) overlay.classList.remove('show');
}

function runGlobalSearch(term) {
    fetchJson('/messages/search?q=' + encodeURIComponent(term), true)
        .then(function (results) {
            var overlay = document.getElementById('searchModalOverlay');
            // Người dùng có thể đã gõ tiếp/đóng modal trong lúc chờ HTTP -- bỏ kết quả trễ nếu ô nhập không còn khớp term này nữa.
            if (!overlay || overlay.querySelector('.searchModalInput').value.trim() !== term) return;
            renderSearchResults(results, term);
        })
        .catch(function (err) { console.warn('tìm kiếm toàn cục lỗi', err); });
}

// Escape HTML TRƯỚC rồi mới thay marker (/, xem ts_headline ở MessageHistoryRegistry#searchMessages)
// thành <mark> thật -- BẮT BUỘC đúng thứ tự này, không thì snippet (nội dung tin nhắn CỦA NGƯỜI DÙNG,
// không đáng tin) chèn thẳng qua innerHTML sẽ là lỗ hổng XSS lưu trữ (cùng cách renderMessageText đã làm).
function escapeAndMarkSnippet(snippet) {
    if (!snippet) return '';
    var escaped = snippet.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return escaped.replace(/\u0001/g, '<mark>').replace(/\u0002/g, '</mark>');
}

function renderSearchResults(results, searchTerm) {
    var overlay = document.getElementById('searchModalOverlay');
    if (!overlay) return;
    var listEl = overlay.querySelector('.searchModalResults');
    var emptyEl = overlay.querySelector('.searchModalEmpty');
    listEl.innerHTML = '';
    emptyEl.style.display = results.length ? 'none' : (overlay.querySelector('.searchModalInput').value.trim() ? '' : 'none');
    results.forEach(function (r) {
        var conv = lastConvList.filter(function (c) { return c.conversationId === r.conversationId; })[0];
        var item = document.createElement('div');
        item.className = 'searchResultItem';
        item.innerHTML = '<span class="avatar"></span>' +
            '<div class="searchResultBody">' +
            '<div class="searchResultHead"><span class="searchResultName"></span><span class="searchResultConv"></span><span class="searchResultTime"></span></div>' +
            '<div class="searchResultSnippet"></div>' +
            '</div>';
        var senderImageUrl = userAvatarUrl(r.fromUserId);
        applyAvatar(item.querySelector('.avatar'), senderImageUrl ? {imageUrl: senderImageUrl} : {color: avatarColor(r.fromUserId), initial: avatarInitial(displayName(r.fromUserId))});
        item.querySelector('.searchResultName').innerText = displayName(r.fromUserId);
        item.querySelector('.searchResultConv').innerText = conv ? conversationLabel(conv) : 'Cuộc trò chuyện';
        item.querySelector('.searchResultTime').innerText = relativeTime(r.ts);
        item.querySelector('.searchResultSnippet').innerHTML = escapeAndMarkSnippet(r.snippet);
        item.onclick = function () {
            closeSearchModal();
            // Hội thoại này có thể CHƯA TỪNG mở trong phiên hiện tại (chưa có card) -- jumpToMessage tự
            // return im lặng nếu thiếu card (bug thật đã gặp y hệt ở chỗ bấm thông báo, xem history-ws.js
            // renderNotifMenu). openConversation() trước để đảm bảo có card, giống hệt cách noti đã làm.
            openConversation(r.conversationId, conv && conversationLabel(conv), conv && membersSubtitle(conv));
            if (typeof isNarrowViewport === 'function' && isNarrowViewport()) document.getElementById('layout').classList.add('mobileChatOpen');
            jumpToMessage(r.conversationId, r.id, r.ts, searchTerm);
        };
        listEl.appendChild(item);
    });
}

// Tái dùng NGUYÊN luồng upload file-server đã có cho ảnh/video gửi trong chat (xem uploadOneFile,
// compose-reactions-pins.js) -- conversationId rỗng vì avatar không thuộc về 1 cuộc trò chuyện nào,
// không nên lẫn vào tab "Files" của bất kỳ ai (xem FileRegistry#listForConversation).
function changeMyAvatar(file) {
    uploadOneFile('', file).then(function (result) {
        return fetch(HISTORY_API_BASE + '/users/avatar?avatarFileId=' + encodeURIComponent(result.fileId), {
            method: 'PUT',
            headers: {'Authorization': 'Bearer ' + authToken}
        });
    }).then(function (res) {
        if (!res.ok) return res.json().then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
        return res.json();
    }).then(function (data) {
        avatarFileIdById[myUserId] = data.avatarFileId;
        renderWhoami();
        refreshProfileModalContent();
        renderConversationList(lastConvList); // avatar mới có thể hiện lại ở DM/group mà mình là thành viên
        if (activeConversationId) renderInfoPanel(activeConversationId);
    }).catch(function (err) {
        appAlert('đổi ảnh đại diện lỗi: ' + err.message, 'Lỗi');
    });
}

// PUT /users?username= đã có sẵn từ trước (UserRegistry#updateUsername) nhưng chưa có UI nào gọi tới -- tái dùng appPrompt như renameConversation.
function changeMyUsername() {
    appPrompt('Tên hiển thị của bạn:', myUsername, {title: 'Đổi tên hiển thị', confirmText: 'Lưu', cancelText: 'Huỷ', placeholder: 'Nhập tên...'}).then(function (next) {
        if (next === null) return;
        next = next.trim();
        if (!next || next === myUsername) return;
        fetch(HISTORY_API_BASE + '/users?username=' + encodeURIComponent(next), {
            method: 'PUT',
            headers: {'Authorization': 'Bearer ' + authToken}
        }).then(function (res) {
            if (!res.ok) return res.json().then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
            return res.json();
        }).then(function (data) {
            myUsername = data.username;
            localStorage.setItem(STORAGE_USERNAME_KEY, myUsername);
            usernameById[myUserId] = myUsername;
            renderWhoami();
            refreshProfileModalContent();
            renderConversationList(lastConvList); // tên của mình có thể hiện trong label DM/subtitle group
            if (activeConversationId) renderInfoPanel(activeConversationId);
        }).catch(function (err) {
            appAlert('đổi tên lỗi: ' + err.message, 'Lỗi');
        });
    });
}

function formatTime(tsEpochMillis) {
    if (!tsEpochMillis) return '';
    var d = new Date(tsEpochMillis);
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    return hh + ':' + mm;
}

// --- presence (online/offline) ---

// Snapshot online/offline ban đầu (GET /presence, herald) -- WS PRESENCE chỉ báo lúc THAY ĐỔI về sau, không tự có trạng thái ban đầu.
function refreshPresenceSnapshot(userIds) {
    if (!userIds.length) return;
    fetch(HERALD_API_BASE + '/presence?userIds=' + userIds.map(encodeURIComponent).join(','), {
        headers: {'Authorization': 'Bearer ' + authToken}
    })
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (list) {
            list.forEach(function (p) { onlineUserIds[p.userId] = p.online; });
            renderConversationList(lastConvList);
            Object.keys(conversations).forEach(updateConvHeadPresence);
        })
        .catch(function (err) {
            console.warn('không load được presence snapshot', err);
        });
}

// Frame PRESENCE qua WS -- cập nhật state rồi vẽ lại mọi chỗ có hiện trạng thái (sidebar dot + conv-head đang mở).
function handlePresenceChange(userId, online) {
    onlineUserIds[userId] = online;
    renderConversationList(lastConvList);
    Object.keys(conversations).forEach(updateConvHeadPresence);
}

// Chỉ hiện "Đang hoạt động" cho DM -- group nhiều người không có 1 trạng thái online/offline đại diện chung hợp lý.
function updateConvHeadPresence(conversationId) {
    var entry = conversations[conversationId];
    var statusEl = entry && entry.el.querySelector('.online-status');
    if (!statusEl) return;
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    var others = conv ? conv.memberUserIds.filter(function (id) { return id !== myUserId; }) : [];
    var online = others.length === 1 && !!onlineUserIds[others[0]];
    statusEl.classList.toggle('show', online);
    statusEl.innerText = online ? '● Đang hoạt động' : '';
    // Cần conv thật từ lastConvList -- card vừa tạo (vd CONVERSATION_ADDED) có thể chưa có, sẽ tự cập nhật ở lần refresh kế tiếp.
    if (conv) {
        var avatarEl = entry.el.querySelector('.conv-head .avatar');
        var avatar = conversationAvatar(conv);
        applyAvatar(avatarEl, avatar);
        // "#" trước tên NHÓM (không DM) -- theo quy ước kênh nhóm kiểu Slack trong thiết kế tham khảo.
        entry.el.querySelector('.conv-head').classList.toggle('group', others.length > 1);
    }
    // Gọi ké renderInfoPanel ở đây vì hàm này vốn đã chạy đúng lúc cần (chọn conversation, đổi presence, refresh...).
    if (conversationId === activeConversationId) renderInfoPanel(conversationId);
}

// --- panel thông tin/thành viên bên phải (kiểu Slack/Discord "Details") ---

// Dưới 1000px #infoPanel là drawer che kín màn hình (@media max-width:1000px) -- mặc định mở sẵn như desktop sẽ che kín app ngay lúc vào trên điện thoại (bug thật đã gặp), nên default theo đúng breakpoint CSS đó.
// Bấm bất kỳ chỗ nào trong avatar đầu panel (cả ảnh lẫn nút bút chì nhỏ #infoPanelAvatarEditBtn, event
// bubble lên tới đây) đều mở đổi ảnh nhóm -- CHỈ khi đang editable (group, xem renderInfoPanel gắn/gỡ
// class này), khỏi phải gắn onclick riêng cho từng phần tử con.
document.getElementById('infoPanelAvatarWrap').addEventListener('click', function () {
    if (this.classList.contains('editable')) changeGroupAvatar();
});

var infoPanelDrawerQuery = window.matchMedia('(max-width: 1000px)');
var infoPanelVisible = !infoPanelDrawerQuery.matches;
document.getElementById('infoPanel').classList.toggle('collapsed', !infoPanelVisible);
// Tự đóng panel khi resize/xoay màn hình xuống dưới breakpoint (tránh bug che kín ở trên) -- không tự mở lại khi rộng ra, tôn trọng lựa chọn ẩn/hiện của người dùng.
infoPanelDrawerQuery.addEventListener('change', function (e) {
    if (e.matches && infoPanelVisible) toggleInfoPanel();
});
function toggleInfoPanel() {
    infoPanelVisible = !infoPanelVisible;
    document.getElementById('infoPanel').classList.toggle('collapsed', !infoPanelVisible);
}

// Cần conv.memberUserIds thật từ lastConvList (GET /conversations) -- không suy được từ conversationId suông. Sắp online lên trước cho dễ nhìn.
// Tab đang xem ở panel phải -- giữ nguyên khi đổi conversation (đang xem Files thì mở hội thoại khác cũng muốn thấy Files của nó).
var infoActiveTab = 'info';
function switchInfoTab(tab) {
    infoActiveTab = tab;
    document.getElementById('infoTabInfoBtn').classList.toggle('active', tab === 'info');
    document.getElementById('infoTabFilesBtn').classList.toggle('active', tab === 'files');
    document.getElementById('infoTabPinsBtn').classList.toggle('active', tab === 'pins');
    document.getElementById('infoTabLinksBtn').classList.toggle('active', tab === 'links');
    renderInfoPanel(activeConversationId);
}

function renderInfoPanel(conversationId) {
    var emptyEl = document.getElementById('infoEmpty');
    var bodyEl = document.getElementById('infoPanelBody');
    var filesEl = document.getElementById('infoFilesBody');
    var pinsEl = document.getElementById('infoPinsBody');
    var linksEl = document.getElementById('infoLinksBody');
    var conv = conversationId && lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv) {
        emptyEl.style.display = '';
        bodyEl.style.display = 'none';
        filesEl.style.display = 'none';
        pinsEl.style.display = 'none';
        linksEl.style.display = 'none';
        return;
    }
    emptyEl.style.display = 'none';
    bodyEl.style.display = infoActiveTab === 'info' ? 'flex' : 'none';
    filesEl.style.display = infoActiveTab === 'files' ? 'flex' : 'none';
    pinsEl.style.display = infoActiveTab === 'pins' ? 'flex' : 'none';
    linksEl.style.display = infoActiveTab === 'links' ? 'flex' : 'none';
    if (infoActiveTab === 'files') {
        loadConversationFiles(conversationId);
        return; // noi dung tab Info khong can tinh lai khi dang khong hien no
    }
    if (infoActiveTab === 'pins') {
        loadConversationPins(conversationId);
        return;
    }
    if (infoActiveTab === 'links') {
        loadConversationLinks(conversationId);
        return;
    }

    var isGroupConv = conv.memberUserIds.filter(function (id) { return id !== myUserId; }).length > 1;
    var avatar = conversationAvatar(conv);
    var avatarEl = document.getElementById('infoPanelAvatar');
    applyAvatar(avatarEl, avatar);
    // Chỉ GROUP mới sửa được ảnh đại diện -- DM hiện avatar thật của người kia, không phải thứ chính
    // mình sở hữu (xem changeGroupAvatar). Nút chỉ hiện khi hover (CSS #infoPanelAvatarWrap.editable:hover).
    var avatarWrapEl = document.getElementById('infoPanelAvatarWrap');
    avatarWrapEl.classList.toggle('editable', isGroupConv);
    if (isGroupConv) document.getElementById('infoPanelAvatarEditBtn').innerHTML = ICON.edit;
    document.getElementById('infoPanelName').innerText = conversationLabel(conv);
    document.getElementById('infoPanelSubtitle').innerText = conv.memberUserIds.length + ' thành viên';
    document.getElementById('infoMemberCount').innerText = conv.memberUserIds.length;
    document.getElementById('infoMemberCountRow').innerText = conv.memberUserIds.length;
    document.getElementById('infoConvIdValue').innerText = conv.conversationId.substring(0, 8) + '…';
    document.getElementById('infoTypeValue').innerText = isGroupConv ? 'Nhóm' : 'Nhắn tin trực tiếp';
    document.getElementById('infoActivityValue').innerText = relativeTime(conv.lastMessageAt);

    var sorted = conv.memberUserIds.slice().sort(function (a, b) {
        var aOnline = a === myUserId || !!onlineUserIds[a];
        var bOnline = b === myUserId || !!onlineUserIds[b];
        return (bOnline ? 1 : 0) - (aOnline ? 1 : 0);
    });
    // Chấm + chữ (.infoStatusVal) đồng bộ với danh sách thành viên bên dưới, không dùng pill xanh/vàng kiểu topbar (lệch tông).
    var anyoneElseOnline = conv.memberUserIds.some(function (id) { return id !== myUserId && !!onlineUserIds[id]; });
    var statusEl = document.getElementById('infoStatusValue');
    statusEl.className = 'infoRowValue infoStatusVal' + (anyoneElseOnline ? ' online' : '');
    statusEl.innerText = anyoneElseOnline ? 'Đang hoạt động' : 'Ngoại tuyến';
    // Chấm trạng thái trên avatar đầu panel dùng CÙNG tín hiệu đó (xanh có ai online / xám không).
    document.getElementById('infoPanelStatusDot').classList.toggle('online', anyoneElseOnline);
    var listEl = document.getElementById('infoMemberList');
    listEl.innerHTML = '';
    sorted.forEach(function (id) {
        var isMe = id === myUserId;
        var online = isMe || !!onlineUserIds[id];
        var row = document.createElement('div');
        row.className = 'infoMemberRow';
        row.innerHTML = '<span class="avatar-wrap"><span class="avatar"></span><span class="status-dot' + (online ? ' online' : '') + '"></span></span><span class="name"></span>' +
            '<span class="roleBadge ' + (online ? 'online">Online' : 'offline">Offline') + '</span>';
        var memberImageUrl = userAvatarUrl(id);
        applyAvatar(row.querySelector('.avatar'), memberImageUrl ? {imageUrl: memberImageUrl} : {color: avatarColor(id), initial: avatarInitial(displayName(id))});
        var nameEl = row.querySelector('.name');
        nameEl.innerText = displayName(id);
        if (isMe) {
            var you = document.createElement('span');
            you.className = 'you';
            you.innerText = ' (bạn)';
            nameEl.appendChild(you);
        }
        listEl.appendChild(row);
    });
}

// Đổi ảnh đại diện RIÊNG của group đang mở trong Info panel -- chỉ nút #infoPanelAvatarEditBtn (chỉ
// hiện cho group, xem renderInfoPanel) gọi tới hàm này nên khỏi tự kiểm tra lại isGroupConv ở đây.
// Cùng luồng upload với changeMyAvatar (tái dùng uploadOneFile), chỉ khác endpoint đích.
function changeGroupAvatar() {
    if (!activeConversationId) return;
    var conversationId = activeConversationId;
    var input = document.createElement('input');
    input.type = 'file';
    input.accept = 'image/*';
    input.style.display = 'none';
    input.addEventListener('change', function (e) {
        var file = e.target.files[0];
        input.remove();
        if (!file) return;
        uploadOneFile('', file).then(function (result) {
            return fetch(HISTORY_API_BASE + '/conversations/avatar?conversationId=' + encodeURIComponent(conversationId) + '&avatarFileId=' + encodeURIComponent(result.fileId), {
                method: 'PUT',
                headers: {'Authorization': 'Bearer ' + authToken}
            });
        }).then(function (res) {
            if (!res.ok) return res.json().then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
            return res.json();
        }).then(function () {
            // Nạp lại NGUYÊN từ server rồi vẽ lại sidebar + conv-head + info panel cùng lúc -- đúng
            // pattern renameConversation() đang dùng, thay vì tự vá lastConvList + gọi rải rác từng hàm render.
            refreshConversationList();
        }).catch(function (err) {
            appAlert('đổi ảnh nhóm lỗi: ' + err.message, 'Lỗi');
        });
    });
    document.body.appendChild(input);
    input.click();
}

// Tab "Files" -- ảnh/video lấy thẳng từ bảng files (gắn conversationId lúc upload, xem FileRegistry#listForConversation), không cần dò lịch sử tin nhắn.
function loadConversationFiles(conversationId) {
    fetch(HISTORY_API_BASE + '/files?conversationId=' + encodeURIComponent(conversationId) + '&limit=100')
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(renderFilesList)
        .catch(function (err) {
            console.warn('không load được danh sách file', err);
        });
}

// App chỉ nhận upload image/video (xem accept="image/*,video/*" ở compose) -- bấm vào mở lightbox prev/next thay vì window.open.
function renderFilesList(list) {
    var listEl = document.getElementById('infoFilesList');
    var emptyEl = document.getElementById('infoFilesEmpty');
    document.getElementById('infoFilesCount').innerText = list.length;
    listEl.innerHTML = '';
    emptyEl.style.display = list.length ? 'none' : '';
    var lightboxFilesForList = list.map(function (f) {
        return {fileUrl: FILE_SERVER_BASE + '/v2/api/download?id=' + encodeURIComponent(f.id), fileMime: f.mime, fileId: f.id};
    });
    list.forEach(function (f, index) {
        var isVideo = (f.mime || '').indexOf('video/') === 0;
        var item = document.createElement('div');
        item.className = 'mediaGridItem' + (isVideo ? ' isVideo' : '');
        item.innerHTML =
            '<img>' +
            '<div class="mediaGridMeta"></div>';
        // Video dùng thumbnail (xem get-thumbnail.lua) thay vì tải cả file chỉ để hiện 1 ảnh nhỏ.
        item.querySelector('img').src = isVideo
            ? FILE_SERVER_BASE + '/v2/api/thumbnail?id=' + encodeURIComponent(f.id) + '&size=200x200'
            : lightboxFilesForList[index].fileUrl;
        item.querySelector('.mediaGridMeta').innerText = displayName(f.fromUserId) + ' · ' + relativeTime(f.ts);
        item.onclick = function () { openMediaLightbox(lightboxFilesForList, index); };
        listEl.appendChild(item);
    });
}

// --- tab "Pins" ---

// Ghim chung + ghim riêng của mình, server tự lọc (xem MessagePinRegistry#listPins) -- cần auth vì ghim riêng là dữ liệu riêng từng user.
function loadConversationPins(conversationId) {
    fetchJson('/pins?conversationId=' + encodeURIComponent(conversationId), true)
        .then(function (list) { renderPinsList(conversationId, list); })
        .catch(function (err) {
            console.warn('không load được danh sách ghim', err);
        });
}

function renderPinsList(conversationId, list) {
    var listEl = document.getElementById('infoPinsList');
    var emptyEl = document.getElementById('infoPinsEmpty');
    document.getElementById('infoPinsCount').innerText = list.length;
    listEl.innerHTML = '';
    emptyEl.style.display = list.length ? 'none' : '';
    list.forEach(function (p) {
        var item = document.createElement('div');
        item.className = 'fileListItem';
        item.innerHTML =
            '<div class="fileListThumb iconThumb fa-solid ' + (p.scope === 'shared' ? 'fa-thumbtack' : 'fa-lock') + '"></div>' +
            '<div class="fileListInfo"><span class="fileListName"></span><span class="fileListMeta"></span></div>' +
            '<button type="button" class="icon pinListUnpinBtn" title="Bỏ ghim">' + ICON.closeSm + '</button>';
        item.querySelector('.fileListName').innerText = displayName(p.fromUserId) + ': ' + snippetForBody(p.body);
        item.querySelector('.fileListMeta').innerText =
            (p.scope === 'shared' ? 'Ghim chung' : 'Ghim riêng') + ' · ' + relativeTime(p.pinnedAt);
        item.onclick = function () { jumpToMessage(conversationId, p.messageId, p.ts); };
        item.querySelector('.pinListUnpinBtn').onclick = function (e) {
            e.stopPropagation();
            sendPin(conversationId, p.messageId, p.scope, false);
            // Optimistic -- ghim riêng không có frame PIN xác nhận qua WS (khác ghim chung, xem handlePinReceived).
            item.remove();
        };
        listEl.appendChild(item);
    });
}

// --- tab "Links" ---

// Link trích từ nội dung tin nhắn, chung cho cả conversation (xem MessageLinkRegistry#listForConversation).
function loadConversationLinks(conversationId) {
    fetchJson('/links?conversationId=' + encodeURIComponent(conversationId) + '&limit=100', false)
        .then(renderLinksList)
        .catch(function (err) {
            console.warn('không load được danh sách link', err);
        });
}

function renderLinksList(list) {
    var listEl = document.getElementById('infoLinksList');
    var emptyEl = document.getElementById('infoLinksEmpty');
    document.getElementById('infoLinksCount').innerText = list.length;
    listEl.innerHTML = '';
    emptyEl.style.display = list.length ? 'none' : '';
    list.forEach(function (l) {
        var item = document.createElement('div');
        item.className = 'fileListItem';
        item.innerHTML =
            '<div class="fileListThumb iconThumb fa-solid fa-link"></div>' +
            '<div class="fileListInfo"><span class="fileListName"></span><span class="fileListMeta"></span></div>';
        item.querySelector('.fileListName').innerText = l.url;
        item.querySelector('.fileListMeta').innerText = displayName(l.fromUserId) + ' · ' + relativeTime(l.ts);
        item.onclick = function () { window.open(l.url, '_blank', 'noopener'); };
        listEl.appendChild(item);
    });
}

// --- typing indicator ---

// Server không có frame "đã dừng gõ" riêng -- suy bằng timeout tự hết hạn cho (conversationId, fromUserId), gõ tiếp thì reset giờ.
function handleTypingReceived(conversationId, fromUserId) {
    if (fromUserId === myUserId) return;
    if (!typingTimers[conversationId]) typingTimers[conversationId] = {};
    if (typingTimers[conversationId][fromUserId]) clearTimeout(typingTimers[conversationId][fromUserId]);
    typingTimers[conversationId][fromUserId] = setTimeout(function () {
        delete typingTimers[conversationId][fromUserId];
        renderTypingIndicator(conversationId);
    }, TYPING_EXPIRE_MS);
    renderTypingIndicator(conversationId);
}

function renderTypingIndicator(conversationId) {
    var entry = conversations[conversationId];
    var el = entry && entry.el.querySelector('.typing-indicator');
    if (!el) return;
    var ids = Object.keys(typingTimers[conversationId] || {});
    if (ids.length === 0) {
        el.classList.remove('show');
        return;
    }
    var names = ids.map(displayName);
    el.querySelector('.typingText').innerText = names.length === 1 ? names[0] + ' đang nhập' : names.join(', ') + ' đang nhập';
    el.classList.add('show');
}

function showLoginForm() {
    identityConfirmed = false;
    document.getElementById('mainApp').style.display = 'none';
    document.getElementById('authScreen').style.display = 'flex';
    setStatus('cần đăng nhập', 'bad');
}

function enterApp() {
    identityConfirmed = true;
    document.getElementById('authScreen').style.display = 'none';
    document.getElementById('mainApp').style.display = 'flex';
    renderWhoami();
    refreshUserList();
    refreshConversationList();
    loadNotifications(); // đồng bộ badge chuông thông báo ngay lúc vào app (xem history-ws.js)
    connect();
}

// --- danh sách hội thoại của bạn (bấm vào để mở) ---

// Bỏ chữ "trước" -- "23 phút trước" luôn bị ellipsis cắt trong cột giờ cố định bề rộng ở sidebar.
// Hôm nay -> giờ:phút, trong tuần -> tên thứ, còn lại -> ngày/tháng -- so theo NGÀY DƯƠNG LỊCH (00:00 local) như formatDateDivider().
var VN_WEEKDAY_SHORT = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
function pad2(n) { return String(n).padStart(2, '0'); }
function relativeTime(epochMillis) {
    if (!epochMillis) return 'Mới';
    var d = new Date(epochMillis);
    var now = new Date();
    var startOfDay = function (x) { return new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime(); };
    var diffDays = Math.round((startOfDay(now) - startOfDay(d)) / 86400000);
    if (diffDays === 0) return pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    if (diffDays > 0 && diffDays < 7) return VN_WEEKDAY_SHORT[d.getDay()];
    var datePart = pad2(d.getDate()) + '/' + pad2(d.getMonth() + 1);
    if (d.getFullYear() !== now.getFullYear()) datePart += '/' + String(d.getFullYear()).slice(-2);
    return datePart;
}

// Group nhiều thành viên nối hết bằng dấu phẩy có thể tràn thành khối chữ rất cao ở nơi không ellipsis (vd #infoPanelName) -- cap tối đa MAX_NAMES_IN_LABEL tên, còn lại "và N người khác" kiểu WhatsApp.
var MAX_NAMES_IN_LABEL = 3;
function joinNamesCapped(names) {
    if (names.length <= MAX_NAMES_IN_LABEL) return names.join(', ');
    return names.slice(0, MAX_NAMES_IN_LABEL).join(', ') + ' và ' + (names.length - MAX_NAMES_IN_LABEL) + ' người khác';
}

function conversationLabel(conv) {
    if (conv.name) return conv.name;
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length === 0) return 'Conversation ' + conv.conversationId.substring(0, 8) + '…';
    if (others.length === 1) return displayName(others[0]);
    return joinNamesCapped(others.map(displayName));
}

// lastMessage* đến từ GET /conversations, không cần gọi thêm API -- trả dạng {kind,...} để buildConvListItem tự quyết định hiển thị.
// getMessageFiles chuẩn hoá shape MỚI (body.files[]) và CŨ (body.fileUrl đơn, trước khi có multi-file) về cùng 1 mảng cho mọi nơi hiển thị dùng chung.
function getMessageFiles(body) {
    if (!body) return [];
    if (Array.isArray(body.files) && body.files.length) return body.files;
    if (body.fileUrl) return [{fileUrl: body.fileUrl, fileId: body.fileId, fileMime: body.fileMime, fileName: body.fileName}];
    return [];
}

function conversationLastMessagePreview(conv) {
    if (!conv.lastMessageFromUserId) return {kind: 'empty', text: 'Chưa có tin nhắn nào'};
    var isMine = conv.lastMessageFromUserId === myUserId;
    var isGroupConv = conv.memberUserIds.filter(function (id) { return id !== myUserId; }).length > 1;
    var prefix = isMine ? 'Bạn: ' : (isGroupConv ? displayName(conv.lastMessageFromUserId) + ': ' : '');
    if (conv.lastMessageDeleted) return {kind: 'text', text: prefix + 'Tin nhắn đã bị xoá'};
    var body = conv.lastMessageBody;
    if (!body) return {kind: 'text', text: prefix};
    var files = getMessageFiles(body);
    if (files.length) {
        var caption = body.message || '';
        if (files.length === 1) {
            var isVideo = (files[0].fileMime || '').indexOf('video/') === 0;
            return {kind: 'file', prefix: prefix, fileType: isVideo ? 'video' : 'image', label: isVideo ? '🎥 Video' : '🖼 Hình ảnh', caption: caption};
        }
        // Nhiều file gộp 1 tin (xem uploadAndSendFiles) -- đếm riêng ảnh/video, trộn lẫn thì gộp chung "📎 N tệp".
        var videoCount = files.filter(function (f) { return (f.fileMime || '').indexOf('video/') === 0; }).length;
        var imageCount = files.length - videoCount;
        var label;
        if (videoCount === 0) label = '🖼 ' + imageCount + ' ảnh';
        else if (imageCount === 0) label = '🎥 ' + videoCount + ' video';
        else label = '📎 ' + files.length + ' tệp';
        return {kind: 'file', prefix: prefix, fileType: 'group', label: label, caption: caption};
    }
    return {kind: 'text', text: prefix + messageBodyText(body)};
}

// Vá thẳng lastConvList (cache, không cần gọi API) rồi render lại -- trước đây sidebar chỉ cập nhật lúc GET /conversations, nên tin mới tới cho conversation KHÔNG đang mở vẫn hiện giờ/tin CŨ tới khi refetch.
function updateConvListEntryFromMessage(conversationId, fromUserId, body, tsEpochMillis, deleted) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv) return; // conversation chưa từng nạp qua GET /conversations (hiếm) -- lần refresh kế tiếp sẽ tự đúng
    conv.lastMessageAt = tsEpochMillis;
    conv.lastMessageFromUserId = fromUserId;
    conv.lastMessageDeleted = !!deleted;
    conv.lastMessageBody = deleted ? null : body;
    // Tăng tạm optimistic CHỈ khi tin của người khác và conversation KHÔNG đang mở (đang mở thì IntersectionObserver tự đánh dấu đã đọc ngay) -- server vẫn là nguồn đếm thật cuối cùng.
    if (!deleted && fromUserId !== myUserId && conversationId !== activeConversationId) {
        conv.unreadCount = (conv.unreadCount || 0) + 1;
    }
    // Đẩy hội thoại vừa có hoạt động lên đầu, khớp thứ tự server trả (ORDER BY COALESCE(last_message_at, conv_created_at) DESC).
    lastConvList = [conv].concat(lastConvList.filter(function (c) { return c !== conv; }));
    renderConversationList(lastConvList);
}

// Chỉ cần cho group -- DM thì label đã là tên người đó rồi; group đã đặt tên riêng vẫn cần dòng này để phân biệt thành viên giữa các group.
function membersSubtitle(conv) {
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length <= 1) return '';
    return joinNamesCapped(others.map(displayName));
}

// Lưu server (PUT /conversations) -- mọi thành viên đều thấy tên mới, khác bản đầu chỉ lưu localStorage riêng trình duyệt.
function renameConversation(conversationId) {
    var entry = conversations[conversationId];
    var current = entry ? entry.label : '';
    appPrompt('Tên hiển thị cho cuộc trò chuyện này (mọi thành viên đều thấy, để trống để xoá):', current, {title: 'Đổi tên cuộc trò chuyện', confirmText: 'Lưu', cancelText: 'Huỷ', placeholder: 'Nhập tên...'}).then(function(next){
    if (next === null) return;
    next = next.trim();
    fetch(HISTORY_API_BASE + '/conversations?conversationId=' + encodeURIComponent(conversationId), {
        method: 'PUT',
        headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken},
        body: JSON.stringify({name: next})
    })
        .then(function (res) {
            if (!res.ok) return res.json().then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
            return res.json();
        })
        .then(function (data) {
            if (entry) {
                var newLabel = data.name || (lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0] ? conversationLabel(Object.assign({}, lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0], {name: null})) : entry.label);
                entry.label = newLabel;
                entry.el.querySelector('.convLabel').innerText = newLabel;
            }
            refreshConversationList();
        })
        .catch(function (err) {
            appAlert('đổi tên lỗi: ' + err.message, 'Lỗi');
        });
    });
}

// Xoá HẲN cho mọi thành viên (không chỉ "rời khỏi") -- server broadcast CONVERSATION_DELETED để các thành viên khác đang online tự dọn UI.
function deleteConversation(conversationId) {
    var entry = conversations[conversationId];
    var label = entry ? entry.label : conversationId.substring(0, 8) + '…';
    appConfirm('Xoá hẳn "' + label + '"? Xoá cho TẤT CẢ thành viên, gồm toàn bộ tin nhắn -- không hoàn tác được.', {title: 'Xoá cuộc trò chuyện', confirmText: 'Xoá', cancelText: 'Huỷ', danger: true}).then(function(ok){ if(!ok) return;
    fetch(HISTORY_API_BASE + '/conversations?conversationId=' + encodeURIComponent(conversationId), {
        method: 'DELETE',
        headers: {'Authorization': 'Bearer ' + authToken}
    })
        .then(function (res) {
            if (!res.ok) return res.json().then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
            removeConversationLocally(conversationId);
        })
        .catch(function (err) {
            appAlert('xoá lỗi: ' + err.message, 'Lỗi');
        });
    });
}

// Dùng chung cho cả 2 đường: tự mình xoá (sau khi server xác nhận) và người khác xoá (nhận qua WS CONVERSATION_DELETED) -- không gọi thêm API ở đây.
function removeConversationLocally(conversationId) {
    var entry = conversations[conversationId];
    if (entry) {
        entry.el.remove();
        delete conversations[conversationId];
    }
    if (activeConversationId === conversationId) {
        activeConversationId = null;
        document.getElementById('chatEmpty').style.display = 'flex';
        renderInfoPanel(null);
        goBackToSidebar(); // hội thoại đang xem vừa biến mất -- màn hình hẹp thì quay lại danh sách, không kẹt ở khung chat rỗng
    }
    lastConvList = lastConvList.filter(function (c) { return c.conversationId !== conversationId; });
    renderConversationList(lastConvList);
}

function refreshConversationList() {
    fetch(HISTORY_API_BASE + '/conversations', {headers: {'Authorization': 'Bearer ' + authToken}})
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (list) {
            lastConvList = list;
            renderConversationList(list);
            Object.keys(conversations).forEach(updateConvHeadPresence);
            refreshPresenceSnapshot(collectRelevantUserIdsForPresence());
        })
        .catch(function (err) {
            console.warn('không load được danh sách hội thoại', err);
        });
}

// Tách riêng khỏi renderConversationList() để dùng chung cho cả 2 khối DM/Nhóm (xem renderConvSection).
function buildConvListItem(conv) {
    var label = conversationLabel(conv);
    var subtitle = membersSubtitle(conv);
    var unreadCount = conv.unreadCount || 0;
    var isUnread = unreadCount > 0;
    var avatar = conversationAvatar(conv);
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    var isOnline = others.length === 1 && !!onlineUserIds[others[0]];
    var item = document.createElement('div');
    item.className = 'conv-list-item' + (conv.conversationId === activeConversationId ? ' active' : '') + (isUnread ? ' unread' : '');
    item.innerHTML =
        '<span class="avatar-wrap"><span class="avatar"></span><span class="status-dot"></span></span>' +
        '<span class="info"><div class="convTopRow"><span class="label"></span><span class="time"></span></div><div class="meta"></div></span>' +
        '<span class="unreadBadge"></span>';
    var avatarEl = item.querySelector('.avatar');
    applyAvatar(avatarEl, avatar);
    item.querySelector('.status-dot').classList.toggle('online', isOnline);
    item.querySelector('.label').innerText = label;
    item.querySelector('.time').innerText = relativeTime(conv.lastMessageAt);
    var metaEl = item.querySelector('.meta');
    var preview = conversationLastMessagePreview(conv);
    if (preview.kind === 'file') {
        if (preview.prefix) {
            var prefixEl = document.createElement('span');
            prefixEl.className = 'metaPrefix';
            prefixEl.innerText = preview.prefix;
            metaEl.appendChild(prefixEl);
        }
        var pillEl = document.createElement('span');
        pillEl.className = 'metaPill ' + preview.fileType;
        pillEl.innerText = preview.label;
        metaEl.appendChild(pillEl);
        if (preview.caption) {
            var captionEl = document.createElement('span');
            captionEl.className = 'metaCaption';
            captionEl.textContent = preview.caption; // .textContent không phải .innerText -- caption nhiều dòng, xem .metaText bên dưới
            metaEl.appendChild(captionEl);
        }
    } else {
        // Span riêng + flex:1;min-width:0 (.metaText) -- ellipsis áp thẳng lên text node con của flex container không đáng tin cậy.
        // .textContent không phải .innerText -- Chrome tự chuyển "\n" trong .innerText thành <br> thật, ép xuống dòng bất kể white-space:nowrap.
        var textEl = document.createElement('span');
        textEl.className = 'metaText';
        textEl.textContent = preview.text;
        metaEl.appendChild(textEl);
    }
    // Đếm số thật (không rút gọn "99+") -- tránh cảm giác "kẹt số" khi đang giảm dần.
    if (isUnread) item.querySelector('.unreadBadge').innerText = String(unreadCount);
    item.onclick = function () { openConversation(conv.conversationId, label, subtitle); };

    // Đồng bộ tiêu đề card đã mở -- lúc CONVERSATION_ADDED chỉ biết id nên tạm hiện "Mới: <id rút gọn>", tự sửa đúng khi list này (có memberUserIds) về.
    var entry = conversations[conv.conversationId];
    if (entry) {
        if (entry.label !== label) {
            entry.label = label;
            var labelEl = entry.el.querySelector('.convLabel');
            if (labelEl) labelEl.innerText = label;
        }
        var subtitleEl = entry.el.querySelector('.subtitle');
        if (subtitleEl && subtitleEl.innerText !== subtitle) subtitleEl.innerText = subtitle;
    }
    return item;
}

// 1 khối kiểu "Direct messages"/"Channels" (Slack) -- ẩn hẳn nếu rỗng, không hiện tiêu đề suông.
function renderConvSection(container, title, icon, list) {
    if (!list.length) return;
    var section = document.createElement('div');
    section.className = 'sidebarSection';
    section.innerHTML = '<div class="sidebarSectionHead"><span class="sectionIcon">' + icon + '</span><span>' + title + '</span></div>';
    list.forEach(function (conv) { section.appendChild(buildConvListItem(conv)); });
    container.appendChild(section);
}

// Gắn sao 1 cuộc trò chuyện -- thuần tuỳ biến RIÊNG của người xem (giống getConversationBackground),
// không phải dữ liệu chung của cả cuộc trò chuyện nên không cần lưu server/đồng bộ giữa các thành
// viên, lưu thẳng localStorage là đủ (mất khi đổi trình duyệt/máy, chấp nhận được).
var STARRED_STORAGE_KEY = 'pingoStarredConversations_v1';
function loadStarredSet() {
    try { return JSON.parse(localStorage.getItem(STARRED_STORAGE_KEY)) || {}; } catch (e) { return {}; }
}
function isConversationStarred(conversationId) {
    return !!loadStarredSet()[conversationId];
}
function setConversationStarred(conversationId, starred) {
    var set = loadStarredSet();
    if (starred) set[conversationId] = true; else delete set[conversationId];
    try { localStorage.setItem(STARRED_STORAGE_KEY, JSON.stringify(set)); } catch (e) { /* gắn sao chỉ là tiện ích phụ -- bỏ qua lặng lẽ nếu localStorage đầy */ }
}
// Gọi từ .starBtn trong conv-head (xem ensureConversationCard) -- cập nhật cả nút vừa bấm lẫn vẽ lại sidebar.
function toggleConversationStarred(conversationId) {
    var starred = !isConversationStarred(conversationId);
    setConversationStarred(conversationId, starred);
    var entry = conversations[conversationId];
    if (entry) {
        var btn = entry.el.querySelector('.starBtn');
        btn.classList.toggle('active', starred);
        btn.title = starred ? 'Bỏ gắn sao' : 'Gắn sao';
    }
    renderConversationList(lastConvList);
}

// Chỉ tách 2 khối: "Đã gắn sao" (nổi lên đầu) và phần còn lại (KHÔNG còn tách riêng DM/Nhóm nữa --
// gộp chung 1 danh sách, đúng thứ tự hoạt động gần đây như server trả về).
// Ô tìm kiếm luôn hiện sẵn, thay nút kính lúp cũ vốn không làm gì (đã bỏ, cùng kiểu "nút chết" ở ô nhập tin).
var conversationSearchTerm = '';
function renderConversationList(list) {
    var container = document.getElementById('conversationList');
    container.innerHTML = '';
    var term = conversationSearchTerm.trim().toLowerCase();
    var filtered = term ? list.filter(function (conv) { return conversationLabel(conv).toLowerCase().indexOf(term) !== -1; }) : list;
    var hintEl = document.getElementById('noConversationsHint');
    hintEl.style.display = filtered.length ? 'none' : '';
    hintEl.innerText = term
        ? 'Không tìm thấy hội thoại nào khớp "' + conversationSearchTerm.trim() + '".'
        : 'Chưa có cuộc hội thoại nào — tạo mới ở trên, hoặc chờ ai đó nhắn cho bạn.';
    var starred = [], rest = [];
    filtered.forEach(function (conv) {
        (isConversationStarred(conv.conversationId) ? starred : rest).push(conv);
    });
    renderConvSection(container, 'Đã gắn sao', ICON.star, starred);
    rest.forEach(function (conv) { container.appendChild(buildConvListItem(conv)); });
}
document.getElementById('conversationSearchInput').addEventListener('input', function (e) {
    conversationSearchTerm = e.target.value;
    renderConversationList(lastConvList);
});

// Không cần tự SUBSCRIBE -- harbor đã tự wake-subscribe hết mọi conversation ngay sau AUTH (xem autoSubscribeAllConversations bên harbor).
function openConversation(conversationId, label, subtitle) {
    ensureConversationCard(conversationId, label, subtitle);
    selectConversation(conversationId);
}

// Card không bị huỷ khi ẩn (chỉ display:none) -- scroll position + draft dở của conversation khác vẫn giữ nguyên khi quay lại.
function selectConversation(conversationId) {
    var entry = conversations[conversationId];
    if (!entry) return;
    if (activeConversationId && activeConversationId !== conversationId && conversations[activeConversationId]) {
        var prevEntry = conversations[activeConversationId];
        prevEntry.el.classList.remove('active');
        // display:none không tự dừng video/audio đang phát (hành vi mặc định trình duyệt) -- phải chủ động pause.
        prevEntry.el.querySelectorAll('video, audio').forEach(function (mediaEl) { mediaEl.pause(); });
        // Reaction picker gắn vào document.body (xem ensureReactionPicker) nên không tự ẩn theo display:none của .conv -- đóng thủ công.
        closeReactionPicker();
    }
    activeConversationId = conversationId;
    entry.el.classList.add('active');
    // Đo lại mốc chiều cao .conv-send ngay lúc card vừa hiện ra thật -- chi tiết xem resetComposeSendBaseline/composeResizeObserver.
    entry.resetComposeSendBaseline();
    document.getElementById('chatEmpty').style.display = 'none';
    // Xoá badge unread ngay để phản hồi tức thì -- server tự tính lại đúng ở lần refresh kế tiếp.
    var openedConv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (openedConv) openedConv.unreadCount = 0;
    renderConversationList(lastConvList); // cập nhật highlight "active" + xoá badge unread trong sidebar
    renderInfoPanel(conversationId);
    loadHistory(conversationId);
    // Chỉ áp lại vị trí cuộn ở lần ĐẦU TIÊN hiện ra thật (đã có layout, xem applyScrollAnchor) -- lần mở lại sau giữ nguyên scrollTop người dùng đang dừng.
    if (!entry.everActivated) {
        entry.everActivated = true;
        applyScrollAnchor(entry);
    }
    // Màn hình hẹp: sidebar/khung chat không hiện đồng thời -- mở hội thoại tự chuyển sang xem chat (xem CSS #layout.mobileChatOpen).
    document.getElementById('layout').classList.add('mobileChatOpen');
    var input = entry.el.querySelector('.composeInput');
    if (input && !isNarrowViewport()) input.focus(); // đừng tự bật bàn phím ảo ngay khi vừa vào màn hình chat trên điện thoại
}

// Chỉ có ý nghĩa ở màn hình hẹp (xem .backBtn) -- không đổi activeConversationId, chỉ đổi panel nào đang hiện.
function goBackToSidebar() {
    document.getElementById('layout').classList.remove('mobileChatOpen');
}

function isNarrowViewport() {
    return window.matchMedia('(max-width: 680px)').matches;
}

