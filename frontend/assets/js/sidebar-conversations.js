// Tên hiển thị cho 1 userId — username đã biết nếu có, ngược lại rút gọn UUID (8 ký tự đầu).
function displayName(userId) {
    return usernameById[userId] || (userId.substring(0, 8) + '…');
}

// Màu avatar ổn định theo id (hash chuỗi -> hue) -- cùng 1 người luôn ra cùng 1 màu ở mọi nơi hiển
// thị (sidebar, bong bóng chat...), không cần lưu màu ở đâu cả.
// Trước đây random theo HSL LIÊN TỤC (hash % 360) -- ra đủ mọi màu có thể, nhìn như cầu vồng, phá vỡ
// cảm giác "sạch/1 bảng màu nhất quán" của app. Giờ chỉ chọn trong 1 BẢNG MÀU CỐ ĐỊNH đã chọn lọc
// (cùng độ đậm/nhạt, hài hoà với tím accent chính) -- vẫn ổn định theo id (cùng người luôn cùng màu),
// chỉ khác là giới hạn trong 1 tập nhìn "có chủ đích" thay vì random vô tội vạ.
var AVATAR_PALETTE = ['#6d5bf5', '#3b82c4', '#14919b', '#2f9b6e', '#c08a2e', '#d0665a', '#b0538f', '#5f6b85'];
function avatarColor(id) {
    var hash = 0;
    for (var i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) >>> 0;
    return AVATAR_PALETTE[hash % AVATAR_PALETTE.length];
}

function avatarInitial(name) {
    return (name || '?').trim().charAt(0).toUpperCase() || '?';
}

// Avatar đại diện cho CẢ 1 conversation (dùng ở sidebar) -- DM thì lấy avatar của người kia, group
// (>=2 người khác) dùng icon chung (không có 1 "khuôn mặt" đại diện tự nhiên cho nhiều người).
function conversationAvatar(conv) {
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length === 1) {
        return {color: avatarColor(others[0]), initial: avatarInitial(displayName(others[0]))};
    }
    // Nhóm (>=2 người khác): trước đây nền xám xịt + emoji 👥 lệch tông hẳn với bộ icon outline
    // đồng bộ đã dùng khắp nơi -- đổi sang màu accent của app (tím) + icon outline "users" cùng bộ.
    return {color: 'var(--accent)', icon: ICON.users};
}

// Gán avatar (màu nền + hoặc chữ cái đầu, hoặc icon SVG cho nhóm) vào 1 phần tử -- dùng chung cho
// mọi nơi hiện avatar cuộc trò chuyện (sidebar, đầu khung chat, panel Info), tránh lặp lại nhánh
// if/else "initial hay icon" ở từng chỗ gọi.
function applyAvatar(el, avatar) {
    el.style.background = avatar.color;
    if (avatar.icon) el.innerHTML = avatar.icon; else el.innerText = avatar.initial;
}

function formatTime(tsEpochMillis) {
    if (!tsEpochMillis) return '';
    var d = new Date(tsEpochMillis);
    var hh = String(d.getHours()).padStart(2, '0');
    var mm = String(d.getMinutes()).padStart(2, '0');
    return hh + ':' + mm;
}

// --- presence (online/offline) ---

// Lấy snapshot online/offline ban đầu cho 1 danh sách userId (GET /presence, herald) -- gọi sau khi
// biết knownUsers (WS PRESENCE chỉ báo lúc THAY ĐỔI về sau, không tự biết trạng thái ban đầu).
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

// Nhận frame PRESENCE qua WS (thay đổi real-time) -- cập nhật state cục bộ rồi vẽ lại MỌI chỗ có
// hiện trạng thái (sidebar avatar dot + conv-head đang mở), không đợi người dùng tự bấm gì cả.
function handlePresenceChange(userId, online) {
    onlineUserIds[userId] = online;
    renderConversationList(lastConvList);
    Object.keys(conversations).forEach(updateConvHeadPresence);
}

