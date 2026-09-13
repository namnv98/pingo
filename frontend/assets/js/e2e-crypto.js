// Mã hoá đầu cuối (E2E) -- dùng thư viện Olm thật (Double Ratchet + Megolm, xem frontend/vendor/olm.js).
// Mỗi THIẾT BỊ (không phải mỗi user) có 1 identity key riêng (Signal Sesame:
// https://signal.org/docs/specifications/sesame/) -- gửi tin phải mã hoá RIÊNG cho TỪNG thiết bị của
// người nhận (fan-out), không dùng chung 1 identity giữa các thiết bị của cùng 1 user.
//
// DM: tin đầu tiên gửi cho 1 thiết bị chưa từng chat cùng tự động là "PREKEY message" (Olm tự nhúng
// mọi thứ cần để tạo session, xem olm.js README mục create_outbound/create_inbound). 1 tin logic = 1
// envelope chứa nhiều ciphertext (1 cho mỗi thiết bị đích của người nhận, cộng thiết bị KHÁC của
// chính mình để tự đọc lại được tin mình gửi) -- xem {@code perDevice} trong
// e2eEncryptOutgoing/e2eDecryptIncoming.
//
// Private key không rời máy: Olm.Account/Olm.Session pickle bằng 1 khoá cục bộ (localStorage) rồi lưu
// IndexedDB -- xoá dữ liệu trình duyệt = mất lịch sử mã hoá cũ (chấp nhận được, như mọi client Olm
// khác). Lấy lại lịch sử ĐÃ giải mã từ thiết bị khác cùng tài khoản qua "liên kết thiết bị" (mã 6 ký
// tự) hoặc "sao lưu lạnh" (file, xem cuối file).

var E2E_DB_NAME = 'pingo-e2e';
var E2E_DB_VERSION = 3;
var E2E_PICKLE_KEY_STORAGE = 'pingo_e2e_pickle_key';
var E2E_DEVICE_ID_STORAGE = 'pingo_e2e_device_id';

var e2eReady = false; // true sau khi Olm.init() xong + đã có Account sẵn sàng dùng
var e2eAccount = null; // Olm.Account đang sống trong bộ nhớ (đã unpickle) -- luôn pickle lại + lưu DB sau mỗi lần đổi state (dùng prekey, v.v.)
var e2eIdentityKeys = null; // {curve25519, ed25519} của CHÍNH mình, cache lại khỏi phải parse account.identity_keys() nhiều lần
var e2eDeviceId = null; // id CỦA THIẾT BỊ NÀY -- sinh 1 lần/trình duyệt (localStorage), KHÔNG BAO GIỜ đổi, xem e2eGetOrCreateDeviceId
var e2eDb = null;
var e2ePickleKey = null;

function e2eOpenDb() {
    if (e2eDb) return Promise.resolve(e2eDb);
    return new Promise(function (resolve, reject) {
        var req = indexedDB.open(E2E_DB_NAME, E2E_DB_VERSION);
        req.onupgradeneeded = function () {
            var db = req.result;
            if (!db.objectStoreNames.contains('account')) db.createObjectStore('account');
            if (!db.objectStoreNames.contains('dmSessions')) db.createObjectStore('dmSessions');
            if (!db.objectStoreNames.contains('msgPlaintext')) db.createObjectStore('msgPlaintext');
            if (!db.objectStoreNames.contains('groupOutbound')) db.createObjectStore('groupOutbound');
            if (!db.objectStoreNames.contains('groupInbound')) db.createObjectStore('groupInbound');
        };
        req.onsuccess = function () { e2eDb = req.result; resolve(e2eDb); };
        req.onerror = function () { reject(req.error); };
    });
}

function e2eDbGet(storeName, key) {
    return e2eOpenDb().then(function (db) {
        return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, 'readonly');
            var req = tx.objectStore(storeName).get(key);
            req.onsuccess = function () { resolve(req.result === undefined ? null : req.result); };
            req.onerror = function () { reject(req.error); };
        });
    });
}

function e2eDbPut(storeName, key, value) {
    return e2eOpenDb().then(function (db) {
        return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, 'readwrite');
            tx.objectStore(storeName).put(value, key);
            tx.oncomplete = function () { resolve(); };
            tx.onerror = function () { reject(tx.error); };
        });
    });
}

// Lấy toàn bộ entry của storeName thuộc về mình (key bắt đầu bằng "myUserId|") -- dùng để đóng gói
// chuyển giao lúc liên kết thiết bị/sao lưu; lọc theo prefix vì IndexedDB chia theo ORIGIN chứ không
// theo user (1 trình duyệt có thể từng đăng nhập nhiều tài khoản).
function e2eDbGetAllForUser(storeName) {
    return e2eOpenDb().then(function (db) {
        return new Promise(function (resolve, reject) {
            var prefix = myUserId + '|';
            var results = [];
            var req = db.transaction(storeName, 'readonly').objectStore(storeName).openCursor();
            req.onsuccess = function () {
                var cursor = req.result;
                if (!cursor) { resolve(results); return; }
                if (String(cursor.key).indexOf(prefix) === 0) results.push({key: cursor.key, value: cursor.value});
                cursor.continue();
            };
            req.onerror = function () { reject(req.error); };
        });
    });
}

function e2eDbPutAll(storeName, entries) {
    if (!entries || !entries.length) return Promise.resolve();
    return e2eOpenDb().then(function (db) {
        return new Promise(function (resolve, reject) {
            var tx = db.transaction(storeName, 'readwrite');
            var store = tx.objectStore(storeName);
            entries.forEach(function (e) { store.put(e.value, e.key); });
            tx.oncomplete = function () { resolve(); };
            tx.onerror = function () { reject(tx.error); };
        });
    });
}

// Đóng gói/mở gói 'groupInbound' để chuyển sang thiết bị KHÁC (liên kết thiết bị hoặc sao lưu lạnh).
// KHÔNG được chuyển thẳng {@code stored.pickle} -- pickle được mã hoá bằng e2ePickleKey CỦA THIẾT BỊ
// NÀY, thiết bị nhận có pickle key riêng (không bao giờ transfer) nên unpickle sai khoá sẽ ném
// OLM.BAD_ACCOUNT_KEY. Dùng {@code export_session}/{@code import_session} của Olm (không lệ thuộc
// pickle key của bất kỳ thiết bị nào) làm định dạng trung gian, rồi pickle LẠI bằng pickle key của
// thiết bị đích lúc nhập.
function e2eExportGroupInboundEntries(entries) {
    return entries.map(function (e) {
        var session = new Olm.InboundGroupSession();
        session.unpickle(e2ePickleKey, e.value.pickle);
        return {key: e.key, exportedSessionKey: session.export_session(session.first_known_index())};
    });
}

function e2eImportGroupInboundEntries(entries) {
    if (!entries || !entries.length) return Promise.resolve();
    var putEntries = entries.map(function (e) {
        var session = new Olm.InboundGroupSession();
        session.import_session(e.exportedSessionKey);
        return {key: e.key, value: {pickle: session.pickle(e2ePickleKey)}};
    });
    return e2eDbPutAll('groupInbound', putEntries);
}

// Với mỗi outbound session (nhóm) đang giữ, tự tạo thêm 1 entry INBOUND "đọc lại tin của chính mình"
// từ initialSessionKey của nó -- Megolm là ratchet CHỈ TIẾN, biết khoá tại vị trí BAN ĐẦU giải được
// MỌI tin đã/sẽ mã hoá bằng ĐÚNG session đó, kể cả tin gửi SAU thời điểm backup này (miễn chưa rotate
// sang session khác) -- vá lỗ hổng "tin mình tự gửi trong khoảng gap không khôi phục được" ngay cả khi
// chỉ dùng 1 thiết bị duy nhất (khác e2eDistributeGroupSessionKeyToOwnDevices, cái đó cần ≥2 thiết bị
// CÙNG online). Trả về CÙNG format {key, exportedSessionKey} với e2eExportGroupInboundEntries để nhập
// lại bằng đúng e2eImportGroupInboundEntries, không cần thêm code riêng ở phía khôi phục.
function e2eExportOwnOutboundAsInboundEntries(outboundEntries) {
    return outboundEntries.map(function (e) {
        var conversationId = e.key.split('|')[2]; // key gốc: myUserId|deviceId|conversationId
        var outbound = new Olm.OutboundGroupSession();
        outbound.unpickle(e2ePickleKey, e.value.pickle);
        var initialSessionKey = e.value.initialSessionKey || outbound.session_key();
        var asInbound = new Olm.InboundGroupSession();
        asInbound.create(initialSessionKey);
        return {
            key: myUserId + '|' + conversationId + '|' + myUserId + '|' + outbound.session_id(),
            exportedSessionKey: asInbound.export_session(asInbound.first_known_index())
        };
    });
}

