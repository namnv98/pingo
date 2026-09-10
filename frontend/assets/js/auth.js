function clearAuthError() {
    var el = document.getElementById('authError');
    el.style.display = 'none';
    el.innerText = '';
}

function showAuthError(message) {
    var el = document.getElementById('authError');
    el.innerText = '⚠ ' + message;
    el.style.display = '';
}

function saveAuth(id, username, token) {
    myUserId = id;
    myUsername = username;
    authToken = token;
    localStorage.setItem(STORAGE_ID_KEY, id);
    localStorage.setItem(STORAGE_USERNAME_KEY, username);
    localStorage.setItem(STORAGE_TOKEN_KEY, token);
}

function clearAuth() {
    myUserId = '';
    myUsername = '';
    authToken = '';
    localStorage.removeItem(STORAGE_ID_KEY);
    localStorage.removeItem(STORAGE_USERNAME_KEY);
    localStorage.removeItem(STORAGE_TOKEN_KEY);
}

// path: '/login' hoặc '/register' — cùng 1 body {username, password}, cùng cách xử lý kết quả.
function doAuth(path) {
    clearAuthError();
    var username = document.getElementById('authUsername').value.trim();
    var password = document.getElementById('authPassword').value;
    if (!username || !password) {
        showAuthError('nhập đủ username và password');
        return;
    }
    fetch(HISTORY_API_BASE + path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username: username, password: password})
    })
        .then(function (res) {
            return res.json().then(function (data) { return {ok: res.ok, data: data}; });
        })
        .then(function (result) {
            if (!result.ok) {
                showAuthError(result.data.error || ('HTTP ' + result.status));
                return;
            }
            saveAuth(result.data.id, result.data.username, result.data.token);
            enterApp();
        })
        .catch(function (err) {
            showAuthError('lỗi kết nối: ' + err.message);
        });
}
function login() { doAuth('/login'); }
function register() { doAuth('/register'); }

function logout() {
    identityConfirmed = false; // truoc khi close() de ws.onclose khong tu retry
    if (ws) { ws.close(); ws = null; }
    clearAuth();
    conversations = {};
    activeConversationId = null;
    lastConvList = [];
    selectedGroupMemberIds = [];
    onlineUserIds = {};
    Object.keys(typingTimers).forEach(function (cid) {
        Object.values(typingTimers[cid]).forEach(clearTimeout);
    });
    typingTimers = {};
    lastTypingSentAt = {};
    document.getElementById('chatMain').querySelectorAll('.conv').forEach(function (el) { el.remove(); });
    document.getElementById('chatEmpty').style.display = 'flex';
    renderInfoPanel(null);
    goBackToSidebar();
    document.getElementById('authUsername').value = '';
    document.getElementById('authPassword').value = '';
    showLoginForm();
}

// Re-fetch + vẽ lại danh sách/picker -- gọi lúc enterApp() và mỗi khi bấm nút "🔄 Làm mới danh sách".
function refreshUserList() {
    fetch(HISTORY_API_BASE + '/users')
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (list) {
            knownUsers = list;
            usernameById = {};
            list.forEach(function (u) { if (u.username) usernameById[u.id] = u.username; });
            renderUserPickers();
            // refreshUserList() và refreshConversationList() chạy song song lúc enterApp() -- nếu
            // list hội thoại về TRƯỚC khi usernameById kịp có (race condition thường gặp), tên hiện
            // ra sẽ tạm rơi về dạng ID rút gọn (xem displayName) và KẸT NGUYÊN như vậy tới khi có gì
            // đó chủ động vẽ lại. Vẽ lại sidebar (dùng cache lastConvList, không cần fetch lại) ngay
            // khi tên vừa sẵn sàng để tự sửa đúng, không bắt người dùng phải tự bấm 🔄.
            renderConversationList(lastConvList);
            refreshPresenceSnapshot(collectRelevantUserIdsForPresence());
        })
        .catch(function (err) {
            console.warn('không load được danh sách user', err);
        });
}

// Chỉ xin snapshot presence cho user THỰC SỰ liên quan (thành viên các hội thoại đang có + user đã
// đặt tên thật trong picker) -- không xin cho cả nghìn tài khoản test dạng UUID, vừa vô nghĩa vừa
// có thể vượt giới hạn 1 lần gọi (xem HeraldApiHandlers#getPresence, MAX_PRESENCE_USER_IDS).
function collectRelevantUserIdsForPresence() {
    var ids = {};
    lastConvList.forEach(function (conv) {
        conv.memberUserIds.forEach(function (id) { if (id !== myUserId) ids[id] = true; });
    });
    knownUsers.forEach(function (u) {
        if (u.id !== myUserId && u.username && !UUID_LIKE_RE.test(u.username)) ids[u.id] = true;
    });
    return Object.keys(ids).slice(0, 200);
}