// Chỉ hiện "Đang hoạt động" cho DM (đúng 1 người khác) -- group nhiều người không có 1 trạng thái
// online/offline đại diện chung hợp lý, bỏ qua cho đơn giản.
function updateConvHeadPresence(conversationId) {
    var entry = conversations[conversationId];
    var statusEl = entry && entry.el.querySelector('.online-status');
    if (!statusEl) return;
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    var others = conv ? conv.memberUserIds.filter(function (id) { return id !== myUserId; }) : [];
    var online = others.length === 1 && !!onlineUserIds[others[0]];
    statusEl.classList.toggle('show', online);
    statusEl.innerText = online ? '● Đang hoạt động' : '';
    // Avatar đầu khung chat -- chỉ vẽ được khi đã biết thành viên thật (conv từ lastConvList), lúc
    // card vừa tạo (vd CONVERSATION_ADDED) có thể chưa có, sẽ tự cập nhật ở lần refreshConversationList kế tiếp.
    if (conv) {
        var avatarEl = entry.el.querySelector('.conv-head .avatar');
        var avatar = conversationAvatar(conv);
        applyAvatar(avatarEl, avatar);
        // "#" trước tên NHÓM (không phải DM) -- đúng quy ước kênh nhóm trong ảnh tham khảo
        // ("# Website", "# Front-end"...), DM thì không có tiền tố vì đã có avatar người đó đại diện rồi.
        entry.el.querySelector('.conv-head').classList.toggle('group', others.length > 1);
    }
    // Panel thông tin/thành viên bên phải chỉ theo dõi ĐÚNG conversation đang mở -- gọi ké ở đây vì
    // hàm này vốn đã được gọi lại đúng lúc cần (chọn conversation, đổi presence, refresh list...).
    if (conversationId === activeConversationId) renderInfoPanel(conversationId);
}

// --- panel thông tin/thành viên bên phải (kiểu Slack/Discord "Details") ---

// Ở màn hình đủ rộng (>1000px), #infoPanel là 1 CỘT riêng nằm trong layout -- mặc định mở sẵn
// (infoPanelVisible=true) là hợp lý, giống Slack/Discord desktop. NHƯNG dưới 1000px, CSS đổi nó
// thành 1 drawer NỔI ĐÈ full màn hình (position:fixed, xem @media max-width:1000px) -- nếu vẫn giữ
// mặc định "mở sẵn" y hệt desktop thì mọi lần mở app trên điện thoại/tablet đều bị panel này che kín
// toàn bộ sidebar+khung chat NGAY TỪ ĐẦU, phải tự bấm ✕ mới thấy được gì cả (bug responsive thật đã
// gặp, xác nhận qua chụp màn hình thật ở nhiều độ rộng). Query khớp CHÍNH XÁC breakpoint CSS đó.
var infoPanelDrawerQuery = window.matchMedia('(max-width: 1000px)');
var infoPanelVisible = !infoPanelDrawerQuery.matches;
document.getElementById('infoPanel').classList.toggle('collapsed', !infoPanelVisible);
// Thu nhỏ cửa sổ trình duyệt (hoặc xoay ngang->dọc) từ rộng xuống drawer-mode trong lúc panel đang
// mở cũng dính ĐÚNG bug y hệt -- tự đóng lại khi vừa chuyển sang chế độ hẹp, không đợi resize xong
// mới lộ ra đã bị che kín. Không tự MỞ lại khi resize ngược lại rộng ra -- tôn trọng lựa chọn ẩn/hiện
// người dùng đã tự bấm, chỉ tự động ở chiều "tránh che kín màn hình" thôi.
infoPanelDrawerQuery.addEventListener('change', function (e) {
    if (e.matches && infoPanelVisible) toggleInfoPanel();
});
function toggleInfoPanel() {
    infoPanelVisible = !infoPanelVisible;
    document.getElementById('infoPanel').classList.toggle('collapsed', !infoPanelVisible);
}