// Khoá pickle cục bộ (mã hoá Account/Session lúc lưu IndexedDB) -- sinh 1 lần/trình duyệt, KHÔNG BAO
// GIỜ gửi lên server, namespace theo myUserId (tránh 2 tài khoản từng dùng chung trình duyệt lẫn khoá).
function e2eGetOrCreatePickleKey() {
    var storageKey = E2E_PICKLE_KEY_STORAGE + ':' + myUserId;
    var existing = localStorage.getItem(storageKey);
    if (existing) return existing;
    var bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    var key = Array.from(bytes).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
    localStorage.setItem(storageKey, key);
    return key;
}

// Id của THIẾT BỊ này (không phải của user) -- sinh 1 lần/trình duyệt, public (gửi lên server để biết
// "gửi cho ai" khi fan-out per-device), khác pickle key ở chỗ đó.
function e2eGetOrCreateDeviceId() {
    var storageKey = E2E_DEVICE_ID_STORAGE + ':' + myUserId;
    var existing = localStorage.getItem(storageKey);
    if (existing) return existing;
    var id = newId();
    localStorage.setItem(storageKey, id);
    return id;
}

// Tên gợi ý cho thiết bị (vd "Chrome trên macOS") -- chỉ để người dùng tự nhận diện trong "Thiết bị
// của tôi", không có ý nghĩa kỹ thuật/bảo mật.
function e2eGuessDeviceLabel() {
    var ua = navigator.userAgent || '';
    var browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
        : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Trình duyệt';
    var os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android'
        : /iPhone|iPad/.test(ua) ? 'iOS' : /Linux/.test(ua) ? 'Linux' : '';
    return os ? browser + ' trên ' + os : browser;
}

function e2eSaveAccount() {
    return e2eDbPut('account', myUserId, {pickle: e2eAccount.pickle(e2ePickleKey)});
}

// Sinh + upload 1 lô one-time prekey mới -- gọi lúc chưa từng bật E2E, và định kỳ khi server báo còn ít.
function e2eGenerateAndUploadPrekeys(count) {
    e2eAccount.generate_one_time_keys(count);
    var otks = JSON.parse(e2eAccount.one_time_keys()).curve25519 || {};
    var toUpload = {};
    Object.keys(otks).forEach(function (keyId) { toUpload[newId()] = otks[keyId]; });
    e2eAccount.mark_keys_as_published();
    return e2eSaveAccount().then(function () {
        return fetch(HISTORY_API_BASE + '/e2e/keys', {
            method: 'PUT',
            headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
            body: JSON.stringify({deviceId: e2eDeviceId, oneTimePrekeys: toUpload})
        }).then(function (res) {
            if (!res.ok) throw new Error('upload prekeys HTTP ' + res.status);
        });
    });
}

// Kiểm tra còn bao nhiêu prekey trên server, top-up nếu dưới ngưỡng -- gọi mỗi lần enterApp().
var E2E_PREKEY_LOW_WATERMARK = 10;
var E2E_PREKEY_TOP_UP_COUNT = 20;
function e2eMaybeTopUpPrekeys() {
    return fetch(HISTORY_API_BASE + '/e2e/prekey-count?deviceId=' + encodeURIComponent(e2eDeviceId), {headers: {'Authorization': 'Bearer ' + authToken}})
        .then(function (res) { return res.ok ? res.json() : {count: 0}; })
        .then(function (data) {
            if ((data.count || 0) < E2E_PREKEY_LOW_WATERMARK) {
                return e2eGenerateAndUploadPrekeys(E2E_PREKEY_TOP_UP_COUNT);
            }
        })
        .catch(function (err) { console.warn('không top-up được e2e prekey', err); });
}

// Đăng ký identity key của THIẾT BỊ này với server (theo deviceId riêng, xem HallApiHandlers#uploadE2eKeys)
// -- mỗi thiết bị/trình duyệt có 1 deviceId + identity key riêng, mở bao nhiêu nơi cũng không ghi đè nhau.
function e2eUploadIdentityKey() {
    return fetch(HISTORY_API_BASE + '/e2e/keys', {
        method: 'PUT',
        headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
        body: JSON.stringify({deviceId: e2eDeviceId, identityKey: e2eIdentityKeys.curve25519, label: e2eGuessDeviceLabel()})
    }).then(function (res) {
        if (!res.ok) throw new Error('upload identity key HTTP ' + res.status);
    });
}

// === Quản lý thiết bị ("Thiết bị của tôi") ===
// Gỡ 1 thiết bị KHÔNG thu hồi được các Olm session người khác đã lỡ thiết lập với nó -- chỉ ngăn không
// ai THIẾT LẬP MỚI với thiết bị đó nữa. Không cho gỡ đúng thiết bị đang dùng (UI phía
// sidebar-conversations.js) -- muốn đăng xuất thiết bị hiện tại thì dùng nút Đăng xuất thường.
function e2eListDevices() {
    return fetch(HISTORY_API_BASE + '/e2e/devices', {headers: {'Authorization': 'Bearer ' + authToken}})
        .then(function (res) { return res.ok ? res.json() : {devices: []}; })
        .then(function (data) { return data.devices || []; });
}

function e2eDeleteDevice(deviceId) {
    return fetch(HISTORY_API_BASE + '/e2e/devices?deviceId=' + encodeURIComponent(deviceId), {
        method: 'DELETE',
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) throw new Error('Gỡ thiết bị thất bại, thử lại sau');
    });
}

var e2eInitPromise = null; // theo dõi lệnh gọi đang chạy dở -- Olm.init() không idempotent, gọi chồng
// 1 lần thứ 2 lúc lần đầu chưa xong sẽ TREO VĨNH VIỄN (không resolve/reject) -- "if (e2eReady) return"
// không đủ vì e2eReady vẫn false lúc đó, phải nhớ đúng Promise đang chạy mà trả lại.
var e2eInitializedForUserId = null; // userId mà e2eReady/e2eAccount/... đang đại diện -- xem reset bên dưới.

// Nạp Account đã pickle từ IndexedDB, hoặc tạo mới nếu chưa từng có -- tách riêng khỏi e2eInit() để
// mỗi bước của chuỗi khởi tạo có tên rõ ràng.
function e2eLoadOrCreateAccount() {
    return e2eDbGet('account', myUserId).then(function (stored) {
        e2eAccount = new Olm.Account();
        if (stored && stored.pickle) e2eAccount.unpickle(e2ePickleKey, stored.pickle);
        else e2eAccount.create();
        e2eIdentityKeys = JSON.parse(e2eAccount.identity_keys());
    });
}

// Gọi 1 lần lúc enterApp() -- nạp/tạo Account của thiết bị này, đảm bảo server có sẵn identity
// key/prekey để người khác thiết lập session bất cứ lúc nào (kể cả khi mình đang offline).
function e2eInit() {
    if (e2eInitializedForUserId !== myUserId) {
        // Đăng xuất rồi đăng nhập tài khoản KHÁC trong cùng 1 lần tải trang (không F5) -- reset mọi
        // state module-level, nếu không tài khoản mới sẽ dùng nhầm Account của tài khoản trước.
        e2eReady = false;
        e2eInitPromise = null;
        e2eAccount = null;
        e2eIdentityKeys = null;
        e2ePickleKey = null;
        e2eDeviceId = null;
    }
    if (e2eInitPromise) return e2eInitPromise;
    if (e2eReady) return Promise.resolve();
    if (typeof Olm === 'undefined') return Promise.resolve(); // vendor/olm.js chưa load được -- E2E tự tắt, phần còn lại của app vẫn chạy bình thường
    e2eInitializedForUserId = myUserId;
    e2ePickleKey = e2eGetOrCreatePickleKey();
    e2eDeviceId = e2eGetOrCreateDeviceId();
    e2eInitPromise = Olm.init({locateFile: function () { return 'vendor/olm.wasm'; }})
        .then(e2eLoadOrCreateAccount)
        // Luôn re-upload identity key (idempotent phía server) dù Account cũ hay mới -- lần trước có
        // thể đã lỡ fail (mất mạng...), không được kẹt vĩnh viễn không bao giờ thử lại.
        .then(e2eSaveAccount)
        .then(e2eUploadIdentityKey)
        .then(function () {
            e2eReady = true;
            return e2eMaybeTopUpPrekeys();
        })
        .catch(function (err) {
            console.warn('e2eInit lỗi -- mã hoá đầu cuối sẽ không dùng được phiên này', err);
            e2eReady = false;
        });
    return e2eInitPromise;
}

// Bật mã hoá đầu cuối cho 1 conversation -- DM dùng Olm 1-1; group dùng Megolm (xem e2eEncryptGroupOutgoing).
function e2eEnableConversation(conversationId) {
    return fetch(HISTORY_API_BASE + '/conversations/e2e?conversationId=' + encodeURIComponent(conversationId), {
        method: 'PUT',
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) return res.json().catch(function () { return {}; }).then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
        refreshConversationList();
    });
}

