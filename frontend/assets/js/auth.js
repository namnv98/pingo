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

// deviceId phải được BIẾT TRƯỚC lúc gọi /login|/register (gửi kèm để server nhúng vào claim JWT,
// xem HallApiHandlers#issueToken -- cần cho tính năng thu hồi thiết bị tức thời, MlsDeviceRegistry),
// nhưng lúc đó CHƯA có myUserId (chỉ có username người dùng vừa gõ) -- khoá tạm theo username. Sau
// khi login/register xong (biết userId thật), migrate sang đúng khoá mls-crypto.js đọc
// (MLS_DEVICE_ID_STORAGE + ':' + userId, xem e2eGetOrCreateDeviceId) để e2eInit() TÁI DÙNG đúng
// deviceId này thay vì tự sinh cái khác -- 1 thiết bị chỉ có ĐÚNG 1 deviceId xuyên suốt từ lúc
// login tới lúc e2eInit() publish KeyPackage.
var AUTH_DEVICE_ID_PREFIX = 'pingo_device_id:';
function getOrCreateAuthDeviceId(username) {
    var k = AUTH_DEVICE_ID_PREFIX + username;
    var existing = localStorage.getItem(k);
    if (existing) return existing;
    var id = crypto.randomUUID();
    localStorage.setItem(k, id);
    return id;
}

function clearAuth() {
    myUserId = '';
    myUsername = '';
    authToken = '';
    localStorage.removeItem(STORAGE_ID_KEY);
    localStorage.removeItem(STORAGE_USERNAME_KEY);
    localStorage.removeItem(STORAGE_TOKEN_KEY);
}