// Vẽ lại avatar/tên/danh sách thành viên cho ĐÚNG 1 conversationId (hoặc null -- chưa chọn gì) --
// cần conv.memberUserIds thật từ lastConvList (GET /conversations), không suy được từ mỗi
// conversationId suông. Sắp online lên trước, offline xuống dưới cho dễ nhìn (giống Slack/Discord).
// Tab đang xem trong panel bên phải -- 'info'/'files'/'pins'/'links'. Giữ nguyên khi chuyển qua lại
// giữa các conversation (đúng thói quen thật: đang xem tab Files thì mở conversation khác cũng
// muốn thấy Files của nó).
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

    var avatar = conversationAvatar(conv);
    var avatarEl = document.getElementById('infoPanelAvatar');
    applyAvatar(avatarEl, avatar);
    document.getElementById('infoPanelName').innerText = conversationLabel(conv);
    document.getElementById('infoPanelSubtitle').innerText = conv.memberUserIds.length + ' thành viên';
    document.getElementById('infoMemberCount').innerText = conv.memberUserIds.length;
    document.getElementById('infoMemberCountRow').innerText = conv.memberUserIds.length;
    document.getElementById('infoConvIdValue').innerText = conv.conversationId.substring(0, 8) + '…';
    var isGroupConv = conv.memberUserIds.filter(function (id) { return id !== myUserId; }).length > 1;
    document.getElementById('infoTypeValue').innerText = isGroupConv ? 'Nhóm' : 'Nhắn tin trực tiếp';
    document.getElementById('infoActivityValue').innerText = relativeTime(conv.lastMessageAt);

    var sorted = conv.memberUserIds.slice().sort(function (a, b) {
        var aOnline = a === myUserId || !!onlineUserIds[a];
        var bOnline = b === myUserId || !!onlineUserIds[b];
        return (bOnline ? 1 : 0) - (aOnline ? 1 : 0);
    });
    // "Trạng thái" tổng quan: có bất kỳ ai khác (ngoài mình) đang online trong hội thoại này không --
    // vẽ dạng chấm + chữ (.infoStatusVal) ĐỒNG BỘ với danh sách thành viên bên dưới, không dùng pill
    // xanh/vàng kiểu topbar (làm khối Thông tin chính lệch tông, rối mắt).
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
        var a = row.querySelector('.avatar');
        a.style.background = avatarColor(id);
        a.innerText = avatarInitial(displayName(id));
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

// Tab "Files" -- liệt kê lại ảnh/video đã từng gửi trong ĐÚNG conversation này, mới nhất trước. Dữ
// liệu lấy thẳng từ bảng files (đã gắn conversationId ngay lúc upload, xem uploadAndSendFile +
// FileRegistry#listForConversation) -- không cần dò lại lịch sử tin nhắn.
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

// Lưới ảnh/video kiểu thư viện (app chỉ nhận upload image/video, xem accept="image/*,video/*" ở
// compose -- không có file tài liệu nào khác cần hiện tên/dung lượng dạng danh sách). Bấm vào mở
// thẳng lightbox có sẵn (prev/next lướt hết mọi file), thay vì window.open ra tab mới trơ trọi.
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
        // Ảnh THẬT dùng thẳng file gốc; video dùng thumbnail (khung hình giây thứ 5, xem
        // get-thumbnail.lua) -- không tải cả video chỉ để hiện 1 ảnh nhỏ trong lưới.
        item.querySelector('img').src = isVideo
            ? FILE_SERVER_BASE + '/v2/api/thumbnail?id=' + encodeURIComponent(f.id) + '&size=200x200'
            : lightboxFilesForList[index].fileUrl;
        item.querySelector('.mediaGridMeta').innerText = displayName(f.fromUserId) + ' · ' + relativeTime(f.ts);
        item.onclick = function () { openMediaLightbox(lightboxFilesForList, index); };
        listEl.appendChild(item);
    });
}

// --- tab "Pins" ---

// Tin đã ghim (chung của cả conversation + riêng của CHÍNH MÌNH, server tự lọc -- xem GET /pins,
// MessagePinRegistry#listPins) -- cần auth vì ghim riêng là dữ liệu riêng của từng user.
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
            // Optimistic -- ghim riêng không có frame PIN nào bay về xác nhận (xem handlePinReceived),
            // ghim chung thì frame bay về sẽ tự refresh lại list nếu tab vẫn đang mở.
            item.remove();
        };
        listEl.appendChild(item);
    });
}