// Session DM gắn theo (myUserId, ownerUserId, deviceId) -- 1 Double Ratchet ĐỘC LẬP cho mỗi (mình,
// đúng 1 thiết bị của họ). ownerUserId có thể là chính myUserId khi đây là 1 thiết bị KHÁC của mình.
function e2eLoadDmSession(ownerUserId, deviceId) {
    return e2eDbGet('dmSessions', myUserId + '|' + ownerUserId + '|' + deviceId).then(function (stored) {
        if (!stored || !stored.pickle) return null;
        var session = new Olm.Session();
        session.unpickle(e2ePickleKey, stored.pickle);
        return session;
    });
}

function e2eSaveDmSession(ownerUserId, deviceId, session) {
    return e2eDbPut('dmSessions', myUserId + '|' + ownerUserId + '|' + deviceId, {pickle: session.pickle(e2ePickleKey)});
}

// Danh sách thiết bị của userId -- mỗi phần tử đã CHIẾM (xoá) sẵn 1 one-time prekey của thiết bị đó
// (xem HallApiHandlers#getE2eKeyBundle). Mảng rỗng (không phải lỗi) nếu userId chưa từng bật E2E.
function e2eFetchDeviceBundles(userId) {
    return fetch(HISTORY_API_BASE + '/e2e/keys/bundle?userId=' + encodeURIComponent(userId), {
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }).then(function (data) { return data.devices || []; });
}

// Giống e2eFetchDeviceBundles nhưng KHÔNG claim/xoá prekey (GET /e2e/keys/devices) -- dùng để kiểm tra
// rẻ "người này có thiết bị nào chưa nhận 1 khoá Megolm cụ thể" mà không tốn prekey chỉ để kiểm tra.
function e2eListDevicesForUser(userId) {
    return fetch(HISTORY_API_BASE + '/e2e/keys/devices?userId=' + encodeURIComponent(userId), {
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }).then(function (data) { return data.devices || []; });
}

// Lấy session đã có với đúng 1 thiết bị, hoặc tạo outbound session mới bằng identity+one-time key của
// thiết bị đó -- {@code device.oneTimePrekey} có thể null (hết prekey, Olm vẫn tạo được, chỉ kém 1 lớp
// forward-secrecy ở tin đầu).
function e2eGetOrCreateDmSessionForDevice(ownerUserId, device) {
    return e2eLoadDmSession(ownerUserId, device.deviceId).then(function (session) {
        if (session) return session;
        var newSession = new Olm.Session();
        newSession.create_outbound(e2eAccount, device.identityKey, device.oneTimePrekey ? device.oneTimePrekey.publicKey : device.identityKey);
        return e2eSaveDmSession(ownerUserId, device.deviceId, newSession).then(function () { return newSession; });
    });
}

// Danh sách đích cần mã hoá riêng cho 1 tin DM gửi tới peerUserId: mọi thiết bị của họ + mọi thiết bị
// KHÁC của chính mình (để tự đọc lại được tin mình gửi trên thiết bị khác).
function e2eBuildOutgoingTargets(peerUserId) {
    return Promise.all([e2eFetchDeviceBundles(peerUserId), e2eFetchDeviceBundles(myUserId)]).then(function (results) {
        var peerDevices = results[0];
        if (!peerDevices.length) throw new Error('người này chưa bật mã hoá đầu cuối');
        var myOtherDevices = results[1].filter(function (d) { return d.deviceId !== e2eDeviceId; });
        return peerDevices.map(function (d) { return {ownerUserId: peerUserId, device: d}; })
            .concat(myOtherDevices.map(function (d) { return {ownerUserId: myUserId, device: d}; }));
    });
}

// Mã hoá 1 tin gửi cho peerUserId trong DM đã bật E2E (cũng dùng lại để mã hoá payload to-device như
// megolm_session). Trả envelope {@code {perDevice: {deviceId: {olmType, ciphertext}}, senderDeviceId}}.
// replyTo.fromUserId + mentionedUserIds lặp lại dạng cleartext ở ngoài (nội dung tin kín hoàn toàn,
// nhưng "ai được reply/mention" vẫn lộ để server bắn đúng thông báo, xem ChatSessionManager).
function e2eEncryptOutgoing(peerUserId, plainBody) {
    return e2eBuildOutgoingTargets(peerUserId).then(function (targets) {
        var plaintext = JSON.stringify(plainBody);
        return Promise.all(targets.map(function (t) {
            return e2eGetOrCreateDmSessionForDevice(t.ownerUserId, t.device).then(function (session) {
                var enc = session.encrypt(plaintext);
                return e2eSaveDmSession(t.ownerUserId, t.device.deviceId, session).then(function () {
                    return {deviceId: t.device.deviceId, olmType: enc.type, ciphertext: enc.body};
                });
            }).catch(function (err) {
                console.warn('không mã hoá được cho thiết bị', t.device.deviceId, err);
                return null;
            });
        })).then(function (encrypted) {
            var perDevice = {};
            encrypted.forEach(function (e) { if (e) perDevice[e.deviceId] = {olmType: e.olmType, ciphertext: e.ciphertext}; });
            var envelope = {e2e: true, perDevice: perDevice, senderDeviceId: e2eDeviceId};
            if (plainBody && plainBody.replyTo && plainBody.replyTo.fromUserId) {
                envelope.replyTo = {fromUserId: plainBody.replyTo.fromUserId};
            }
            if (plainBody && plainBody.mentionedUserIds && plainBody.mentionedUserIds.length) {
                envelope.mentionedUserIds = plainBody.mentionedUserIds;
            }
            return envelope;
        });
    });
}

// Chọn (hoặc tạo) đúng Olm session để giải 1 ciphertext DM: dùng lại session cũ nếu còn khớp (tin
// ratchet thường, hoặc prekey message trùng session đã có do 2 bên cùng lúc tạo outbound -- xem
// olm.js README mục matches_inbound); ngược lại, nếu là prekey message thì tạo inbound session mới.
function e2eResolveDmSessionForDecrypt(fromUserId, senderDeviceId, mine) {
    return e2eLoadDmSession(fromUserId, senderDeviceId).then(function (session) {
        if (session && (mine.olmType === 1 || session.matches_inbound(mine.ciphertext))) {
            return {session: session, plaintext: session.decrypt(mine.olmType, mine.ciphertext)};
        }
        if (mine.olmType === 0) {
            var newSession = new Olm.Session();
            newSession.create_inbound(e2eAccount, mine.ciphertext);
            var plaintext = newSession.decrypt(mine.olmType, mine.ciphertext);
            e2eAccount.remove_one_time_keys(newSession);
            return e2eSaveAccount().then(function () { return {session: newSession, plaintext: plaintext}; });
        }
        throw new Error('không có session để giải mã tin này (olmType=1 nhưng chưa từng thiết lập session)');
    });
}

// Giải mã 1 tin nhận được từ fromUserId trong DM đã bật E2E -- fromUserId có thể là chính myUserId
// (đọc lại tin mình gửi từ thiết bị khác). Không giải mã được (không có phần dành cho thiết bị này
// trong perDevice, hoặc lỗi ratchet) thì trả placeholder rõ ràng thay vì throw làm vỡ pipeline render.
function e2eDecryptIncoming(fromUserId, envelope) {
    var mine = envelope && envelope.perDevice ? envelope.perDevice[e2eDeviceId] : null;
    if (!mine) {
        return Promise.resolve({message: '🔒 Tin nhắn đã mã hoá (không gửi cho thiết bị này -- có thể thiết bị vừa liên kết sau lúc tin được gửi)', e2eFailed: true});
    }
    var senderDeviceId = envelope.senderDeviceId;
    return e2eResolveDmSessionForDecrypt(fromUserId, senderDeviceId, mine)
        .then(function (result) {
            return e2eSaveDmSession(fromUserId, senderDeviceId, result.session).then(function () { return JSON.parse(result.plaintext); });
        })
        .catch(function (err) {
            console.warn('e2e decrypt lỗi', err);
            return {message: '🔒 Không giải mã được tin nhắn này (đổi thiết bị/trình duyệt khác?)', e2eFailed: true};
        });
}

// Cache plaintext cục bộ theo messageId -- Olm/Megolm là ratchet DÙNG 1 LẦN cho mỗi vị trí tin, decrypt
// xong không decrypt lại được chính ciphertext đó lần 2. Load lại lịch sử (F5, mở lại conversation) mà
// không cache sẽ cố decrypt lại ciphertext đã xử lý rồi -> lỗi ratchet, mất nội dung. Nên: decrypt 1
// lần, cache plaintext, mọi lần sau đọc cache -- kể cả tin của chính mình đọc qua fan-out từ thiết bị khác.
function e2eCachePlaintext(messageId, plainBody) {
    return e2eDbPut('msgPlaintext', myUserId + '|' + messageId, {body: plainBody});
}

function e2eGetCachedPlaintext(messageId) {
    return e2eDbGet('msgPlaintext', myUserId + '|' + messageId).then(function (stored) { return stored ? stored.body : null; });
}