// username LUÔN có giá trị (bắt buộc từ lúc /register, xem HallApiHandlers#register) — nhưng rất
// nhiều tài khoản trong DB là do e2e test tự sinh username = uuid() (registerUser() trong
// e2e/lib.mjs), nhìn không khác gì ID, không ai nhận ra được là ai. Lọc theo "trông giống UUID tự
// sinh" (không phải "có/không có tên" -- lúc nào cũng có) để ẩn bớt rác test theo mặc định.
var UUID_LIKE_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Chuyển tab DM / Group trong panel "Cuộc trò chuyện mới" -- tách biệt 2 luồng tạo, đỡ rối hơn hẳn
// so với nhồi chung 1 danh sách (DM chọn phát đi ngay, Group cần chọn nhiều + đặt tên nên khác hẳn
// thao tác, để chung 1 chỗ trước đây khó nhìn/khó phân biệt đang thao tác cho cái nào).
function switchNewConvTab(tab) {
    document.getElementById('tabDmBtn').classList.toggle('active', tab === 'dm');
    document.getElementById('tabGroupBtn').classList.toggle('active', tab === 'group');
    document.getElementById('dmTab').classList.toggle('active', tab === 'dm');
    document.getElementById('groupTab').classList.toggle('active', tab === 'group');
    // Ô tên nhóm + nút tạo + đếm thành viên CHỈ thuộc tab Group -- ẩn ở tab DM cho đỡ rối mắt.
    var isGroup = tab === 'group';
    document.getElementById('groupNameInput').hidden = !isGroup;
    document.getElementById('createGroupBtn').hidden = !isGroup;
    document.getElementById('memberCount').hidden = !isGroup;
    if (isGroup) updateGroupFoot();
}

// Panel "Cuộc trò chuyện mới" giờ nổi thành thẻ riêng phía trên nút tròn (FAB, xem CSS
// #newConvPanel[open] #newConvBody) chứ không còn đẩy danh sách hội thoại xuống -- đóng lại khi bấm ra
// NGOÀI panel hoặc Esc, giống mọi popup khác trong app (theme menu, background picker...), thay vì chỉ
// đóng được bằng cách bấm lại đúng nút tròn như hành vi mặc định của <details>.
document.addEventListener('click', function (e) {
    var panel = document.getElementById('newConvPanel');
    if (panel.open && !panel.contains(e.target)) panel.open = false;
});
document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') document.getElementById('newConvPanel').open = false;
});

// Vẽ lại danh sách user ở CẢ 2 tab (DM + Group) thành các hàng .userRow (avatar + tên + ô tick),
// từ knownUsers đã fetch sẵn (không gọi lại API) — tách riêng khỏi refreshUserList() để checkbox/ô
// tìm kiếm chỉ cần vẽ lại, không cần fetch lại mỗi lần gõ/tick. KHÔNG còn ô nhập UUID tay -- chọn
// bằng tên duy nhất (không ai gõ nổi 1 UUID 36 ký tự chính xác), search-by-name đã đủ giữa hàng nghìn user.
function renderUserPickers() {
    var showTest = document.getElementById('showTestAccounts').checked;
    var query = document.getElementById('userSearch').value.trim().toLowerCase();
    var visible = knownUsers.filter(function (u) {
        if (u.id === myUserId) return false; // không tự nhắn/tự chọn chính mình
        if (!showTest && UUID_LIKE_RE.test(u.username)) return false;
        if (query && u.username.toLowerCase().indexOf(query) === -1) return false;
        return true;
    });

    var dmPicker = document.getElementById('dmPicker');
    dmPicker.innerHTML = '';
    visible.forEach(function (u) {
        var row = buildUserRow(u, false);
        row.onclick = function () { startDm(u.id); };
        dmPicker.appendChild(row);
    });

    var groupPicker = document.getElementById('memberPicker');
    groupPicker.innerHTML = '';
    visible.forEach(function (u) {
        var row = buildUserRow(u, true);
        if (selectedGroupMemberIds.indexOf(u.id) !== -1) row.classList.add('selected');
        row.onclick = function () { toggleGroupMember(u.id, row); };
        groupPicker.appendChild(row);
    });
}

// Dựng 1 hàng user (avatar màu ổn định theo id + tên + ô tick bên phải). canSelect=true (tab Group)
// thì hiện ô tick tròn để chọn/bỏ nhiều người; tab DM (canSelect=false) không có tick -- bấm là nhắn ngay.
function buildUserRow(u, canSelect) {
    var row = document.createElement('div');
    row.className = 'userRow' + (canSelect ? ' canSelect' : '');
    row.title = u.id;
    var av = document.createElement('div');
    av.className = 'userRowAvatar';
    av.style.background = avatarColor(u.id);
    av.innerText = avatarInitial(u.username);
    row.appendChild(av);
    var name = document.createElement('div');
    name.className = 'userName';
    name.innerText = u.username;
    row.appendChild(name);
    var check = document.createElement('div');
    check.className = 'userCheck';
    check.innerText = '✓';
    row.appendChild(check);
    return row;
}

function toggleGroupMember(userId, rowEl) {
    var idx = selectedGroupMemberIds.indexOf(userId);
    if (idx === -1) selectedGroupMemberIds.push(userId); else selectedGroupMemberIds.splice(idx, 1);
    if (rowEl) rowEl.classList.toggle('selected', idx === -1);
    updateGroupFoot();
}

// Cập nhật ô tên nhóm + nút tạo + đếm số thành viên ở chân panel -- chỉ hiện ở tab Group.
function updateGroupFoot() {
    var n = selectedGroupMemberIds.length;
    var count = document.getElementById('memberCount');
    count.hidden = false;
    count.innerText = n === 0 ? 'chưa chọn ai' : 'đã chọn ' + n + ' thành viên';
    var btn = document.getElementById('createGroupBtn');
    btn.disabled = n === 0;
    btn.innerText = n === 0 ? 'Tạo group' : 'Tạo group (' + n + ')';
}

// Đóng panel tạo mới -- dùng cho nút ✕ trong header (Esc + bấm ra ngoài đã có sẵn, xem bên dưới).
function closeNewConvPanel() {
    document.getElementById('newConvPanel').open = false;
}