// --- tab "Links" ---

// Link đã trích từ nội dung tin nhắn (chung cho cả conversation, không riêng user nào -- xem GET
// /links, MessageLinkRegistry#listForConversation).
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

// Nhận frame TYPING qua WS -- hẹn giờ tự hết hạn cho ĐÚNG (conversationId, fromUserId) đó, gõ tiếp
// thì reset lại giờ (server không có frame "đã dừng gõ" riêng, suy bằng timeout là đủ dùng).
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
    var whoamiEl = document.getElementById('whoami');
    whoamiEl.innerHTML = '<span class="avatar"></span><span class="name"></span>';
    var whoamiAvatar = whoamiEl.querySelector('.avatar');
    whoamiAvatar.style.background = avatarColor(myUserId);
    whoamiAvatar.innerText = avatarInitial(myUsername);
    whoamiEl.querySelector('.name').innerText = myUsername;
    refreshUserList();
    refreshConversationList();
    loadNotifications(); // đồng bộ badge chuông thông báo ngay lúc vào app (xem history-ws.js)
    connect();
}

// --- danh sách hội thoại của bạn (bấm vào để mở) ---

// Bỏ chữ "trước" -- "23 phút trước" quá dài, luôn bị ellipsis cắt xén trong cột giờ cố định bề rộng
// ở sidebar (nhìn "phèn"/cẩu thả) dù có nới rộng cột tới đâu cũng có lúc không đủ -- "23 phút" ngắn
// gọn, không bao giờ cần cắt, vẫn đủ hiểu trong ngữ cảnh danh sách hội thoại.
// Đúng kiểu Telegram thật (ảnh người dùng gửi tham khảo): hôm nay -> giờ:phút, trong tuần (chưa tới
// 7 ngày, không phải hôm nay) -> tên thứ viết tắt, còn lại -> ngày/tháng (kèm /năm nếu khác năm hiện
// tại) -- so theo NGÀY DƯƠNG LỊCH thật (00:00 local), không phải "cách nhau bao nhiêu giờ", cùng
// cách formatDateDivider() đang làm cho vạch ngăn ngày trong khung chat.
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

// Ưu tiên tên đặt riêng lưu server (conv.name, xem renameConversation) nếu có. Không thì: DM (đúng
// 1 người khác ngoài mình) hiện tên người đó; Group (nhiều/không ai khác) liệt kê tên các thành
// viên còn lại, hoặc rút gọn conversationId nếu chỉ có mình mình (vd group rỗng).
// Group nhiều thành viên (nhất là tài khoản test tên dài dạng "orderTestC-1788623614934") nối hết
// bằng dấu phẩy có thể ra 1 chuỗi cực dài -- cắt CSS (ellipsis) chỉ đỡ phần hiển thị 1 dòng, còn chỗ
// cho phép xuống dòng tự do (vd #infoPanelName) thì vẫn tràn thành khối chữ rất cao/rối. Sửa tận gốc:
// chỉ nối tối đa MAX_NAMES_IN_LABEL tên đầu, còn lại rút gọn "và N người khác" -- đúng kiểu
// WhatsApp/Messenger đặt tên group tự động, luôn ngắn gọn bất kể group có bao nhiêu người.
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