// Mã hoá body TRƯỚC KHI gửi nếu conversation đã bật E2E -- gọi từ sendChatMessage (điểm vào duy nhất
// gửi MESSAGE, xem messaging-core.js). DM (đúng 1 người khác) dùng Olm 1-1; group dùng Megolm.
function e2eMaybeEncryptForSend(conversationId, plainBody) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv || !conv.e2eEnabled) return Promise.resolve(plainBody);
    if (!e2eReady) return Promise.reject(new Error('Mã hoá đầu cuối chưa sẵn sàng, thử lại sau'));
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length === 1) return e2eEncryptOutgoing(others[0], plainBody);
    return e2eEncryptGroupOutgoing(conversationId, conv.memberUserIds, plainBody);
}

// Giải mã body nhận về nếu là tin e2e -- dùng cho mọi nguồn (WS sống, GET /messages lịch sử,
// jump-to-message...). Luôn tra cache trước, chỉ decrypt khi cache miss thật. {@code body.megolm}
// quyết định dùng Olm 1-1 hay Megolm group.
function e2eResolveIncomingBody(fromUserId, body, messageId, conversationId) {
    if (!body || !body.e2e) return Promise.resolve(body);
    return e2eGetCachedPlaintext(messageId).then(function (cached) {
        if (cached) return cached;
        if (!e2eReady) return {message: '🔒 Tin nhắn đã mã hoá (thiết bị này chưa sẵn sàng đọc)', e2eFailed: true};
        var decryptPromise = body.megolm ? e2eDecryptGroupIncoming(conversationId, fromUserId, body) : e2eDecryptIncoming(fromUserId, body);
        return decryptPromise.then(function (plain) {
            // KHÔNG cache khi thất bại -- để lần sau còn thử lại (vd session key vừa nhận được qua
            // to-device); giữ lại phong bì gốc (xem e2eFailedEnvelopesByMessageId) cho lần thử đó.
            if (plain && plain.e2eFailed) {
                if (messageId) e2eFailedEnvelopesByMessageId[messageId] = {fromUserId: fromUserId, conversationId: conversationId, body: body};
                return plain;
            }
            if (messageId) delete e2eFailedEnvelopesByMessageId[messageId];
            return e2eCachePlaintext(messageId, plain).then(function () { return plain; });
        });
    });
}

// Thử giải mã lại mọi tin của conversationId đang kẹt ở trạng thái thất bại -- gọi sau khi vừa nhận 1
// khoá phiên Megolm mới (to-device, sống qua WS hoặc drain lúc offline, xem e2eHandleToDeviceItem) để
// màn hình đang mở tự cập nhật ngay, không bắt người dùng phải F5 mới thấy. Chỉ sửa các bubble đang
// hiển thị qua handleMessageEdited (đã có sẵn, dùng chung với tính năng sửa tin).
function e2eRetryFailedMessagesIn(conversationId) {
    var pendingIds = Object.keys(e2eFailedEnvelopesByMessageId).filter(function (id) {
        return e2eFailedEnvelopesByMessageId[id].conversationId === conversationId;
    });
    if (!pendingIds.length) return;
    pendingIds.forEach(function (messageId) {
        var entry = e2eFailedEnvelopesByMessageId[messageId];
        e2eResolveIncomingBody(entry.fromUserId, entry.body, messageId, entry.conversationId).then(function (resolvedBody) {
            if (!resolvedBody || !resolvedBody.e2eFailed) handleMessageEdited(conversationId, messageId, resolvedBody);
        });
    });
}

// Giống e2eResolveIncomingBody nhưng dùng riêng cho tin VỪA ĐƯỢC SỬA của người khác -- bỏ qua cache
// đọc (đang giữ bản cũ trước khi sửa), luôn decrypt ciphertext mới rồi ghi đè cache.
function e2eResolveEditedBody(fromUserId, body, messageId, conversationId) {
    if (!body || !body.e2e) return Promise.resolve(body);
    if (!e2eReady) return Promise.resolve({message: '🔒 Tin nhắn đã mã hoá (thiết bị này chưa sẵn sàng đọc)', e2eFailed: true});
    var decryptPromise = body.megolm ? e2eDecryptGroupIncoming(conversationId, fromUserId, body) : e2eDecryptIncoming(fromUserId, body);
    return decryptPromise.then(function (plain) {
        if (plain && plain.e2eFailed) return plain;
        return e2eCachePlaintext(messageId, plain).then(function () { return plain; });
    });
}

// Giải mã 1 lô tin theo đúng thứ tự ban đầu (Promise.all giữ index) -- dùng cho mọi chỗ nạp lịch sử
// hàng loạt (loadLatestPage, loadAroundReadCursor, maybeLoadOlder, jump-to-message).
function e2eResolveIncomingBatch(conversationId, messages) {
    return Promise.all(messages.map(function (m) {
        return e2eResolveIncomingBody(m.fromUserId, m.body, m.id, conversationId).then(function (resolvedBody) {
            return Object.assign({}, m, {body: resolvedBody});
        });
    }));
}

// ================= Group (Megolm) =================
// Khác Olm 1-1 (1 session dùng chung cho cả 2 chiều giữa đúng 2 thiết bị): Megolm là ratchet 1 CHIỀU,
// mỗi thiết bị tự giữ đúng 1 "outbound session" cho tin mình gửi (không share giữa các thiết bị của
// chính mình -- 2 thiết bị cùng ratchet 1 outbound session sẽ vô tình dùng chung message key), và giữ
// N inbound session (1 cho mỗi cặp người gửi + thiết bị của họ) để đọc tin của họ -- xem create()/
// session_key() (outbound) vs create(session_key) (inbound) trong olm.js README mục "Group chat".
// session_key() là bí mật dùng chung cho toàn bộ history của outbound session đó -- không gửi rõ, luôn
// mã hoá riêng cho từng thiết bị nhận qua đúng Olm 1-1 session (tái dùng e2eEncryptOutgoing), gửi qua
// POST /e2e/to-device (type "megolm_session"). Ciphertext Megolm của nội dung tin thật sự thì KHÔNG
// cần fan-out per-device -- 1 bản duy nhất, ai có đúng sessionId đều giải được.

// Key gắn theo (myUserId, e2eDeviceId) -- outbound session là của riêng thiết bị này cho
// conversationId đó, không share với thiết bị khác của chính mình.
//
// {@code initialSessionKey} = session_key() gọi ĐÚNG 1 LẦN lúc session.create(), lưu lại riêng --
// session_key() phản ánh vị trí ratchet HIỆN TẠI (không phải vị trí ban đầu), gọi lại nó sau khi đã
// gửi vài tin rồi phân phối cho 1 thiết bị "bắt kịp" sẽ chỉ cho họ đọc được tin TỪ LÚC ĐÓ trở đi, tạo
// khoảng trống không đọc được các tin gửi trước đó. Dùng lại đúng initialSessionKey cho mọi lần phân
// phối tới THÀNH VIÊN CŨ (xem e2eGetOrCreateOutboundGroupSession) là cách duy nhất đảm bảo họ luôn đọc
// lại được toàn bộ lịch sử của session.
function e2eLoadOutboundGroupSession(conversationId) {
    return e2eDbGet('groupOutbound', myUserId + '|' + e2eDeviceId + '|' + conversationId).then(function (stored) {
        if (!stored || !stored.pickle) return null;
        var session = new Olm.OutboundGroupSession();
        session.unpickle(e2ePickleKey, stored.pickle);
        return {session: session, distributedTo: stored.distributedTo || [], initialSessionKey: stored.initialSessionKey};
    });
}

// Cảnh báo bất cứ khi nào sessionId outbound của conversation này ĐỔI so với lần lưu trước -- không
// chỉ riêng 3 trường hợp đã biết (lần đầu gửi, sau restore, rotate do rời/kick nhóm), để phát hiện cả
// trường hợp đổi session ngoài dự tính (dấu hiệu bug) thay vì im lặng.
function e2eWarnOutboundSessionChanged(conversationId) {
    if (typeof showToast !== 'function') return;
    var conv = (typeof lastConvList !== 'undefined' ? lastConvList : []).filter(function (c) { return c.conversationId === conversationId; })[0];
    var label = conv && typeof conversationLabel === 'function' ? conversationLabel(conv) : conversationId.substring(0, 8) + '…';
    showToast('⚠ Session mã hoá nhóm "' + label + '" vừa đổi -- nên tải lại file backup mới');
}