// _isRetry: nội bộ, không truyền tay -- xem nhánh deviceRevoked bên dưới (bug thật đã gặp: gỡ 1
// thiết bị TỪ THIẾT BỊ KHÁC khiến chính thiết bị đó kẹt vĩnh viễn "sai mật khẩu"/401 mọi thứ, vì nó
// cứ tái dùng ĐÚNG deviceId cũ đã bị revoke mỗi lần login -- xem javadoc HallApiHandlers#withToken).
function doAuth(path, _isRetry) {
    clearAuthError();
    var username = document.getElementById('authUsername').value.trim();
    var password = document.getElementById('authPassword').value;
    if (!username || !password) {
        showAuthError('nhập đủ username và password');
        return;
    }
    var deviceId = getOrCreateAuthDeviceId(username);
    var label = (typeof e2eGuessDeviceLabel === 'function') ? e2eGuessDeviceLabel() : undefined;
    fetch(HISTORY_API_BASE + path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username: username, password: password, deviceId: deviceId, label: label})
    })
        .then(function (res) {
            return res.json().then(function (data) { return {ok: res.ok, data: data}; });
        })
        .then(function (result) {
            if (!result.ok) {
                showAuthError(result.data.error || ('HTTP ' + result.status));
                return;
            }
            if (result.data.deviceRevoked && !_isRetry) {
                // deviceId cục bộ đã bị revoke (từ 1 thiết bị khác) -- server đã issue token KHÔNG
                // mang claim đó (dùng tạm được), nhưng phải xoá cache + sinh deviceId MỚI rồi login
                // lại 1 lần nữa NGAY để có token mang deviceId hợp lệ (không thì "Thiết bị của tôi"/
                // thu hồi tương lai không hoạt động đúng cho phiên này). Giới hạn ĐÚNG 1 lần retry
                // (_isRetry=true) -- deviceId mới không bao giờ bị revoke sẵn, không lặp vô hạn.
                localStorage.removeItem(AUTH_DEVICE_ID_PREFIX + username);
                doAuth(path, true);
                return;
            }
            saveAuth(result.data.id, result.data.username, result.data.token);
            // Migrate deviceId sang khoá mls-crypto.js sẽ đọc (xem getOrCreateAuthDeviceId ở trên).
            localStorage.setItem(MLS_DEVICE_ID_STORAGE + ':' + result.data.id, deviceId);
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
    unregisterPushToken(); // TRƯỚC clearAuth() -- cần authToken còn hợp lệ để gọi DELETE /push-tokens (xem push-notifications.js)
    // Đăng xuất PHẢI thu hồi luôn chính thiết bị này (không chỉ xoá token cục bộ) -- nếu không, ai
    // lấy được token cũ (chưa hết hạn, TOKEN_TTL 7 ngày) vẫn dùng được bình thường sau khi "đã đăng
    // xuất". Thu hồi TRƯỚC khi clearAuth() xoá authToken khỏi bộ nhớ (cần token hợp lệ để gọi DELETE).
    // Chạy nền, không chặn phần dọn UI bên dưới (mất mạng vẫn phải đăng xuất được cục bộ) -- xoá luôn
    // deviceId khỏi localStorage NGAY CẢ KHI revoke lỗi (mất mạng...): giữ deviceId cũ lại thì lần
    // login sau (nếu revoke đã trót thành công phía server) sẽ tự khoá chính mình ngay từ request đầu
    // (xem MlsDeviceRegistry#registerDevice's ON CONFLICT ... WHERE revoked_at IS NULL) -- thà sinh
    // deviceId mới oan 1 lần còn hơn tự lock mình ra khỏi tài khoản.
    var loggedOutUserId = myUserId;
    var loggedOutUsername = myUsername;
    var deviceIdToRevoke = e2eDeviceId || (loggedOutUserId ? localStorage.getItem(MLS_DEVICE_ID_STORAGE + ':' + loggedOutUserId) : null);
    if (authToken && deviceIdToRevoke) {
        e2eDeleteDevice(deviceIdToRevoke).catch(function (err) { console.warn('[e2e-mls] thu hồi thiết bị lúc logout lỗi (bỏ qua)', err); });
    }
    if (loggedOutUserId) {
        localStorage.removeItem(MLS_DEVICE_ID_STORAGE + ':' + loggedOutUserId);
        e2eClearLocalStateForCurrentUser(loggedOutUserId).catch(function (err) { console.warn('[e2e-mls] xoá state cục bộ lúc logout lỗi (bỏ qua)', err); });
    }
    if (loggedOutUsername) localStorage.removeItem(AUTH_DEVICE_ID_PREFIX + loggedOutUsername);
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

function refreshUserList() {
    fetch(HISTORY_API_BASE + '/users')
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (list) {
            knownUsers = list;
            usernameById = {};
            avatarFileIdById = {};
            list.forEach(function (u) {
                if (u.username) usernameById[u.id] = u.username;
                avatarFileIdById[u.id] = u.avatarFileId || null;
            });
            renderUserPickers();
            // Sửa race: nếu list hội thoại về trước usernameById, tên bị kẹt dạng ID -- vẽ lại sidebar (cache) ngay khi tên đã sẵn sàng.
            renderConversationList(lastConvList);
            renderWhoami();
            refreshPresenceSnapshot(collectRelevantUserIdsForPresence());
        })
        .catch(function (err) {
            console.warn('không load được danh sách user', err);
        });
}

// Chỉ xin presence cho user thực sự liên quan, tránh vượt giới hạn 1 lần gọi (xem HeraldApiHandlers#getPresence, MAX_PRESENCE_USER_IDS).
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

// Lọc theo "giống UUID tự sinh" (username luôn có giá trị, xem HallApiHandlers#register) để ẩn tài khoản e2e test (registerUser() sinh username=uuid()) khỏi danh sách mặc định.
var UUID_LIKE_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Tách tab DM/Group riêng vì luồng tạo khác nhau (DM chọn là gửi ngay, Group cần chọn nhiều + đặt tên) -- gộp chung trước đây rối.
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

// Đóng khi bấm ra ngoài / Esc thay vì chỉ bấm lại nút tròn (hành vi mặc định của <details>), giống các popup khác trong app.
document.addEventListener('click', function (e) {
    var panel = document.getElementById('newConvPanel');
    if (panel.open && !panel.contains(e.target)) panel.open = false;
});
document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') document.getElementById('newConvPanel').open = false;
});

// Vẽ lại từ knownUsers đã fetch sẵn (không gọi lại API mỗi lần gõ/tick) -- không còn nhập UUID tay, chọn theo tên là đủ.
function renderUserPickers() {
    var showTest = document.getElementById('showTestAccounts').checked;
    var query = document.getElementById('userSearch').value.trim().toLowerCase();
    var visible = knownUsers.filter(function (u) {
        if (u.id === myUserId) return false;
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

function buildUserRow(u, canSelect) {
    var row = document.createElement('div');
    row.className = 'userRow' + (canSelect ? ' canSelect' : '');
    row.title = u.id;
    var av = document.createElement('div');
    av.className = 'userRowAvatar';
    var uImageUrl = userAvatarUrl(u.id);
    applyAvatar(av, uImageUrl ? {imageUrl: uImageUrl} : {color: avatarColor(u.id), initial: avatarInitial(u.username)});
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