// Xem trước tin nhắn gần nhất cho sidebar -- lastMessageBody/lastMessageFromUserId/lastMessageDeleted
// đến từ GET /conversations (xem ConversationMembershipRegistry#listConversationsForUser), KHÔNG
// cần gọi thêm API nào khác. "Bạn: " cho tin của chính mình, "Tên: " cho tin nhóm của người khác, DM
// thì không cần tiền tố (đã biết đang chat với đúng 1 người đó rồi, lặp lại tên vô ích).
// Trả về DẠNG CÓ CẤU TRÚC (không phải chuỗi cuối) -- kind: 'empty'|'text'|'file', để
// buildConvListItem tự quyết định vẽ chữ thường hay chèn thêm pill màu cho file (ảnh/video, xem CSS
// .metaPill) thay vì nhét emoji thẳng vào 1 chuỗi chữ xám đơn điệu như trước.
// Chuẩn hoá danh sách file của 1 tin về CÙNG 1 dạng mảng [{fileUrl, fileId, fileMime, fileName}, ...]
// dù tin đó dùng shape MỚI (body.files, gửi nhiều file gộp 1 tin -- xem uploadAndSendFiles) hay shape
// CŨ (body.fileUrl đơn lẻ, tin nhắn file từ TRƯỚC khi có tính năng multi-file) -- để mọi chỗ hiển thị
// (bong bóng chat, preview sidebar, lightbox) chỉ cần viết đúng 1 đường xử lý, không phải rẽ nhánh
// theo shape ở từng nơi.
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
        // Nhiều file GỘP trong 1 tin (xem uploadAndSendFiles) -- đếm riêng ảnh/video, hiện gọn kiểu
        // "🖼 3 ảnh" nếu chỉ toàn 1 loại, trộn lẫn ảnh+video thì hiện chung "📎 N tệp" cho đơn giản.
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

// Cập nhật NGAY "tin nhắn gần nhất"/"giờ" trong sidebar theo đúng tin VỪA NHẬN qua WS -- trước đây
// chỗ này chỉ được làm mới khi gọi lại nguyên GET /conversations (refreshConversationList, chỉ chạy
// lúc SUBSCRIBE_OK/CONVERSATION_ADDED), nên tin sống tới trong lúc đang mở app cứ hiện trong khung
// chat nhưng sidebar vẫn hiện tin/giờ CŨ tới khi có dịp fetch lại -- sai lệch rõ nhất khi tin đó cho
// 1 conversation KHÔNG đang mở. Sửa bằng cách vá thẳng vào lastConvList (cache đã có, không cần gọi
// thêm API) rồi render lại, giống hệt field mà GET /conversations trả (xem
// ConversationMembershipRegistry#listConversationsForUser) để conversationLastMessagePreview() dùng
// lại được nguyên logic, không cần viết thêm 1 đường hiển thị riêng.
function updateConvListEntryFromMessage(conversationId, fromUserId, body, tsEpochMillis, deleted) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv) return; // conversation chưa từng nạp qua GET /conversations (hiếm) -- lần refresh kế tiếp sẽ tự đúng
    conv.lastMessageAt = tsEpochMillis;
    conv.lastMessageFromUserId = fromUserId;
    conv.lastMessageDeleted = !!deleted;
    conv.lastMessageBody = deleted ? null : body;
    // Tăng tạm ngay conv.unreadCount (không đợi round-trip GET /conversations) -- CHỈ khi tin của
    // NGƯỜI KHÁC và conversation này KHÔNG đang mở (đang mở thì gần như ngay lập tức được
    // IntersectionObserver đánh dấu đã đọc thật, xem ensureConversationCard, tăng rồi lại giảm ở đây
    // chỉ gây nhấp nháy vô ích). Server (GET /conversations, xem
    // ConversationMembershipRegistry#listConversationsForUser) vẫn là nguồn ĐẾM THẬT cuối cùng --
    // lệch tạm vài giây (nếu có) sẽ tự sửa đúng ở lần refreshConversationList()/reload kế tiếp.
    if (!deleted && fromUserId !== myUserId && conversationId !== activeConversationId) {
        conv.unreadCount = (conv.unreadCount || 0) + 1;
    }
    // Đẩy hội thoại VỪA CÓ HOẠT ĐỘNG lên đầu danh sách -- khớp đúng thứ tự server vẫn trả
    // (ORDER BY COALESCE(last_message_at, conv_created_at) DESC), không để nguyên vị trí cũ trong
    // lúc nội dung đã là mới nhất.
    lastConvList = [conv].concat(lastConvList.filter(function (c) { return c !== conv; }));
    renderConversationList(lastConvList);
}