function e2eSaveOutboundGroupSession(conversationId, session, distributedTo, initialSessionKey) {
    var key = myUserId + '|' + e2eDeviceId + '|' + conversationId;
    var newSessionId = session.session_id();
    return e2eDbGet('groupOutbound', key).then(function (prev) {
        if (prev && prev.pickle) {
            var prevSession = new Olm.OutboundGroupSession();
            prevSession.unpickle(e2ePickleKey, prev.pickle);
            if (prevSession.session_id() !== newSessionId) e2eWarnOutboundSessionChanged(conversationId);
            return e2eDbPut('groupOutbound', key,
                {pickle: session.pickle(e2ePickleKey), distributedTo: distributedTo, initialSessionKey: initialSessionKey});
        }
        // Không có groupOutbound cũ -- có thể là lần đầu gửi thật (không cảnh báo), HOẶC vừa restore
        // xong nên outbound bị mất trắng dù trước đó chính mình đã từng gửi tin trong conversation này
        // (groupInbound vẫn còn entry tự-export của chính mình từ backup) -- 2 trường hợp này chỉ phân
        // biệt được qua groupInbound, không thể dựa vào groupOutbound (luôn rỗng ở cả 2 trường hợp).
        return e2eDbGetAllForUser('groupInbound').then(function (entries) {
            var hadOwnHistory = entries.some(function (e) {
                var parts = e.key.split('|'); // key: myUserId|conversationId|senderUserId|sessionId
                return parts[1] === conversationId && parts[2] === myUserId;
            });
            if (hadOwnHistory) e2eWarnOutboundSessionChanged(conversationId);
            return e2eDbPut('groupOutbound', key,
                {pickle: session.pickle(e2ePickleKey), distributedTo: distributedTo, initialSessionKey: initialSessionKey});
        });
    });
}

function e2eLoadInboundGroupSession(conversationId, senderUserId, sessionId) {
    return e2eDbGet('groupInbound', myUserId + '|' + conversationId + '|' + senderUserId + '|' + sessionId).then(function (stored) {
        if (!stored || !stored.pickle) return null;
        var session = new Olm.InboundGroupSession();
        session.unpickle(e2ePickleKey, stored.pickle);
        return session;
    });
}

function e2eSaveInboundGroupSession(conversationId, senderUserId, sessionId, session) {
    return e2eDbPut('groupInbound', myUserId + '|' + conversationId + '|' + senderUserId + '|' + sessionId, {pickle: session.pickle(e2ePickleKey)});
}

// Gửi {@code sessionKey} (do người gọi cung cấp sẵn -- không tự gọi session_key() ở đây, xem javadoc
// e2eLoadOutboundGroupSession) cho 1 loạt người qua to-device -- mã hoá riêng từng người bằng Olm DM
// session 1-1 (tái dùng e2eEncryptOutgoing, đã tự fan-out mọi thiết bị hiện tại của người đó). Chưa tự
// động đồng bộ sessionKey này sang thiết bị KHÁC của CHÍNH MÌNH (to-device địa chỉ theo recipientUserId
// ở tầng server) -- thiết bị khác của mình đọc lại lịch sử qua "liên kết thiết bị"/sao lưu lạnh thay vì
// tự động ngay lúc gửi, giới hạn chấp nhận được. Trả về đúng danh sách recipientUserId GỬI THÀNH CÔNG
// (không phải tất cả) -- người gọi chỉ đánh dấu "đã phân phối" cho ai thật sự nhận được; lỗi 1 người
// không chặn kết quả của những người còn lại.
function e2eDistributeGroupSessionKey(conversationId, session, recipientUserIds, sessionKey) {
    if (!recipientUserIds.length) return Promise.resolve([]);
    var payload = {conversationId: conversationId, sessionId: session.session_id(), sessionKey: sessionKey};
    return Promise.all(recipientUserIds.map(function (recipientUserId) {
        return e2eEncryptOutgoing(recipientUserId, payload)
            .then(function (envelope) {
                return fetch(HISTORY_API_BASE + '/e2e/to-device', {
                    method: 'POST',
                    headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
                    body: JSON.stringify({deliveries: [{recipientUserId: recipientUserId, type: 'megolm_session', conversationId: conversationId, body: envelope}]})
                });
            })
            .then(function (res) { return res.ok ? recipientUserId : null; })
            .catch(function (err) { console.warn('không gửi được megolm session key cho', recipientUserId, err); return null; });
    })).then(function (results) { return results.filter(function (id) { return id !== null; }); });
}

// Danh sách "userId|deviceId" cho mọi thiết bị hiện tại của từng userId -- dùng SAU KHI đã gửi thành
// công để ghi sổ đúng thiết bị nào vừa nhận (e2eEncryptOutgoing bên trong đã tự fan-out mọi thiết bị
// hiện tại của người đó, nên coi như tất cả thiết bị hiện tại của 1 người "succeeded" đều đã nhận).
// Dùng e2eListDevicesForUser (không claim prekey) vì chỉ cần đọc danh sách để ghi sổ.
function e2eCurrentDeviceKeysFor(userIds) {
    return Promise.all(userIds.map(function (userId) {
        return e2eListDevicesForUser(userId).then(function (devices) {
            return devices.map(function (d) { return userId + '|' + d.deviceId; });
        });
    })).then(function (lists) { return lists.reduce(function (acc, l) { return acc.concat(l); }, []); });
}

// Gửi {@code sessionKey} cho các THIẾT BỊ KHÁC CỦA CHÍNH MÌNH đang online cùng lúc -- để tin nhóm MÌNH
// vừa gửi hiện ra ngay ở thiết bị khác của mình (giống Signal/WhatsApp: mọi tin mới đều mã hoá gửi cho
// MỌI thiết bị hiện tại của người gửi, xem thảo luận). CHỈ áp dụng cho session đang được TẠO/GỬI BÂY
// GIỜ -- không phải cơ chế "tự động đọc lại lịch sử cũ" cho 1 thiết bị mới đăng nhập (cái đó cố ý
// không tự động, chỉ qua liên kết thiết bị/backup). Viết riêng thay vì tái dùng
// e2eEncryptOutgoing/e2eDistributeGroupSessionKey với peerUserId=myUserId -- gọi vậy sẽ tự đếm luôn
// CHÍNH thiết bị đang gửi vào đích (peerDevices không loại trừ e2eDeviceId) và claim trùng prekey 2
// lần cho cùng 1 thiết bị khác trong 1 lượt.
function e2eDistributeGroupSessionKeyToOwnDevices(conversationId, session, sessionKey) {
    return e2eFetchDeviceBundles(myUserId).then(function (devices) {
        var others = devices.filter(function (d) { return d.deviceId !== e2eDeviceId; });
        if (!others.length) return [];
        var payload = {conversationId: conversationId, sessionId: session.session_id(), sessionKey: sessionKey};
        var plaintext = JSON.stringify(payload);
        return Promise.all(others.map(function (device) {
            return e2eGetOrCreateDmSessionForDevice(myUserId, device).then(function (dmSession) {
                var enc = dmSession.encrypt(plaintext);
                return e2eSaveDmSession(myUserId, device.deviceId, dmSession).then(function () {
                    var perDevice = {};
                    perDevice[device.deviceId] = {olmType: enc.type, ciphertext: enc.body};
                    var envelope = {e2e: true, perDevice: perDevice, senderDeviceId: e2eDeviceId};
                    return fetch(HISTORY_API_BASE + '/e2e/to-device', {
                        method: 'POST',
                        headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
                        body: JSON.stringify({deliveries: [{recipientUserId: myUserId, type: 'megolm_session', conversationId: conversationId, body: envelope}]})
                    });
                });
            }).then(function (res) { return res.ok ? device.deviceId : null; })
                .catch(function (err) { console.warn('không đồng bộ được session nhóm sang thiết bị khác của mình', device.deviceId, err); return null; });
        })).then(function (results) { return results.filter(function (id) { return id !== null; }); });
    }).catch(function (err) { console.warn('không lấy được danh sách thiết bị khác của mình', err); return []; });
}

// Thiết bị khác của chính mình có đang thiếu sessionKey hiện tại không? Coi như luôn "bắt kịp" (không
// có khái niệm backward secrecy giữa các thiết bị của CHÍNH MÌNH -- đã là mình thì đọc toàn bộ lịch sử
// của mình luôn hợp lý).
function e2eHasOwnDevicesPending(distributedTo) {
    return e2eListDevicesForUser(myUserId).then(function (devices) {
        return devices.some(function (d) { return d.deviceId !== e2eDeviceId && distributedTo.indexOf(myUserId + '|' + d.deviceId) === -1; });
    });
}

// Trong số {@code others}, ai đang có ít nhất 1 thiết bị chưa nằm trong {@code distributedTo}? Tách
// riêng 2 loại (xem e2eGetOrCreateOutboundGroupSession's javadoc):
//  - catchUpIds: THÀNH VIÊN CŨ vừa đổi/thêm thiết bị (đã có ít nhất 1 thiết bị khác từng nhận session
//    này rồi) -- phải nhận initialSessionKey để đọc lại được TOÀN BỘ lịch sử, không có khoảng trống.
//  - newMemberIds: thành viên MỚI thật sự (chưa từng có thiết bị nào nhận) -- phải nhận khoá HIỆN TẠI,
//    không phải initialSessionKey, để giữ đúng backward secrecy (không đọc được lịch sử trước lúc vào nhóm).
function e2eFindPendingGroupRecipients(others, distributedTo) {
    var hasAnyDeviceOfUser = function (userId) {
        return distributedTo.some(function (entry) { return entry.indexOf(userId + '|') === 0; });
    };
    return Promise.all(others.map(function (userId) {
        return e2eListDevicesForUser(userId).then(function (devices) {
            var hasMissingDevice = devices.some(function (d) { return distributedTo.indexOf(userId + '|' + d.deviceId) === -1; });
            if (!hasMissingDevice) return null;
            return {userId: userId, catchingUp: hasAnyDeviceOfUser(userId)};
        });
    })).then(function (results) {
        var pending = results.filter(function (r) { return r !== null; });
        return {
            catchUpIds: pending.filter(function (r) { return r.catchingUp; }).map(function (r) { return r.userId; }),
            newMemberIds: pending.filter(function (r) { return !r.catchingUp; }).map(function (r) { return r.userId; })
        };
    });
}

// Phân phối bù cho catchUpIds/newMemberIds (+ thiết bị khác của chính mình nếu {@code ownPending})
// rồi ghi lại distributedTo + lưu session -- tách riêng khỏi e2eGetOrCreateOutboundGroupSession để hàm
// đó chỉ còn lo "cần phân phối bù hay không".
function e2eRecordGroupDistribution(conversationId, session, distributedTo, initialSessionKey, catchUpIds, newMemberIds, ownPending) {
    return Promise.all([
        e2eDistributeGroupSessionKey(conversationId, session, catchUpIds, initialSessionKey),
        e2eDistributeGroupSessionKey(conversationId, session, newMemberIds, session.session_key()),
        ownPending ? e2eDistributeGroupSessionKeyToOwnDevices(conversationId, session, initialSessionKey) : Promise.resolve([])
    ]).then(function (successLists) {
        // Chỉ thêm người/thiết bị THẬT SỰ nhận được -- ai gửi lỗi vẫn ở lại pending, lần gửi tin kế
        // tiếp tự thử lại, không bị đánh dấu nhầm "đã phân phối" rồi không bao giờ thử lại nữa.
        var succeededUserIds = successLists[0].concat(successLists[1]);
        var ownSucceededDeviceIds = successLists[2];
        return e2eCurrentDeviceKeysFor(succeededUserIds).then(function (newKeys) {
            var newDistributedTo = distributedTo.slice();
            newKeys.forEach(function (k) { if (newDistributedTo.indexOf(k) === -1) newDistributedTo.push(k); });
            ownSucceededDeviceIds.forEach(function (deviceId) {
                var k = myUserId + '|' + deviceId;
                if (newDistributedTo.indexOf(k) === -1) newDistributedTo.push(k);
            });
            return e2eSaveOutboundGroupSession(conversationId, session, newDistributedTo, initialSessionKey).then(function () {
                return {session: session, distributedTo: newDistributedTo, initialSessionKey: initialSessionKey};
            });
        });
    });
}

// Lấy (hoặc tạo mới) outbound Megolm session của CHÍNH THIẾT BỊ NÀY cho conversationId, đảm bảo mọi
// thiết bị hiện tại của mọi thành viên khác (VÀ thiết bị khác của chính mình) đã nhận được
// session_key -- phân phối bù cho ai còn thiếu.
function e2eGetOrCreateOutboundGroupSession(conversationId, memberUserIds) {
    var others = memberUserIds.filter(function (id) { return id !== myUserId; });
    return e2eLoadOutboundGroupSession(conversationId).then(function (existing) {
        var session = existing ? existing.session : new Olm.OutboundGroupSession();
        var initialSessionKey = existing ? existing.initialSessionKey : null;
        if (!initialSessionKey) {
            // Session mới toanh, HOẶC session tạo từ trước bản có initialSessionKey (đành lấy tạm khoá
            // hiện tại làm phương án dự phòng -- tự "chữa lành" ngay lần rotate/tạo lại kế tiếp).
            if (!existing) session.create();
            initialSessionKey = session.session_key(); // gọi đúng 1 lần ở đây, xem javadoc phía trên
        }
        var distributedTo = existing ? existing.distributedTo : [];
        return Promise.all([
            e2eFindPendingGroupRecipients(others, distributedTo),
            e2eHasOwnDevicesPending(distributedTo)
        ]).then(function (checkResults) {
            var pending = checkResults[0], ownPending = checkResults[1];
            if (!pending.catchUpIds.length && !pending.newMemberIds.length && !ownPending) {
                return {session: session, distributedTo: distributedTo, initialSessionKey: initialSessionKey};
            }
            return e2eRecordGroupDistribution(conversationId, session, distributedTo, initialSessionKey, pending.catchUpIds, pending.newMemberIds, ownPending);
        });
    });
}

// Bắt buộc tạo session MỚI (bỏ hẳn session cũ) -- gọi khi phát hiện có người vừa bị kick/rời khỏi 1
// group đã mã hoá (xem e2eCheckGroupRotations), để họ không đọc được tin mình gửi SAU thời điểm đó
// (vẫn đọc được tin cũ, vì đã giữ sẵn session_key cũ -- Megolm nhóm không có forward-secrecy
// per-message như DM, chỉ rotate khi rời/bị kick). Mọi thành viên còn lại (và thiết bị khác của chính
// mình) đều là "gốc" của session mới này -- dùng chung đường phân phối "catch up" (initialSessionKey)
// của e2eRecordGroupDistribution, không có ai thuộc diện "thành viên mới cần giới hạn backward secrecy".
function e2eRotateOutboundGroupSession(conversationId, memberUserIds) {
    var session = new Olm.OutboundGroupSession();
    session.create();
    var initialSessionKey = session.session_key();
    var others = memberUserIds.filter(function (id) { return id !== myUserId; });
    return e2eRecordGroupDistribution(conversationId, session, [], initialSessionKey, others, [], true);
}

function e2eEncryptGroupOutgoing(conversationId, memberUserIds, plainBody) {
    return e2eGetOrCreateOutboundGroupSession(conversationId, memberUserIds).then(function (result) {
        var plaintext = JSON.stringify(plainBody);
        var ciphertext = result.session.encrypt(plaintext);
        var sessionId = result.session.session_id();
        return e2eSaveOutboundGroupSession(conversationId, result.session, result.distributedTo, result.initialSessionKey).then(function () {
            var envelope = {e2e: true, megolm: true, sessionId: sessionId, ciphertext: ciphertext};
            if (plainBody && plainBody.replyTo && plainBody.replyTo.fromUserId) envelope.replyTo = {fromUserId: plainBody.replyTo.fromUserId};
            if (plainBody && plainBody.mentionedUserIds && plainBody.mentionedUserIds.length) envelope.mentionedUserIds = plainBody.mentionedUserIds;
            return envelope;
        });
    });
}

// Giải 1 tin group -- tra đúng inbound session theo (conversationId, fromUserId, sessionId) vì mỗi
// người gửi có session riêng. Chưa có session (to-device chưa kịp tới, mình vào nhóm sau lúc session
// được tạo, hoặc thiết bị khác của người gửi mà session_key chưa từng đồng bộ tới) thì báo lỗi rõ ràng
// thay vì throw làm vỡ pipeline render -- KHÔNG cache (xem e2eResolveIncomingBody) để lần mở lại sau
// còn tự thử lại nếu khoá đã tới.
function e2eDecryptGroupIncoming(conversationId, fromUserId, envelope) {
    return e2eLoadInboundGroupSession(conversationId, fromUserId, envelope.sessionId)
        .then(function (session) {
            if (!session) throw new Error('chưa nhận được khoá phiên (session key) của người này');
            var result = session.decrypt(envelope.ciphertext);
            return e2eSaveInboundGroupSession(conversationId, fromUserId, envelope.sessionId, session)
                .then(function () { return JSON.parse(result.plaintext); });
        })
        .catch(function (err) {
            console.warn('e2e group decrypt lỗi', err);
            return {message: '🔒 Không giải mã được tin nhắn nhóm này (đang chờ nhận khoá?)', e2eFailed: true};
        });
}

// Xử lý 1 tin to-device (dùng chung cho relay sống qua WS -- xem history-ws.js case "E2E_TO_DEVICE" --
// và drain lúc connect lại, xem e2eDrainToDevice). An toàn khi cùng lúc nhiều thiết bị của cùng 1 user
// đều nhận được relay/drain của 1 item -- e2eDecryptIncoming tự bỏ qua nếu {@code perDevice} không có
// phần dành cho thiết bị này. 2 loại: phân phối Megolm session key; hoặc gói chuyển giao lịch sử lúc
// liên kết thiết bị (xem e2eApproveDeviceLink/e2eHandleDeviceLinkPayload bên dưới) -- KHÔNG có đường
// tự động nào khác: chỉ 2 đường CÓ hành động rõ ràng của người dùng (nhập mã liên kết thiết bị, hoặc
// phục hồi từ file backup) mới được cấp quyền đọc lại lịch sử.
function e2eHandleToDeviceItem(type, senderUserId, conversationId, olmEnvelope) {
    if (type === 'device_link_payload') return e2eHandleDeviceLinkPayload(olmEnvelope);
    if (type !== 'megolm_session' || !olmEnvelope) return Promise.resolve();
    return e2eDecryptIncoming(senderUserId, olmEnvelope).then(function (payload) {
        if (!payload || payload.e2eFailed || !payload.sessionKey || !payload.sessionId) return;
        var session = new Olm.InboundGroupSession();
        session.create(payload.sessionKey);
        var resolvedConversationId = payload.conversationId || conversationId;
        return e2eSaveInboundGroupSession(resolvedConversationId, senderUserId, payload.sessionId, session).then(function () {
            // Khoá vừa lưu xong -- màn hình (nếu đang mở đúng conversation này) có thể đang giữ vài
            // bubble cũ kẹt ở nhãn lỗi từ trước lúc khoá tới, tự thử lại ngay thay vì đợi F5.
            e2eRetryFailedMessagesIn(resolvedConversationId);
        });
    });
}