// Dòng phụ hiện DANH SÁCH THÀNH VIÊN bên dưới tên -- chỉ cần cho group (>=2 người khác ngoài mình):
// DM chỉ có 1 người khác thì label đã chính là tên người đó rồi, thêm dòng phụ chỉ lặp lại vô ích.
// Group ĐÃ đặt tên riêng vẫn cần dòng này -- đặt tên xong không có nghĩa là quên luôn ai đang ở
// trong đó, nhất là group nhiều thành viên dễ nhầm giữa các group cùng vài người quen thuộc.
function membersSubtitle(conv) {
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length <= 1) return '';
    return joinNamesCapped(others.map(displayName));
}

// Đổi tên riêng cho 1 conversationId -- lưu server (PUT /conversations), MỌI thành viên đều thấy
// tên mới (khác bản đầu chỉ lưu localStorage riêng từng trình duyệt) -- gọi từ nút ✎ trong conv-head.
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

// Xoá HẲN conversation cho MỌI thành viên (không phải chỉ "rời khỏi") -- gọi DELETE /conversations
// (hall), server xoá messages/notifications/membership liên quan trong DB rồi broadcast cho các
// thành viên khác đang online tự dọn UI ngay (xem case "CONVERSATION_DELETED" trong ws.onmessage).
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

// Dọn hết dấu vết 1 conversationId khỏi UI cục bộ -- dùng chung cho cả 2 đường: (1) tự mình xoá
// (deleteConversation, ngay sau khi server xác nhận), (2) người khác xoá, mình chỉ nhận được thông
// báo qua WS (case "CONVERSATION_DELETED"). Không gọi API xoá gì thêm ở đây -- server đã xoá xong DB rồi.
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

// Dựng 1 dòng trong sidebar cho 1 conversation -- tách riêng khỏi renderConversationList() để dùng
// chung cho cả 2 khối "Nhắn tin trực tiếp"/"Nhóm" (xem renderConvSection).
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
    // Xem trước tin nhắn gần nhất ("Bạn: ...", "Tên: ..." cho nhóm, pill màu "Hình ảnh"/"Video" cho
    // file) -- trước đây chỗ này chỉ hiện mỗi giờ tương đối, danh sách nhìn trơ trọi không biết ai
    // vừa nhắn gì, đây chính là thứ khiến sidebar "nhìn cứ bình thường" thiếu sức sống.
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
            captionEl.textContent = preview.caption; // .textContent (không phải .innerText) -- caption có thể nhiều dòng nếu người gửi đính kèm file kèm chú thích dài, cùng lý do đã sửa ở .metaText
            metaEl.appendChild(captionEl);
        }
    } else {
        // Bọc trong 1 <span> RIÊNG (không gán thẳng .innerText lên .meta) -- .meta là flex container,
        // text-overflow:ellipsis áp lên 1 text node con trần trụi của flex container không đáng tin
        // cậy (tuỳ engine trình duyệt) dù đã khai nowrap/overflow. Span riêng + flex:1; min-width:0
        // (xem CSS .metaText) là cách chắc chắn, giống hệt .metaCaption đã dùng cho nhánh file.
        //
        // .textContent (KHÔNG PHẢI .innerText) -- lý do THẬT của bug "tin nhiều dòng vẫn xuống dòng
        // trong sidebar dù CSS nowrap đã đúng": .innerText tự Ý CHUYỂN mọi ký tự "\n" trong chuỗi gán
        // vào thành thẻ <br> THẬT trong DOM (hành vi riêng của Chrome, không phải chỉ giữ nguyên ký tự
        // xuống dòng trong 1 text node) -- <br> LUÔN ngắt dòng bất kể white-space là gì (nowrap chỉ
        // chặn ngắt dòng do TRÀN CHỮ, không chặn được thẻ <br> tường minh). .textContent thì giữ
        // nguyên "\n" như 1 ký tự trong text node (không parse ra <br>), lúc đó nowrap mới thật sự
        // gộp nó thành 1 khoảng trắng như kỳ vọng.
        var textEl = document.createElement('span');
        textEl.className = 'metaText';
        textEl.textContent = preview.text;
        metaEl.appendChild(textEl);
    }
    // Badge tròn đếm ĐÚNG số thật (không chỉ 1 chấm trơn như trước, cũng không rút gọn kiểu "99+" --
    // cùng lý do đã áp dụng cho chấm đỏ trong khung chat: hiển thị chính xác, không tạo cảm giác
    // "kẹt số" khi đang giảm dần) -- thay hẳn ".dot" cũ (chỉ báo có/không, không nói lên bao nhiêu).
    if (isUnread) item.querySelector('.unreadBadge').innerText = String(unreadCount);
    item.onclick = function () { openConversation(conv.conversationId, label, subtitle); };

    // Đồng bộ luôn tiêu đề card (nếu đã mở) với tên/thành viên vừa tính đúng -- vd lúc
    // CONVERSATION_ADDED chỉ biết conversationId (chưa biết thành viên là ai) nên tạm hiện "Mới:
    // <id rút gọn>", list này (từ GET /conversations, có đủ memberUserIds) tự sửa lại ngay khi có.
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