// Bù các tin to-device gửi lúc mình offline (relay sống qua WS lúc đó không tới đâu được) -- gọi 1 lần
// lúc enterApp(), xử lý tuần tự (không Promise.all) để giữ đúng thứ tự nếu có nhiều lần rotate liên
// tiếp trên cùng 1 conversation.
function e2eDrainToDevice() {
    if (!e2eReady) return Promise.resolve();
    return fetch(HISTORY_API_BASE + '/e2e/to-device', {headers: {'Authorization': 'Bearer ' + authToken}})
        .then(function (res) { return res.ok ? res.json() : []; })
        .then(function (items) {
            return items.reduce(function (chain, item) {
                return chain.then(function () { return e2eHandleToDeviceItem(item.type, item.senderUserId, item.conversationId, item.body); });
            }, Promise.resolve());
        })
        .catch(function (err) { console.warn('không drain được e2e to-device', err); });
}

// === Liên kết thiết bị (gõ tay mã 6 ký tự) ===
// Mỗi thiết bị tự có identity riêng ngay từ đầu (xem e2eInit) -- tính năng này chỉ còn 1 việc: chuyển
// giao LỊCH SỬ ĐÃ GIẢI MÃ (msgPlaintext + Megolm inbound session) từ 1 thiết bị cũ sang thiết bị mới,
// giống "message history sync" của Signal (https://signal.org/blog/a-synchronized-start-for-linked-devices/
// -- Signal cũng không chia sẻ session sống, chỉ đóng gói lịch sử đã giải mã sẵn rồi chuyển 1 lần).
//
// Cơ chế: thiết bị MỚI tự tạo 1 Olm.Account tạm (chỉ dùng 1 lần cho việc chuyển giao, khác Account
// thật đã có sẵn từ e2eInit) + xin server 1 mã 6 ký tự ngắn hạn (5 phút). Thiết bị CŨ gõ mã đó, lấy
// bundle tạm của thiết bị mới, mã hoá gói {msgPlaintext, groupInbound} của mình bằng 1 Olm session
// outbound bình thường (dùng 1 lần rồi bỏ), gửi qua POST /e2e/to-device sẵn có, địa chỉ đến CHÍNH MÌNH.
//
// Giới hạn thật sự (lý do an toàn, giống Signal): KHÔNG chuyển session DM 1-1 hay Megolm OUTBOUND
// session -- đây là ratchet dùng để GỬI, nếu 2 thiết bị cùng giữ 1 bản rồi cùng mã hoá trước khi đồng
// bộ lại, cả 2 sẽ vô tình dùng chung 1 message key cho 2 tin khác nhau. Thiết bị mới tự thiết lập
// session gửi riêng khi cần, độc lập, tự fan-out per-device như mọi thiết bị khác.
var e2eLinkAccount = null; // Olm.Account tạm, chỉ tồn tại lúc đang chờ được liên kết (thiết bị mới)
var e2eLinkCode = null; // mã đang chờ khớp -- phòng nhận nhầm payload của 1 phiên xin mã cũ/khác
var e2eLinkWaiters = [];

// Thiết bị muốn LẤY lịch sử từ thiết bị khác gọi hàm này để xin 1 mã liên kết.
function e2eRequestDeviceLink() {
    if (typeof Olm === 'undefined') return Promise.reject(new Error('Mã hoá đầu cuối chưa sẵn sàng ở trình duyệt này'));
    return Olm.init({locateFile: function () { return 'vendor/olm.wasm'; }}).then(function () {
        var account = new Olm.Account();
        account.create();
        account.generate_one_time_keys(1);
        var identityKey = JSON.parse(account.identity_keys()).curve25519;
        var otks = JSON.parse(account.one_time_keys()).curve25519;
        var keyId = Object.keys(otks)[0];
        return fetch(HISTORY_API_BASE + '/e2e/device-link/request', {
            method: 'POST',
            headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
            body: JSON.stringify({identityKey: identityKey, oneTimeKeyId: keyId, oneTimeKey: otks[keyId]})
        }).then(function (res) {
            if (!res.ok) throw new Error('không xin được mã liên kết, thử lại sau');
            return res.json();
        }).then(function (data) {
            e2eLinkAccount = account;
            e2eLinkCode = data.code;
            return data; // {code, expiresInSeconds}
        });
    });
}

// Chờ payload liên kết tới -- không polling, dựa vào relay sống WS (case "E2E_TO_DEVICE" trong
// history-ws.js) tự gọi e2eHandleToDeviceItem -> e2eHandleDeviceLinkPayload -> resolve waiter ở đây.
function e2eWaitForDeviceLink(timeoutMs) {
    return new Promise(function (resolve, reject) {
        var entry = {resolve: null};
        var timer = setTimeout(function () {
            var idx = e2eLinkWaiters.indexOf(entry);
            if (idx !== -1) e2eLinkWaiters.splice(idx, 1);
            reject(new Error('Hết thời gian chờ liên kết, thử lấy mã mới'));
        }, timeoutMs || 300000);
        entry.resolve = function (v) { clearTimeout(timer); resolve(v); };
        e2eLinkWaiters.push(entry);
    });
}

function e2eCancelDeviceLinkWait() {
    e2eLinkAccount = null;
    e2eLinkCode = null;
    e2eLinkWaiters = [];
}

// Nhận gói {msgPlaintext, groupInbound} đã giải mã, nhập thẳng vào storage của thiết bị này.
function e2eApplyLinkedHistory(transfer) {
    return e2eDbPutAll('msgPlaintext', transfer.msgPlaintext)
        .then(function () { return e2eImportGroupInboundEntries(transfer.groupInbound); })
        .then(function () {
            e2eLinkAccount = null;
            e2eLinkCode = null;
        });
}

// Gọi từ e2eHandleToDeviceItem khi tới đúng loại 'device_link_payload' -- giải mã bằng Account tạm
// (e2eLinkAccount), rồi nhập lịch sử vừa nhận. Không khớp mã đang chờ (payload của 1 phiên xin mã khác,
// vd tab cũ chưa đóng) thì bỏ qua im lặng.
function e2eHandleDeviceLinkPayload(envelope) {
    if (!e2eLinkAccount || !envelope || envelope.code !== e2eLinkCode) return Promise.resolve();
    try {
        var session = new Olm.Session();
        session.create_inbound(e2eLinkAccount, envelope.ciphertext);
        var plaintext = session.decrypt(envelope.olmType, envelope.ciphertext);
        e2eLinkAccount.remove_one_time_keys(session);
        var transfer = JSON.parse(plaintext);
        return e2eApplyLinkedHistory(transfer).then(function () {
            var waiters = e2eLinkWaiters;
            e2eLinkWaiters = [];
            waiters.forEach(function (w) { w.resolve(transfer); });
        });
    } catch (err) {
        console.warn('e2e device-link giải mã lỗi', err);
        return Promise.resolve();
    }
}

// Thiết bị CÓ SẴN lịch sử gọi sau khi user gõ đúng mã hiển thị trên thiết bị mới -- lấy bundle tạm của
// thiết bị mới, gói toàn bộ cache plaintext + Megolm inbound session của mình, mã hoá bằng 1 Olm
// session outbound bình thường rồi gửi qua to-device, địa chỉ đến CHÍNH MÌNH (userId khác của thiết bị
// mới cùng 1 tài khoản, WS relay đã hỗ trợ multi-session cho cùng userId).
function e2eApproveDeviceLink(code) {
    if (!e2eReady) return Promise.reject(new Error('Thiết bị này chưa sẵn sàng, thử lại sau'));
    return fetch(HISTORY_API_BASE + '/e2e/device-link/bundle?code=' + encodeURIComponent(code), {
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (res.status === 404) throw new Error('Mã không đúng hoặc đã hết hạn');
        if (!res.ok) throw new Error('Không lấy được thông tin thiết bị mới, thử lại sau');
        return res.json();
    }).then(function (bundle) {
        return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groupInbound'), e2eDbGetAllForUser('groupOutbound')]).then(function (dumps) {
            var session = new Olm.Session();
            session.create_outbound(e2eAccount, bundle.identityKey, bundle.oneTimeKey);
            var groupInbound = e2eExportGroupInboundEntries(dumps[1]).concat(e2eExportOwnOutboundAsInboundEntries(dumps[2]));
            var transfer = {msgPlaintext: dumps[0], groupInbound: groupInbound};
            var enc = session.encrypt(JSON.stringify(transfer));
            return fetch(HISTORY_API_BASE + '/e2e/to-device', {
                method: 'POST',
                headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
                body: JSON.stringify({deliveries: [{recipientUserId: myUserId, type: 'device_link_payload', body: {code: code, olmType: enc.type, ciphertext: enc.body}}]})
            });
        });
    }).then(function (res) {
        if (!res.ok) throw new Error('Gửi liên kết thất bại, thử lại sau');
    });
}

// So sánh danh sách thành viên cũ (trước lần refresh này) với mới cho từng group đã mã hoá -- ai đó
// vừa biến mất (bị kick/tự rời) mà mình đã từng gửi khoá outbound session hiện tại cho họ thì phải
// rotate. Gọi sau mỗi lần refreshConversationList() -- cách duy nhất phát hiện "vừa có người rời" phía
// những thành viên còn lại (server chỉ báo sống qua WS riêng cho đúng người bị xoá).
function e2eCheckGroupRotations(oldList, newList) {
    if (!e2eReady || !oldList.length) return;
    newList.forEach(function (conv) {
        if (!conv.e2eEnabled) return;
        var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
        if (others.length <= 1) return; // DM -- không có khái niệm "kick" kiểu group ở đây
        var old = oldList.filter(function (c) { return c.conversationId === conv.conversationId; })[0];
        if (!old) return;
        var removed = old.memberUserIds.filter(function (id) { return id !== myUserId && conv.memberUserIds.indexOf(id) === -1; });
        if (!removed.length) return;
        e2eLoadOutboundGroupSession(conv.conversationId).then(function (existing) {
            if (!existing) return; // mình chưa từng gửi gì trong group này -- không có session nào để rotate
            // distributedTo lưu theo "userId|deviceId" -- so khớp prefix "id|" thay vì so tuyệt đối.
            var needsRotate = removed.some(function (id) {
                return existing.distributedTo.some(function (entry) { return entry.indexOf(id + '|') === 0; });
            });
            if (needsRotate) return e2eRotateOutboundGroupSession(conv.conversationId, conv.memberUserIds);
        }).catch(function (err) { console.warn('rotate megolm session lỗi', err); });
    });
}

// === Backup lạnh (xuất/nhập file mã hoá cục bộ, không bao giờ qua server) ===
// Đối chiếu cách Matrix/Signal/WhatsApp làm: Matrix chỉ backup KHOÁ Megolm vì server họ giữ ciphertext
// vĩnh viễn; Signal/WhatsApp backup thẳng nội dung đã giải mã vì server họ không giữ ciphertext lâu
// dài. App này giống Matrix (bảng {@code messages} giữ ciphertext vĩnh viễn) nên với GROUP, backup
// {@code groupInbound} (khoá Megolm) là đủ và mạnh hơn backup nội dung; nhưng với DM (Olm Double
// Ratchet 2 chiều, không backup session sống an toàn được -- cùng lý do ở mục "Liên kết thiết bị"),
// buộc phải backup thẳng {@code msgPlaintext}. Kết hợp cả 2 -- đúng nguyên gói {msgPlaintext,
// groupInbound} đã dùng cho liên kết thiết bị, chỉ đổi kênh mã hoá.
//
// 2 cách bảo vệ file (người dùng tự chọn, giống Matrix Security Key/Security Phrase):
//  - "key": 32 byte ngẫu nhiên (256-bit, dùng thẳng làm khoá AES) -- hiện 1 lần duy nhất dạng 64 ký tự
//    hex để người dùng tự lưu, giống "khoá khôi phục" của Signal/WhatsApp. Không lưu lại ở đâu trong
//    app -- mất là mất thật.
//  - "phrase": mật khẩu tự đặt, entropy thấp hơn nên qua PBKDF2 (210k vòng, khuyến nghị OWASP 2023) +
//    salt ngẫu nhiên trước khi dùng làm khoá AES.

var E2E_BACKUP_PBKDF2_ITERATIONS = 210000;

function e2eBytesToHex(bytes) {
    return Array.from(bytes).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
}
function e2eHexToBytes(hex) {
    hex = hex.replace(/[^0-9a-fA-F]/g, '');
    var bytes = new Uint8Array(Math.floor(hex.length / 2));
    for (var i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
    return bytes;
}
function e2eAbToBase64(buf) {
    return btoa(String.fromCharCode.apply(null, new Uint8Array(buf)));
}
function e2eBase64ToAb(b64) {
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return bytes.buffer;
}

function e2eDeriveKeyFromPhrase(phrase, saltBytes) {
    return crypto.subtle.importKey('raw', new TextEncoder().encode(phrase), 'PBKDF2', false, ['deriveKey'])
        .then(function (baseKey) {
            return crypto.subtle.deriveKey(
                {name: 'PBKDF2', salt: saltBytes, iterations: E2E_BACKUP_PBKDF2_ITERATIONS, hash: 'SHA-256'},
                baseKey, {name: 'AES-GCM', length: 256}, false, ['encrypt', 'decrypt']);
        });
}

function e2eImportRawAesKey(keyBytes) {
    return crypto.subtle.importKey('raw', keyBytes, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

// Tạo nội dung file backup -- {@code mode} 'key' (random 256-bit, trả kèm recoveryKeyHex để UI hiện
// cho user lưu lại ngay, không lấy lại lần 2 được) hoặc 'phrase' (mật khẩu tự đặt). Trả
// {@code {fileContent, recoveryKeyHex?}} -- fileContent là chuỗi JSON để UI tải xuống thành file.
function e2eCreateBackup(mode, phrase) {
    return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groupInbound'), e2eDbGetAllForUser('groupOutbound')]).then(function (dumps) {
        var groupInbound = e2eExportGroupInboundEntries(dumps[1]).concat(e2eExportOwnOutboundAsInboundEntries(dumps[2]));
        var payload = JSON.stringify({msgPlaintext: dumps[0], groupInbound: groupInbound});
        var iv = crypto.getRandomValues(new Uint8Array(12));
        var keyPromise, recoveryKeyHex = null, saltBytes = null;
        if (mode === 'key') {
            var keyBytes = crypto.getRandomValues(new Uint8Array(32));
            recoveryKeyHex = e2eBytesToHex(keyBytes);
            keyPromise = e2eImportRawAesKey(keyBytes);
        } else {
            saltBytes = crypto.getRandomValues(new Uint8Array(16));
            keyPromise = e2eDeriveKeyFromPhrase(phrase, saltBytes);
        }
        return keyPromise.then(function (cryptoKey) {
            return crypto.subtle.encrypt({name: 'AES-GCM', iv: iv}, cryptoKey, new TextEncoder().encode(payload));
        }).then(function (ciphertext) {
            var file = {v: 1, mode: mode, iv: e2eAbToBase64(iv), ciphertext: e2eAbToBase64(ciphertext)};
            if (saltBytes) file.salt = e2eAbToBase64(saltBytes);
            return {fileContent: JSON.stringify(file), recoveryKeyHex: recoveryKeyHex};
        });
    });
}

// Khôi phục từ nội dung file backup + khoá/mật khẩu tương ứng đúng {@code file.mode} -- import thẳng
// vào storage của thiết bị này, không đụng gì tới identity (giống e2eApplyLinkedHistory, chỉ khác
// nguồn dữ liệu là file thay vì kênh Olm sống). Sai khoá/mật khẩu hoặc file hỏng -- AES-GCM tự phát
// hiện (authenticated encryption) -- báo lỗi rõ ràng thay vì import nhầm dữ liệu vô nghĩa.
function e2eRestoreBackup(fileContent, secret) {
    var file;
    try { file = JSON.parse(fileContent); } catch (e) { return Promise.reject(new Error('File backup không hợp lệ')); }
    if (!file || !file.v || !file.ciphertext || !file.iv) return Promise.reject(new Error('File backup không hợp lệ'));
    var iv = new Uint8Array(e2eBase64ToAb(file.iv));
    var keyPromise = file.mode === 'phrase'
        ? e2eDeriveKeyFromPhrase(secret, new Uint8Array(e2eBase64ToAb(file.salt)))
        : e2eImportRawAesKey(e2eHexToBytes(secret));
    return keyPromise.then(function (cryptoKey) {
        return crypto.subtle.decrypt({name: 'AES-GCM', iv: iv}, cryptoKey, e2eBase64ToAb(file.ciphertext));
    }).then(function (plainBuf) {
        var transfer = JSON.parse(new TextDecoder().decode(plainBuf));
        return e2eDbPutAll('msgPlaintext', transfer.msgPlaintext).then(function () {
            return e2eImportGroupInboundEntries(transfer.groupInbound);
        });
    }).catch(function (err) {
        console.warn('e2e restore backup lỗi', err);
        throw new Error('Sai khoá/mật khẩu hoặc file backup bị hỏng');
    });
}