// 1 khối trong sidebar kiểu "Direct messages"/"Channels" (Slack) -- tiêu đề hoa nhỏ + danh sách,
// ẩn hẳn khối nếu rỗng (không hiện tiêu đề suông không có gì bên dưới).
function renderConvSection(container, title, icon, list) {
    if (!list.length) return;
    var section = document.createElement('div');
    section.className = 'sidebarSection';
    section.innerHTML = '<div class="sidebarSectionHead"><span class="sectionIcon">' + icon + '</span><span>' + title + '</span></div>';
    list.forEach(function (conv) { section.appendChild(buildConvListItem(conv)); });
    container.appendChild(section);
}

// Tách "Nhắn tin trực tiếp" (đúng 1 người khác -- DM) và "Nhóm" (nhiều người khác) thành 2 khối
// riêng trong sidebar -- giống cách Slack tách "Direct messages"/"Channels", và map ĐÚNG 1-1 với
// khái niệm DM/Group đã có sẵn trong model dữ liệu thật (không phải mục trang trí suông).
// Lọc theo tên -- ô tìm kiếm luôn hiện sẵn đầu sidebar, thay nút kính lúp cũ (bấm vào không làm gì
// cả, cùng kiểu "nút chết" đã bỏ ở ô nhập tin -- không làm gì thì bỏ hẳn thay vì để đó cho đẹp mắt suông).
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
    var dms = [], groups = [];
    filtered.forEach(function (conv) {
        var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
        (others.length === 1 ? dms : groups).push(conv);
    });
    renderConvSection(container, 'Nhắn tin trực tiếp', ICON.mail, dms);
    renderConvSection(container, 'Nhóm', ICON.users, groups);
}
document.getElementById('conversationSearchInput').addEventListener('input', function (e) {
    conversationSearchTerm = e.target.value;
    renderConversationList(lastConvList);
});

// Mở 1 hội thoại đã có sẵn (từ danh sách, bấm vào) — không cần tự SUBSCRIBE nữa, harbor đã tự
// wake-subscribe hết mọi conversation của mình ngay sau AUTH (xem autoSubscribeAllConversations
// bên harbor), chỉ cần tạo card (nếu chưa có) + hiển thị nó + load lịch sử.
function openConversation(conversationId, label, subtitle) {
    ensureConversationCard(conversationId, label, subtitle);
    selectConversation(conversationId);
}

// Chuyển #chatMain sang hiển thị đúng 1 conversation (ẩn hết card khác) — cùng ý tưởng "1 cuộc trò
// chuyện đang mở tại 1 thời điểm" như Slack/Discord/WhatsApp, thay vì xếp chồng tất cả log cùng lúc
// (rất rối khi test với nhiều conversation). Card không bị huỷ khi ẩn -- chỉ display:none, nên
// scroll position + draft đang gõ dở của các conversation khác vẫn giữ nguyên khi quay lại.
function selectConversation(conversationId) {
    var entry = conversations[conversationId];
    if (!entry) return;
    if (activeConversationId && activeConversationId !== conversationId && conversations[activeConversationId]) {
        var prevEntry = conversations[activeConversationId];
        prevEntry.el.classList.remove('active');
        // "Tắt" hẳn mọi thứ còn đang chạy ở hội thoại VỪA RỜI ĐI khi chuyển sang hội thoại khác --
        // video đang phát (bấm play trước đó) KHÔNG tự dừng chỉ vì card bị ẩn qua display:none (hành
        // vi mặc định của trình duyệt), sẽ tiếp tục phát tiếng ngầm phía sau nếu không chủ động pause.
        prevEntry.el.querySelectorAll('video, audio').forEach(function (mediaEl) { mediaEl.pause(); });
        // Popup chọn emoji gắn NGOÀI card (vào document.body, xem ensureReactionPicker) nên không tự
        // ẩn theo display:none của .conv -- nếu đang mở nhắm vào 1 tin của hội thoại cũ thì đóng luôn.
        closeReactionPicker();
    }
    activeConversationId = conversationId;
    entry.el.classList.add('active');
    // Neo lại mốc chiều cao .conv-send NGAY (đo đồng bộ) tại đúng lúc card vừa hiện ra thật sự -- xem
    // chú thích chi tiết ở entry.resetComposeSendBaseline (ensureConversationCard) + composeResizeObserver.
    entry.resetComposeSendBaseline();
    document.getElementById('chatEmpty').style.display = 'none';
    // Xoá badge "chưa đọc" của ĐÚNG conversation vừa mở (no-op nếu vốn không có) -- server sẽ tự tính
    // lại ĐÚNG số 0 ở lần refreshConversationList()/reload kế tiếp (đọc gần hết qua
    // IntersectionObserver ngay khi các tin lọt khung nhìn), xoá ngay ở đây chỉ để phản hồi tức thì.
    var openedConv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (openedConv) openedConv.unreadCount = 0;
    renderConversationList(lastConvList); // cập nhật highlight "active" + xoá badge unread trong sidebar
    renderInfoPanel(conversationId);
    loadHistory(conversationId);
    // Lần ĐẦU TIÊN THẬT SỰ hiện conversation này ra màn hình (trước đó có thể đã nạp sẵn lịch sử
    // trong lúc còn display:none -- vd subscribe ngầm lúc mới đăng nhập) -- áp lại đúng vị trí cuộn
    // NGAY BÂY GIỜ, lúc đã có layout thật (xem applyScrollAnchor). No-op nếu loadHistory() còn đang
    // load dở (chưa có entry.scrollAnchor) -- chính loadHistory() sẽ tự áp dụng khi xong, lúc đó
    // card cũng đã active/có layout rồi. Các lần MỞ LẠI SAU (đã từng active) thì bỏ qua hẳn bước
    // này -- giữ nguyên scrollTop người dùng đang dừng ở đó, không ép lại mỗi lần quay lại tab.
    if (!entry.everActivated) {
        entry.everActivated = true;
        applyScrollAnchor(entry);
    }
    // Màn hình hẹp (điện thoại): sidebar/khung chat KHÔNG hiện đồng thời (đủ chỗ đâu mà hiện cả 2) --
    // mở 1 hội thoại tự chuyển sang xem khung chat, xem CSS "#layout.mobileChatOpen" + goBackToSidebar().
    document.getElementById('layout').classList.add('mobileChatOpen');
    var input = entry.el.querySelector('.composeInput');
    if (input && !isNarrowViewport()) input.focus(); // đừng tự bật bàn phím ảo ngay khi vừa vào màn hình chat trên điện thoại
}

// Quay lại danh sách hội thoại (chỉ có ý nghĩa ở màn hình hẹp -- xem nút .backBtn trong conv-head,
// chỉ hiện qua CSS khi màn hình đủ hẹp). Không đổi activeConversationId/không huỷ gì -- card vẫn giữ
// nguyên, chỉ là đổi panel nào đang hiện.
function goBackToSidebar() {
    document.getElementById('layout').classList.remove('mobileChatOpen');
}

function isNarrowViewport() {
    return window.matchMedia('(max-width: 680px)').matches;
}

