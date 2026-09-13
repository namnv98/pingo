// Mã hoá đầu cuối (E2E) -- dùng thư viện Olm thật (Double Ratchet + Megolm, xem frontend/vendor/olm.js),
// KHÔNG tự viết crypto. MỖI THIẾT BỊ (không phải mỗi user) tự có 1 identity key RIÊNG -- đúng thuật
// toán Sesame của Signal thật (xem https://signal.org/docs/specifications/sesame/): 1 user nhiều
// thiết bị = nhiều identity độc lập, gửi tin phải mã hoá RIÊNG cho TỪNG thiết bị của người nhận (fan-
// out), không dùng chung 1 identity giữa các thiết bị (bản đầu tiên làm vậy, gây xung đột + sai an
// toàn -- xem lịch sử users.e2e_identity_key trong postgres/helm/templates/configmap.yaml).
//
// Cách hoạt động DM (KHÔNG cần hàng đợi to-device riêng -- khác group): tin ĐẦU TIÊN gửi cho 1 THIẾT
// BỊ chưa từng chat mã hoá cùng sẽ tự động là "PREKEY message" (Olm tự nhúng sẵn mọi thứ cần để thiết
// bị đó tạo session, xem olm.js README mục create_outbound/create_inbound) -- cưỡi thẳng lên đúng
// đường MESSAGE thường đang có sẵn. 1 tin logic = 1 envelope chứa NHIỀU ciphertext (1 cho mỗi thiết
// bị đích, gồm cả thiết bị của người nhận LẪN các thiết bị KHÁC của CHÍNH MÌNH để tự đọc lại được tin
// mình gửi) -- xem {@code perDevice} trong e2eEncryptOutgoing/e2eDecryptIncoming.
//
// Private key KHÔNG BAO GIỜ rời máy: Olm.Account/Olm.Session pickle (serialize, mã hoá bằng 1 khoá
// cục bộ random sinh 1 lần, lưu localStorage) rồi lưu trong IndexedDB -- xoá dữ liệu trình duyệt =
// mất khả năng đọc lịch sử mã hoá cũ (chấp nhận được, giống mọi client Olm/Signal khác chạy trong
// trình duyệt) -- có thể lấy lại lịch sử ĐÃ TỪNG giải mã từ 1 thiết bị khác cùng tài khoản qua tính
// năng "liên kết thiết bị" (mã 6 ký tự, gõ tay, xem mục cuối file), giống Signal thật.

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

// Lấy TOÀN BỘ entry của storeName thuộc VỀ MÌNH (key bắt đầu bằng "myUserId|") -- dùng để đóng gói
// chuyển giao lúc liên kết thiết bị (xem e2eApproveDeviceLink); lọc theo prefix để KHÔNG lỡ kèm theo
// dữ liệu của tài khoản KHÁC từng đăng nhập chung trình duyệt này (IndexedDB chia theo origin, không
// theo user, xem javadoc e2eGetOrCreatePickleKey).
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

// Nhập lại 1 lô entry (key/value) vào storeName -- dùng ở đầu NHẬN của liên kết thiết bị, entries
// đã kèm sẵn đúng key (myUserId|...) từ thiết bị gửi, cùng 1 user nên copy thẳng không cần đổi key.
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

// Đóng gói 'groupInbound' để CHUYỂN SANG THIẾT BỊ KHÁC (liên kết thiết bị HOẶC sao lưu lạnh) --
// KHÔNG được dump thẳng {@code stored.pickle} như e2eDbGetAllForUser (bug thật đã gặp lúc test: tin
// gap vẫn "chưa nhận được khoá" dù session ĐÃ tới nơi) -- pickle là Olm.Session#pickle(e2ePickleKey)
// MÃ HOÁ BẰNG e2ePickleKey CỦA THIẾT BỊ NÀY, còn thiết bị NHẬN có e2ePickleKey RIÊNG của NÓ (sinh 1
// lần/trình duyệt, không bao giờ transfer, xem javadoc e2eGetOrCreatePickleKey) -- unpickle bằng khoá
// khác sẽ ném OLM.BAD_ACCOUNT_KEY, không đơn thuần "thiếu key" mà là hỏng vĩnh viễn (đè mất session
// ĐÚNG nếu bản backup cũ được import SAU 1 session mới đã nhận đúng). Phải giải phóng khỏi pickle
// key bằng {@code export_session(first_known_index())} (Olm hỗ trợ sẵn, KHÔNG lệ thuộc pickle key
// của bất kỳ thiết bị nào) -- xem e2eImportGroupInboundEntries phía dưới, làm ngược lại lúc nhập.
function e2eExportGroupInboundEntries(entries) {
    return entries.map(function (e) {
        var session = new Olm.InboundGroupSession();
        session.unpickle(e2ePickleKey, e.value.pickle);
        return {key: e.key, exportedSessionKey: session.export_session(session.first_known_index())};
    });
}

// Ngược lại e2eExportGroupInboundEntries -- nhập từng exportedSessionKey (không lệ thuộc pickle key
// nơi xuất) rồi pickle LẠI bằng e2ePickleKey CỦA THIẾT BỊ NÀY trước khi lưu, xem javadoc trên.
function e2eImportGroupInboundEntries(entries) {
    if (!entries || !entries.length) return Promise.resolve();
    var putEntries = entries.map(function (e) {
        var session = new Olm.InboundGroupSession();
        session.import_session(e.exportedSessionKey);
        return {key: e.key, value: {pickle: session.pickle(e2ePickleKey)}};
    });
    return e2eDbPutAll('groupInbound', putEntries);
}

// Khoá pickle cục bộ (mã hoá Account/Session lúc lưu xuống IndexedDB) -- sinh 1 LẦN/trình duyệt,
// KHÔNG BAO GIỜ gửi lên server (khác identity key/prekey, vốn CHỦ Ý là public). Lưu localStorage
// (cùng chỗ với authToken) thay vì IndexedDB cho đơn giản -- chỉ 1 chuỗi ngắn, không cần transaction.
// Khoá localStorage GẮN THEO myUserId -- IndexedDB/localStorage chia theo ORIGIN, không theo "đang
// đăng nhập ai", nên 1 trình duyệt từng dùng để đăng nhập NHIỀU tài khoản (test nhiều user, máy dùng
// chung...) mà không namespace theo user sẽ đọc NHẦM session/khoá của tài khoản KHÁC đã đăng nhập
// trước đó trên CÙNG trình duyệt (bug thật đã gặp lúc test nhiều tài khoản: 2 người khác nhau bị
// dùng chung 1 outbound Megolm session).
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

// id CỦA THIẾT BỊ NÀY (không phải của user) -- sinh 1 LẦN/trình duyệt bằng crypto.randomUUID(),
// KHÔNG BAO GIỜ đổi (khác pickle key, cái này CÓ gửi lên server -- public, chỉ để server biết "gửi
// cho ai" khi fan-out per-device, xem HallApiHandlers's PUT /e2e/keys). Namespace theo myUserId cùng
// lý do e2eGetOrCreatePickleKey.
function e2eGetOrCreateDeviceId() {
    var storageKey = E2E_DEVICE_ID_STORAGE + ':' + myUserId;
    var existing = localStorage.getItem(storageKey);
    if (existing) return existing;
    var id = newId();
    localStorage.setItem(storageKey, id);
    return id;
}

// Tên GỢI Ý cho thiết bị này (vd "Chrome trên macOS") -- CHỈ để người dùng tự nhận diện trong danh
// sách "Thiết bị của tôi" (xem e2eListDevices), KHÔNG có ý nghĩa kỹ thuật/bảo mật gì (khác identity
// key). Đoán thô từ navigator.userAgent -- sai/thiếu 1 vài trình duyệt lạ không sao, chỉ là gợi ý.
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

// Sinh thêm 1 lô one-time prekey MỚI + upload lên server -- gọi lúc chưa từng bật E2E, và định kỳ
// khi GET /e2e/prekey-count báo còn ít (xem e2eMaybeTopUpPrekeys). keyId tự sinh bằng newId() (đã
// có sẵn toàn cục, xem messaging-core.js) -- không cần trùng khớp gì với server, chỉ cần DUY NHẤT
// trong phạm vi CHÍNH MÌNH.
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

// Kiểm tra còn bao nhiêu prekey trên server, top-up nếu dưới ngưỡng -- gọi mỗi lần enterApp() (xem
// e2eInit), KHÔNG cần định kỳ liên tục vì prekey chỉ bị tiêu lúc có người MỚI thiết lập session với
// mình, tần suất thấp.
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

// Gọi 1 lần lúc enterApp() (xem sidebar-conversations.js) -- nạp/tạo Account CỦA THIẾT BỊ NÀY, đảm
// bảo server có sẵn identity key + prekey để người khác thiết lập session với ĐÚNG thiết bị này bất
// cứ lúc nào (kể cả khi mình đang offline lúc họ bắt đầu chat mã hoá lần đầu). Đăng ký theo
// {@code deviceId} riêng (xem HallApiHandlers#uploadE2eKeys) -- KHÔNG còn khái niệm "xung đột identity"
// của bản trước (1 identity dùng chung cả tài khoản): mỗi thiết bị/trình duyệt có 1 deviceId + 1
// identity key RIÊNG, mở bao nhiêu nơi cũng không ai ghi đè ai, luôn thành công.
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
// Liệt kê/gỡ các thiết bị mã hoá của CHÍNH MÌNH -- xem HallApiHandlers's GET/DELETE /e2e/devices.
// Gỡ 1 thiết bị KHÔNG thu hồi được các Olm session người khác đã lỡ thiết lập với nó (đã gửi rồi thì
// thôi, xem javadoc E2eKeyRegistry#deleteDevice) -- chỉ ngăn không ai THIẾT LẬP MỚI với thiết bị đó
// nữa (server không còn key bundle nào cho nó). Không cho gỡ ĐÚNG thiết bị đang dùng (xem UI phía
// sidebar-conversations.js) -- muốn "đăng xuất" thiết bị hiện tại thì dùng nút Đăng xuất thường.
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

var e2eInitPromise = null; // theo dõi lệnh gọi ĐANG CHẠY DỞ -- Olm.init() không idempotent, gọi
// chồng 1 lần thứ 2 lúc lần đầu CHƯA XONG (race thật đã gặp: enterApp() có thể bị kích 2 lần gần
// nhau) sẽ TREO VĨNH VIỄN (không resolve/reject), không chỉ đơn thuần lỗi -- e2eReady vẫn false lúc
// đó nên guard "if (e2eReady) return" KHÔNG đủ, phải nhớ đúng cái Promise đang chạy mà trả lại.
var e2eInitializedForUserId = null; // userId mà e2eReady/e2eAccount/... ĐANG đại diện -- xem reset bên dưới.
function e2eInit() {
    if (e2eInitializedForUserId !== myUserId) {
        // Đăng xuất rồi đăng nhập TÀI KHOẢN KHÁC trong CÙNG 1 lần tải trang (không F5) -- mọi state
        // module-level ở đây (e2eReady, Account/identityKeys đang mở, promise init đang chạy dở...)
        // vẫn còn đại diện tài khoản CŨ, không reset thì tài khoản MỚI sẽ ÂM THẦM DÙNG NHẦM Account
        // của người trước (bug thật đã gặp: đăng nhập lần lượt 3 tài khoản test để thử group-E2E,
        // "e2eReady" từ tài khoản đầu tiên khiến 2 tài khoản sau bỏ qua hẳn bước init, không bao giờ
        // tự tạo Account/đăng identity key riêng của họ).
        e2eReady = false;
        e2eInitPromise = null;
        e2eAccount = null;
        e2eIdentityKeys = null;
        e2ePickleKey = null;
        e2eDeviceId = null;
    }
    if (e2eInitPromise) return e2eInitPromise;
    if (e2eReady) return Promise.resolve();
    if (typeof Olm === 'undefined') return Promise.resolve(); // vendor/olm.js chưa load được (vd offline lần đầu) -- E2E tự tắt, phần còn lại của app vẫn chạy bình thường
    e2eInitializedForUserId = myUserId;
    e2ePickleKey = e2eGetOrCreatePickleKey();
    e2eDeviceId = e2eGetOrCreateDeviceId();
    e2eInitPromise = Olm.init({locateFile: function () { return 'vendor/olm.wasm'; }})
        .then(function () { return e2eDbGet('account', myUserId); })
        .then(function (stored) {
            e2eAccount = new Olm.Account();
            if (stored && stored.pickle) {
                e2eAccount.unpickle(e2ePickleKey, stored.pickle);
                e2eIdentityKeys = JSON.parse(e2eAccount.identity_keys());
            } else {
                e2eAccount.create();
                e2eIdentityKeys = JSON.parse(e2eAccount.identity_keys());
            }
            // LUÔN re-upload identity key (idempotent phía server, đăng ký theo deviceId RIÊNG -- xem
            // javadoc e2eUploadIdentityKey) dù Account cũ hay mới -- account có thể đã pickle sẵn cục
            // bộ nhưng lần trước upload lên server LỠ fail (mất mạng, deploy lại giữa chừng...) mà code
            // cũ chỉ upload ở nhánh "tạo mới", nên 1 lần fail là kẹt vĩnh viễn không bao giờ thử lại
            // -- bug thật đã gặp lúc test.
            return e2eSaveAccount()
                .then(e2eUploadIdentityKey)
                .then(function () {
                    e2eReady = true;
                    return e2eMaybeTopUpPrekeys();
                });
        })
        .catch(function (err) {
            console.warn('e2eInit lỗi -- mã hoá đầu cuối sẽ không dùng được phiên này', err);
            e2eReady = false;
        });
    return e2eInitPromise;
}

// Bật mã hoá đầu cuối cho 1 conversation -- DM (2 người) trong Phase 1 này; group (Megolm) xem TODO.
function e2eEnableConversation(conversationId) {
    return fetch(HISTORY_API_BASE + '/conversations/e2e?conversationId=' + encodeURIComponent(conversationId), {
        method: 'PUT',
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) return res.json().catch(function () { return {}; }).then(function (data) { throw new Error(data.error || ('HTTP ' + res.status)); });
        refreshConversationList();
    });
}

// Key GẮN THEO myUserId (xem javadoc e2eGetOrCreatePickleKey) + THEO ĐÚNG 1 THIẾT BỊ ĐÍCH cụ thể
// (ownerUserId/deviceId của đầu bên kia -- ownerUserId có thể LÀ CHÍNH myUserId khi đây là 1 trong
// các thiết bị KHÁC của MÌNH, xem javadoc e2eEncryptOutgoing) -- session của "mình <-> 1 thiết bị cụ
// thể", KHÔNG còn gộp chung mọi thiết bị của 1 người như bản 1-identity/user cũ (Sesame thật: mỗi
// cặp (mình, họ) x (thiết bị của họ) là 1 Double Ratchet ĐỘC LẬP).
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

// Lấy danh sách THIẾT BỊ (không phải 1 identity duy nhất nữa) của {@code userId} -- mỗi phần tử đã
// tự CHIẾM (xoá) sẵn 1 one-time prekey của chính thiết bị đó (xem HallApiHandlers#getE2eKeyBundle,
// E2eKeyRegistry#claimKeyBundlesForUser). Mảng RỖNG (không phải lỗi/404) nếu userId chưa từng bật
// E2E ở bất kỳ thiết bị nào.
function e2eFetchDeviceBundles(userId) {
    return fetch(HISTORY_API_BASE + '/e2e/keys/bundle?userId=' + encodeURIComponent(userId), {
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }).then(function (data) { return data.devices || []; });
}

// Giống e2eFetchDeviceBundles nhưng KHÔNG claim/xoá prekey nào (gọi {@code GET /e2e/keys/devices}
// thay vì {@code .../bundle}) -- dùng để KIỂM TRA rẻ "người này có thiết bị nào chưa từng nhận 1
// khoá phiên Megolm cụ thể hay không" (xem e2eGetOrCreateOutboundGroupSession) mà không phải trả giá
// tiêu tốn 1 one-time prekey mỗi lần chỉ để kiểm tra.
function e2eListDevicesForUser(userId) {
    return fetch(HISTORY_API_BASE + '/e2e/keys/devices?userId=' + encodeURIComponent(userId), {
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }).then(function (data) { return data.devices || []; });
}

// Lấy session đã có với ĐÚNG 1 thiết bị (ownerUserId, device.deviceId), hoặc tạo outbound session
// MỚI bằng identity+one-time key của thiết bị đó nếu chưa từng chat -- {@code device.oneTimePrekey}
// có thể null (thiết bị đó hết prekey, Olm vẫn tạo outbound được, chỉ kém 1 lớp forward-secrecy ở
// tin đầu, xem javadoc E2eKeyRegistry#claimKeyBundlesForUser).
function e2eGetOrCreateDmSessionForDevice(ownerUserId, device) {
    return e2eLoadDmSession(ownerUserId, device.deviceId).then(function (session) {
        if (session) return session;
        var newSession = new Olm.Session();
        newSession.create_outbound(e2eAccount, device.identityKey, device.oneTimePrekey ? device.oneTimePrekey.publicKey : device.identityKey);
        return e2eSaveDmSession(ownerUserId, device.deviceId, newSession).then(function () { return newSession; });
    });
}

// Mã hoá 1 tin gửi cho peerUserId trong DM đã bật E2E (CŨNG dùng lại để mã hoá payload to-device như
// megolm_session -- xem e2eDistributeGroupSessionKey) -- trả về ENVELOPE {@code {perDevice: {deviceId:
// {olmType, ciphertext}, ...}, senderDeviceId}} để gửi đi thay cho body gốc. Mã hoá RIÊNG cho TỪNG
// thiết bị của peerUserId (fan-out per-device, đúng chuẩn Signal/Sesame -- xem javadoc đầu file) VÀ
// cho các thiết bị KHÁC của CHÍNH MÌNH (để tự đọc lại được tin mình gửi trên thiết bị khác, không chỉ
// nhờ cache cục bộ như bản 1-identity cũ). 404/rỗng nếu peerUserId CHƯA TỪNG bật E2E ở bất kỳ thiết bị
// nào -- ném lỗi rõ ràng cho người gọi (vd báo "người này chưa bật mã hoá đầu cuối"), khác với việc 1
// vài thiết bị lẻ tẻ của họ lỗi/hết prekey (bỏ qua từng cái, không chặn cả gửi).
//
// replyTo.fromUserId + mentionedUserIds được LẶP LẠI dạng CLEARTEXT ở ngoài (đã thoả thuận với người
// dùng: nội dung tin kín hoàn toàn, nhưng "ai được reply/mention" vẫn lộ để server bắn đúng thông báo
// riêng như cũ, xem ChatSessionManager#extractSpecialNotifyUserIds bên colony).
function e2eEncryptOutgoing(peerUserId, plainBody) {
    return Promise.all([e2eFetchDeviceBundles(peerUserId), e2eFetchDeviceBundles(myUserId)]).then(function (results) {
        var peerDevices = results[0];
        if (!peerDevices.length) throw new Error('người này chưa bật mã hoá đầu cuối');
        var myOtherDevices = results[1].filter(function (d) { return d.deviceId !== e2eDeviceId; });
        var targets = peerDevices.map(function (d) { return {ownerUserId: peerUserId, device: d}; })
            .concat(myOtherDevices.map(function (d) { return {ownerUserId: myUserId, device: d}; }));
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

// Giải mã 1 tin nhận được từ fromUserId trong DM đã bật E2E -- trả plain body gốc (đúng shape
// {message, files, replyTo, ...} như tin không mã hoá) để phần render còn lại KHÔNG cần biết gì về
// E2E. {@code fromUserId} có thể LÀ CHÍNH myUserId (đọc lại tin MÌNH đã gửi từ 1 thiết bị KHÁC, xem
// javadoc e2eEncryptOutgoing) -- vẫn tra đúng session theo (fromUserId, envelope.senderDeviceId).
// Không giải mã được (không có phần dành cho THIẾT BỊ NÀY trong perDevice, hoặc lỗi ratchet) thì trả
// placeholder rõ ràng thay vì throw làm vỡ cả pipeline render.
function e2eDecryptIncoming(fromUserId, envelope) {
    var mine = envelope && envelope.perDevice ? envelope.perDevice[e2eDeviceId] : null;
    if (!mine) {
        return Promise.resolve({message: '🔒 Tin nhắn đã mã hoá (không gửi cho thiết bị này -- có thể thiết bị vừa liên kết sau lúc tin được gửi)', e2eFailed: true});
    }
    var senderDeviceId = envelope.senderDeviceId;
    return e2eLoadDmSession(fromUserId, senderDeviceId)
        .then(function (session) {
            if (session && mine.olmType === 1) {
                // Tin RATCHET thường (không phải tin đầu) -- PHẢI có session cũ mới đúng, không tự tạo mới.
                return {session: session, plaintext: session.decrypt(mine.olmType, mine.ciphertext)};
            }
            if (session && mine.olmType === 0 && session.matches_inbound(mine.ciphertext)) {
                // PREKEY message nhưng KHỚP đúng session đã có (vd 2 bên cùng lúc tự tạo outbound,
                // xem olm.js README matches_inbound) -- dùng lại session cũ, không tạo trùng.
                return {session: session, plaintext: session.decrypt(mine.olmType, mine.ciphertext)};
            }
            if (mine.olmType === 0) {
                // Tin ĐẦU TIÊN từ thiết bị này (hoặc session cũ không khớp) -- tạo inbound session mới.
                var newSession = new Olm.Session();
                newSession.create_inbound(e2eAccount, mine.ciphertext);
                var plaintext = newSession.decrypt(mine.olmType, mine.ciphertext);
                e2eAccount.remove_one_time_keys(newSession);
                return e2eSaveAccount().then(function () { return {session: newSession, plaintext: plaintext}; });
            }
            throw new Error('không có session để giải mã tin này (olmType=1 nhưng chưa từng thiết lập session)');
        })
        .then(function (result) {
            return e2eSaveDmSession(fromUserId, senderDeviceId, result.session).then(function () { return JSON.parse(result.plaintext); });
        })
        .catch(function (err) {
            console.warn('e2e decrypt lỗi', err);
            return {message: '🔒 Không giải mã được tin nhắn này (đổi thiết bị/trình duyệt khác?)', e2eFailed: true};
        });
}

// Cache plaintext cục bộ theo messageId (IndexedDB, cùng trình duyệt) -- vẫn cần dù giờ đã fan-out
// per-device (đọc lại tin mình gửi trên thiết bị KHÁC không còn PHỤ THUỘC HOÀN TOÀN vào cache nữa,
// xem e2eResolveIncomingBody), vì Megolm/Olm vẫn là RATCHET DÙNG 1 LẦN cho mỗi vị trí tin -- decrypt()
// xong 1 tin thì trạng thái đã tiến lên, KHÔNG decrypt lại được CHÍNH tin đó lần 2 (khác đọc file
// tĩnh, đây là stream). Load lại lịch sử (F5, mở lại conversation) mà không có cache sẽ cố decrypt
// lại ciphertext ĐÃ decrypt trước đó -> lỗi ratchet, mất luôn nội dung (bug thật đã gặp lúc test).
// Nên: decrypt-1-LẦN, cache plaintext lại, mọi lần sau đọc CACHE, không bao giờ decrypt lại ciphertext
// đã xử lý rồi -- kể cả tin của CHÍNH MÌNH đọc qua fan-out từ 1 thiết bị khác.
function e2eCachePlaintext(messageId, plainBody) {
    return e2eDbPut('msgPlaintext', myUserId + '|' + messageId, {body: plainBody});
}

function e2eGetCachedPlaintext(messageId) {
    return e2eDbGet('msgPlaintext', myUserId + '|' + messageId).then(function (stored) { return stored ? stored.body : null; });
}

// Mã hoá body TRƯỚC KHI gửi đi nếu conversation đã bật E2E -- gọi từ sendChatMessage (điểm vào DUY
// NHẤT gửi MESSAGE, xem messaging-core.js). Trả nguyên {@code plainBody} không đổi nếu conversation
// KHÔNG bật E2E (đường đi cũ, không ảnh hưởng gì). DM (đúng 1 người khác) dùng Olm 1-1; GROUP (>1
// người khác) dùng Megolm (xem e2eEncryptGroupOutgoing).
function e2eMaybeEncryptForSend(conversationId, plainBody) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv || !conv.e2eEnabled) return Promise.resolve(plainBody);
    if (!e2eReady) return Promise.reject(new Error('Mã hoá đầu cuối chưa sẵn sàng, thử lại sau'));
    var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
    if (others.length === 1) return e2eEncryptOutgoing(others[0], plainBody);
    return e2eEncryptGroupOutgoing(conversationId, conv.memberUserIds, plainBody);
}

// Giải mã body NHẬN VỀ nếu là tin e2e -- dùng cho MỌI nguồn tin (WS sống, GET /messages lịch sử,
// jump-to-message...), xem các call site trong history-ws.js/messages-render.js. LUÔN tra cache
// TRƯỚC (xem javadoc e2eCachePlaintext) -- chỉ decrypt khi cache MISS thật (lần đầu thấy đúng tin
// này), rồi lưu lại kết quả ngay để lần sau không decrypt lại ciphertext đã dùng (session không cho
// phép). {@code body.megolm} quyết định dùng Olm 1-1 hay Megolm group để giải (xem
// e2eDecryptIncoming/e2eDecryptGroupIncoming). Tin CỦA CHÍNH MÌNH giờ CŨNG thử decrypt bình thường
// (khác bản 1-identity cũ luôn coi là "không đọc lại được") -- fan-out per-device (xem
// e2eEncryptOutgoing) gửi kèm 1 bản cho các thiết bị KHÁC của mình, nên đọc lại được nếu THIẾT BỊ
// NÀY có mặt trong {@code perDevice} lúc gửi; không có (vd thiết bị vừa liên kết SAU lúc tin đó được
// gửi) thì decrypt tự trả placeholder rõ ràng (xem e2eDecryptIncoming), không throw.
function e2eResolveIncomingBody(fromUserId, body, messageId, conversationId) {
    if (!body || !body.e2e) return Promise.resolve(body);
    return e2eGetCachedPlaintext(messageId).then(function (cached) {
        if (cached) return cached;
        if (!e2eReady) return {message: '🔒 Tin nhắn đã mã hoá (thiết bị này chưa sẵn sàng đọc)', e2eFailed: true};
        var decryptPromise = body.megolm ? e2eDecryptGroupIncoming(conversationId, fromUserId, body) : e2eDecryptIncoming(fromUserId, body);
        return decryptPromise.then(function (plain) {
            // KHÔNG cache khi decrypt thất bại (e2eFailed) -- để lần sau còn thử lại (vd session key
            // vừa nhận được qua to-device), cache thành công thật thì mới chốt luôn không decrypt lại nữa.
            if (plain && plain.e2eFailed) return plain;
            return e2eCachePlaintext(messageId, plain).then(function () { return plain; });
        });
    });
}

// Giống e2eResolveIncomingBody nhưng dùng RIÊNG cho tin VỪA ĐƯỢC SỬA (EDIT) của NGƯỜI KHÁC -- BỎ
// QUA cache đọc (nội dung cache đang giữ là bản CŨ trước khi sửa), luôn decrypt ciphertext MỚI rồi
// GHI ĐÈ cache bằng kết quả mới -- xem history-ws.js case "EDIT".
function e2eResolveEditedBody(fromUserId, body, messageId, conversationId) {
    if (!body || !body.e2e) return Promise.resolve(body);
    if (!e2eReady) return Promise.resolve({message: '🔒 Tin nhắn đã mã hoá (thiết bị này chưa sẵn sàng đọc)', e2eFailed: true});
    var decryptPromise = body.megolm ? e2eDecryptGroupIncoming(conversationId, fromUserId, body) : e2eDecryptIncoming(fromUserId, body);
    return decryptPromise.then(function (plain) {
        if (plain && plain.e2eFailed) return plain;
        return e2eCachePlaintext(messageId, plain).then(function () { return plain; });
    });
}

// Giải mã 1 LÔ tin theo đúng THỨ TỰ ban đầu (Promise.all giữ index, không phụ thuộc tin nào xong
// trước) -- dùng cho mọi chỗ nạp lịch sử hàng loạt (loadLatestPage, loadAroundReadCursor,
// maybeLoadOlder, jump-to-message). {@code conversationId} truyền riêng (không đọc từ {@code m}) vì
// GET /messages không trả lại field đó trên từng tin -- nơi gọi đã biết sẵn theo query.
function e2eResolveIncomingBatch(conversationId, messages) {
    return Promise.all(messages.map(function (m) {
        return e2eResolveIncomingBody(m.fromUserId, m.body, m.id, conversationId).then(function (resolvedBody) {
            return Object.assign({}, m, {body: resolvedBody});
        });
    }));
}

// ================= Group (Megolm) =================
// Khác Olm 1-1 (1 session DÙNG CHUNG cho cả 2 chiều gửi/nhận giữa đúng 2 THIẾT BỊ): Megolm là ratchet
// 1 CHIỀU, mỗi THIẾT BỊ tự giữ ĐÚNG 1 "outbound session" cho tin MÌNH gửi (không share giữa các thiết
// bị của CHÍNH MÌNH -- cùng lý do không share Olm Session DM, xem javadoc mục "Liên kết thiết bị" cuối
// file: 2 thiết bị cùng ratchet 1 outbound session độc lập sẽ vô tình dùng chung message key), và giữ
// N inbound session (1 cho MỖI (người gửi, thiết bị của họ)) để đọc tin của họ -- xem create()/
// session_key() (outbound) vs create(session_key) (inbound) trong olm.js README mục "Group chat".
// session_key() là bí mật DÙNG CHUNG cho toàn bộ history của outbound session đó -- KHÔNG gửi rõ, luôn
// mã hoá riêng cho từng THIẾT BỊ nhận qua đúng Olm 1-1 session đã có ở trên (tái dùng e2eEncryptOutgoing,
// đã tự fan-out per-device), gửi qua {@code POST /e2e/to-device} (type {@code "megolm_session"}) --
// KHÔNG qua message thường vì đây là key material, không phải nội dung chat. Nội dung tin nhóm THẬT
// SỰ (ciphertext Megolm) thì KHÔNG cần fan-out per-device -- 1 bản duy nhất, ai có đúng sessionId
// tương ứng đều giải được, xem e2eEncryptGroupOutgoing/e2eDecryptGroupIncoming.

// Key GẮN THEO (myUserId, e2eDeviceId) -- outbound session là CỦA RIÊNG "thiết bị này" cho
// conversationId đó, KHÔNG share với các thiết bị KHÁC của CHÍNH MÌNH (xem javadoc phía trên) lẫn
// không lẫn với tài khoản KHÁC lỡ đăng nhập trước đó trên CÙNG trình duyệt.
//
// {@code initialSessionKey} = {@code session.session_key()} gọi ĐÚNG 1 LẦN lúc {@code session.create()},
// lưu lại RIÊNG (không gọi lại {@code session.session_key()} lúc phân phối) -- bug thật đã gặp lúc
// test: {@code session_key()} phản ánh vị trí ratchet HIỆN TẠI (đã encrypt bao nhiêu tin), không phải
// vị trí BAN ĐẦU -- gọi lại nó SAU KHI đã gửi vài tin rồi phân phối cho 1 thiết bị "bắt kịp" (đổi máy/
// khôi phục backup) sẽ cho họ khoá CHỈ đọc được tin TỪ THỜI ĐIỂM ĐÓ trở đi, tạo "khoảng trống" không
// đọc được các tin gửi TRƯỚC lúc phân phối lại nhưng SAU lúc backup cũ của họ (xác nhận bằng
// OLM.UNKNOWN_MESSAGE_INDEX khi test trực tiếp). Lưu {@code initialSessionKey} lúc tạo, dùng lại
// ĐÚNG giá trị đó cho MỌI lần phân phối tới THÀNH VIÊN CŨ (xem e2eGetOrCreateOutboundGroupSession) là
// cách duy nhất đảm bảo họ luôn đọc lại được TOÀN BỘ lịch sử của session, không có khoảng trống.
function e2eLoadOutboundGroupSession(conversationId) {
    return e2eDbGet('groupOutbound', myUserId + '|' + e2eDeviceId + '|' + conversationId).then(function (stored) {
        if (!stored || !stored.pickle) return null;
        var session = new Olm.OutboundGroupSession();
        session.unpickle(e2ePickleKey, stored.pickle);
        return {session: session, distributedTo: stored.distributedTo || [], initialSessionKey: stored.initialSessionKey};
    });
}

function e2eSaveOutboundGroupSession(conversationId, session, distributedTo, initialSessionKey) {
    return e2eDbPut('groupOutbound', myUserId + '|' + e2eDeviceId + '|' + conversationId,
        {pickle: session.pickle(e2ePickleKey), distributedTo: distributedTo, initialSessionKey: initialSessionKey});
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

// Gửi {@code sessionKey} (do NGƯỜI GỌI cung cấp SẴN, xem javadoc e2eLoadOutboundGroupSession vì sao
// KHÔNG tự gọi {@code session.session_key()} ở đây) cho 1 loạt NGƯỜI qua to-device -- mã hoá RIÊNG
// từng người bằng đúng Olm DM session 1-1 (tái dùng e2eEncryptOutgoing, đã tự fan-out cho MỌI thiết
// bị HIỆN TẠI của người đó) rồi POST lên hàng đợi to-device, KHÔNG gửi rõ session_key vì ai có nó đọc
// được MỌI tin mã hoá bằng chain key TỪ VỊ TRÍ ĐÓ trở đi. LƯU Ý: chỉ fan-out tới thiết bị của NGƯỜI
// NHẬN, chưa tự động đồng bộ session_key này sang các thiết bị KHÁC của CHÍNH MÌNH (to-device địa chỉ
// theo recipientUserId ở tầng server, phần "gửi kèm cho chính mình" bên trong envelope của
// e2eEncryptOutgoing không tới đâu vì gói được queue dưới recipientUserId của NGƯỜI NHẬN, không phải
// mình) -- thiết bị khác của mình sẽ đọc được các tin nhóm CŨ này qua tính năng "liên kết thiết bị"
// (chuyển hẳn groupInbound, xem mục cuối file) thay vì tự động ngay lúc gửi, giới hạn CHẤP NHẬN ĐƯỢC.
// Trả về đúng danh sách recipientUserId GỬI THÀNH CÔNG (không phải tất cả) -- người gọi chỉ được đánh
// dấu "đã phân phối" cho những ai THẬT SỰ nhận được, không phải cả danh sách đã CỐ gửi. Lỗi 1 người
// (vd họ chưa từng bật E2E) không được chặn/làm rớt kết quả của những người còn lại -- mỗi delivery
// độc lập, catch riêng từng cái.
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

// Lấy danh sách "userId|deviceId" CHO MỌI THIẾT BỊ HIỆN TẠI của từng userId trong {@code userIds} --
// dùng SAU KHI đã gửi thành công, để ghi lại đúng CÁC THIẾT BỊ nào vừa thật sự nhận được (xem
// e2eDistributeGroupSessionKey's javadoc: e2eEncryptOutgoing bên trong đã tự fan-out cho MỌI thiết
// bị hiện tại của người đó, nên coi như TẤT CẢ thiết bị hiện tại của 1 người "succeeded" đều đã nhận).
// Dùng {@link e2eListDevicesForUser} (KHÔNG claim prekey) vì chỉ cần ĐỌC danh sách để ghi sổ, không
// cần thiết lập session gì thêm ở bước này.
function e2eCurrentDeviceKeysFor(userIds) {
    return Promise.all(userIds.map(function (userId) {
        return e2eListDevicesForUser(userId).then(function (devices) {
            return devices.map(function (d) { return userId + '|' + d.deviceId; });
        });
    })).then(function (lists) { return lists.reduce(function (acc, l) { return acc.concat(l); }, []); });
}

// Lấy (hoặc tạo mới) outbound Megolm session CỦA CHÍNH THIẾT BỊ NÀY cho conversationId -- đảm bảo MỌI
// THIẾT BỊ HIỆN TẠI của mọi thành viên khác đã nhận được session_key, phân phối bù cho ai/thiết bị nào
// chưa có. 2 trường hợp "chưa có" khác hẳn nhau, PHẢI phân biệt (xem {@code hasAnyDeviceOfUser} bên
// dưới):
//  - THÀNH VIÊN CŨ vừa đổi sang thiết bị MỚI (mất hết local storage rồi đăng nhập lại/khôi phục
//    backup) -- họ ĐÃ có ít nhất 1 thiết bị khác từng nhận session này rồi, chỉ thiết bị MỚI là chưa
//    -- PHẢI gửi {@code initialSessionKey} (chốt lúc {@code session.create()}, xem javadoc
//    e2eLoadOutboundGroupSession) để họ đọc lại được TOÀN BỘ lịch sử session, không có "khoảng trống"
//    (bug thật đã gặp: gọi lại {@code session.session_key()} lúc phân phối muộn chỉ cho đọc được tin
//    TỪ LÚC ĐÓ trở đi, mất hẳn các tin gửi giữa lúc backup cũ và lúc phân phối lại).
//  - THÀNH VIÊN MỚI thật sự (chưa từng có bất kỳ thiết bị nào nhận session này) -- PHẢI gửi khoá
//    HIỆN TẠI ({@code session.session_key()} gọi tươi lúc này), KHÔNG phải initialSessionKey -- nếu
//    không sẽ phá vỡ backward secrecy đã thống nhất trước đó (thành viên mới không được đọc lịch sử
//    trước lúc họ vào nhóm).
function e2eGetOrCreateOutboundGroupSession(conversationId, memberUserIds) {
    var others = memberUserIds.filter(function (id) { return id !== myUserId; });
    return e2eLoadOutboundGroupSession(conversationId).then(function (existing) {
        var session = existing ? existing.session : new Olm.OutboundGroupSession();
        var initialSessionKey = existing ? existing.initialSessionKey : null;
        if (!existing) {
            session.create();
            initialSessionKey = session.session_key(); // CHỈ gọi 1 LẦN DUY NHẤT ở đây, xem javadoc phía trên.
        } else if (!initialSessionKey) {
            // Session được TẠO TỪ TRƯỚC bản fix này (chưa từng lưu initialSessionKey) -- đành lấy tạm
            // khoá HIỆN TẠI làm phương án dự phòng (giống hệt hành vi CŨ trước fix, không phải lỗi mới
            // phát sinh) -- session này sẽ tự "chữa lành" (có initialSessionKey đúng nghĩa) ngay khi
            // rotate lần kế tiếp (kick/rời, xem e2eRotateOutboundGroupSession) hoặc lần tới đây bị bỏ
            // rồi tạo lại (vd đổi thiết bị của CHÍNH MÌNH).
            initialSessionKey = session.session_key();
        }
        var distributedTo = existing ? existing.distributedTo : [];
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
            if (!pending.length) return {session: session, distributedTo: distributedTo, initialSessionKey: initialSessionKey};
            var catchUpIds = pending.filter(function (r) { return r.catchingUp; }).map(function (r) { return r.userId; });
            var newMemberIds = pending.filter(function (r) { return !r.catchingUp; }).map(function (r) { return r.userId; });
            return Promise.all([
                e2eDistributeGroupSessionKey(conversationId, session, catchUpIds, initialSessionKey),
                e2eDistributeGroupSessionKey(conversationId, session, newMemberIds, session.session_key())
            ]).then(function (successLists) {
                // CHỈ thêm người THẬT SỰ nhận được (xem javadoc e2eDistributeGroupSessionKey) -- ai gửi
                // lỗi (vd họ chưa bật E2E) vẫn ở lại "pending", lần gửi tin KẾ TIẾP sẽ tự thử lại, không
                // bị đánh dấu nhầm "đã phân phối" rồi không bao giờ thử lại nữa (bug thật đã gặp lúc test).
                var succeeded = successLists[0].concat(successLists[1]);
                return e2eCurrentDeviceKeysFor(succeeded).then(function (newKeys) {
                    var newDistributedTo = distributedTo.slice();
                    newKeys.forEach(function (k) { if (newDistributedTo.indexOf(k) === -1) newDistributedTo.push(k); });
                    return e2eSaveOutboundGroupSession(conversationId, session, newDistributedTo, initialSessionKey).then(function () {
                        return {session: session, distributedTo: newDistributedTo, initialSessionKey: initialSessionKey};
                    });
                });
            });
        });
    });
}

// Bắt buộc tạo session MỚI (bỏ hẳn session cũ, không tái dùng) -- gọi khi phát hiện có người vừa bị
// kick/rời khỏi 1 group đã mã hoá (xem e2eCheckGroupRotations), để họ KHÔNG đọc được tin MÌNH gửi
// SAU thời điểm đó (vẫn đọc được tin CŨ trước lúc rời, vì họ đã giữ sẵn session_key cũ -- đúng kỳ
// vọng đã thống nhất "chỉ rotate khi rời/bị kick", group Megolm vốn không có forward-secrecy
// per-message như DM, xem javadoc đầu file/thảo luận trước đó). Mọi thành viên CÒN LẠI đều là thành
// viên "gốc" của session MỚI này (không ai "bắt kịp" cả, session vừa tạo) -- gửi thẳng
// {@code initialSessionKey} luôn (giống nhau ở đây vì session hoàn toàn mới, ai cũng nhận NGAY lúc
// vị trí ratchet = 0).
function e2eRotateOutboundGroupSession(conversationId, memberUserIds) {
    var session = new Olm.OutboundGroupSession();
    session.create();
    var initialSessionKey = session.session_key();
    var others = memberUserIds.filter(function (id) { return id !== myUserId; });
    return e2eDistributeGroupSessionKey(conversationId, session, others, initialSessionKey)
        .then(function (succeeded) { return e2eCurrentDeviceKeysFor(succeeded); })
        .then(function (distributedTo) { return e2eSaveOutboundGroupSession(conversationId, session, distributedTo, initialSessionKey); });
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

// Giải 1 tin group -- tra ĐÚNG inbound session theo (conversationId, fromUserId, sessionId trong
// envelope, xem e2eSaveInboundGroupSession) vì mỗi người gửi có session RIÊNG (sessionId đã tự phân
// biệt theo từng THIẾT BỊ gửi, không cần thêm senderDeviceId vào key). Chưa có session (vd to-device
// phân phối khoá chưa kịp tới, hoặc mình vào nhóm SAU lúc session đó được tạo, hoặc là thiết bị KHÁC
// của người gửi mà session_key đó chưa từng được đồng bộ tới) thì báo lỗi rõ ràng thay vì throw làm
// vỡ pipeline render -- KHÔNG cache (xem e2eResolveIncomingBody), để lần GET /messages/mở lại sau còn
// tự thử lại nếu lúc đó khoá đã tới.
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

// Xử lý 1 tin to-device (dùng chung cho relay sống qua WS -- xem history-ws.js case "E2E_TO_DEVICE"
// -- và drain lúc connect lại -- xem e2eDrainToDevice). An toàn khi CÙNG LÚC nhiều thiết bị của cùng
// 1 user đều nhận được relay/drain của 1 item -- e2eDecryptIncoming tự bỏ qua (trả placeholder) nếu
// {@code perDevice} không có phần dành cho THIẾT BỊ NÀY, không cần lọc theo deviceId ở tầng này. 3
// loại: phân phối Megolm session key (xem e2eDistributeGroupSessionKey); YÊU CẦU gửi lại khoá (xem
// e2eRequestMissingGroupKeys/e2eHandleGroupKeyRequest ngay dưới); hoặc gói chuyển giao LỊCH SỬ lúc
// liên kết thiết bị (xem e2eApproveDeviceLink/e2eHandleDeviceLinkPayload bên dưới).
function e2eHandleToDeviceItem(type, senderUserId, conversationId, olmEnvelope) {
    if (type === 'device_link_payload') return e2eHandleDeviceLinkPayload(olmEnvelope);
    if (type === 'megolm_key_request') return e2eHandleGroupKeyRequest(conversationId);
    if (type !== 'megolm_session' || !olmEnvelope) return Promise.resolve();
    return e2eDecryptIncoming(senderUserId, olmEnvelope).then(function (payload) {
        if (!payload || payload.e2eFailed || !payload.sessionKey || !payload.sessionId) return;
        var session = new Olm.InboundGroupSession();
        session.create(payload.sessionKey);
        return e2eSaveInboundGroupSession(payload.conversationId || conversationId, senderUserId, payload.sessionId, session);
    });
}

// === Chủ động xin lại khoá Megolm còn thiếu (giống m.room_key_request của Matrix) ===
// Vá lỗ hổng CÒN LẠI sau fix initialSessionKey: dù khoá đã đúng (đọc lại được CẢ lịch sử), nó vẫn
// CHỈ được gửi lại lúc có người GỬI 1 tin mới (xem javadoc e2eGetOrCreateOutboundGroupSession -- chỉ
// người GIỮ outbound session mới tự kiểm tra "ai đang thiếu" MỖI LẦN HỌ GỬI) -- thiết bị vừa khôi
// phục KHÔNG có cách nào tự "xin lại" nếu chẳng may không ai gửi gì mới. Nên: thiết bị vừa online chủ
// động BÁO cho mọi thành viên khác trong các nhóm đã mã hoá "kiểm tra giúp xem tôi có đang thiếu khoá
// phiên nào không" -- không cần biết CHÍNH XÁC thiếu gì (tốn công quét lại toàn bộ lịch sử tin nhắn),
// cứ hỏi hết, ai đang giữ outbound session cho conversation đó tự chạy lại ĐÚNG logic kiểm tra+phân
// phối bù đã có (e2eGetOrCreateOutboundGroupSession), tự nhiên chỉ gửi cho ai THẬT SỰ đang thiếu.

// Nhận yêu cầu "xin lại khoá" -- nếu MÌNH có giữ outbound session cho conversationId đó thì chạy lại
// nguyên logic phân phối bù sẵn có, không thêm logic mới nào (đã tự chỉ gửi cho ai thiếu thật).
function e2eHandleGroupKeyRequest(conversationId) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv || !conv.e2eEnabled) return Promise.resolve();
    return e2eGetOrCreateOutboundGroupSession(conversationId, conv.memberUserIds)
        .catch(function (err) { console.warn('xử lý yêu cầu xin khoá lỗi', err); });
}

// Gọi 1 lần lúc enterApp() (SAU e2eDrainToDevice -- xem sidebar-conversations.js), broadcast yêu cầu
// tới TẤT CẢ thành viên khác của MỌI group đã mã hoá đang có trong {@code lastConvList}. Rẻ (chỉ 1
// to-device rỗng mỗi người, không tốn prekey/không cần mã hoá gì thêm -- conversationId vốn đã lộ
// cleartext ở tầng to-device y hệt megolm_session, xem javadoc E2eKeyRegistry) -- an toàn để gọi mỗi
// lần mở app dù thường sẽ không thiếu gì (người nhận tự no-op nếu không có gì để gửi thêm).
function e2eRequestMissingGroupKeys() {
    if (!e2eReady) return Promise.resolve();
    var deliveries = [];
    lastConvList.forEach(function (conv) {
        if (!conv.e2eEnabled) return;
        var others = conv.memberUserIds.filter(function (id) { return id !== myUserId; });
        if (others.length <= 1) return; // DM -- không có khái niệm session Megolm ở đây
        others.forEach(function (otherId) {
            deliveries.push({recipientUserId: otherId, type: 'megolm_key_request', conversationId: conv.conversationId, body: {}});
        });
    });
    if (!deliveries.length) return Promise.resolve();
    return fetch(HISTORY_API_BASE + '/e2e/to-device', {
        method: 'POST',
        headers: {'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json'},
        body: JSON.stringify({deliveries: deliveries})
    }).catch(function (err) { console.warn('không gửi được yêu cầu xin khoá', err); });
}

// Bù các tin to-device gửi lúc mình OFFLINE (relay sống qua WS lúc đó không tới đâu được, xem
// javadoc E2eKeyRegistry#queueToDeviceMessage) -- gọi 1 lần lúc enterApp(), xử lý TUẦN TỰ (không
// Promise.all) để giữ đúng thứ tự nếu có nhiều lần rotate liên tiếp trên cùng 1 conversation.
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

// === Liên kết thiết bị (KHÔNG quét QR -- gõ tay mã 6 ký tự) ===
// Mỗi thiết bị giờ tự có identity riêng NGAY TỪ ĐẦU (xem e2eInit) -- KHÔNG còn "xung đột identity"
// hay chờ liên kết mới dùng được E2E như bản trước. Tính năng này giờ CHỈ còn 1 việc: chuyển giao
// LỊCH SỬ ĐÃ GIẢI MÃ (msgPlaintext + Megolm inbound session) từ 1 thiết bị CŨ sang thiết bị MỚI --
// giống hệt "message history sync" của chính Signal thật khi liên kết thiết bị mới (xem
// https://signal.org/blog/a-synchronized-start-for-linked-devices/: Signal cũng KHÔNG chia sẻ session
// sống, chỉ đóng gói lịch sử đã giải mã sẵn rồi chuyển an toàn 1 lần).
//
// Cơ chế: thiết bị MỚI tự tạo 1 Olm.Account TẠM (chỉ dùng 1 lần cho việc chuyển giao này, KHÔNG phải
// Account thật của nó -- Account thật đã có sẵn từ e2eInit rồi) + xin server 1 mã 6 ký tự ngắn hạn (5
// phút). Thiết bị CŨ gõ mã đó vào, lấy bundle tạm của thiết bị mới, mã hoá gói {msgPlaintext,
// groupInbound} của MÌNH bằng 1 Olm session outbound bình thường (y hệt gửi 1 tin DM, dùng 1 lần rồi
// bỏ), gửi qua đúng {@code POST /e2e/to-device} sẵn có, địa chỉ đến CHÍNH MÌNH.
//
// GIỚI HẠN THẬT SỰ (không tránh được, lý do an toàn -- giống chính Signal): KHÔNG chuyển session DM
// 1-1 (dmSessions) hay Megolm OUTBOUND session (groupOutbound) -- đây là ratchet dùng để GỬI, nếu 2
// thiết bị cùng giữ 1 bản sao rồi CÙNG mã hoá trước khi đồng bộ lại, cả 2 sẽ vô tình dùng CHUNG 1
// message key cho 2 tin KHÁC NHAU -- lỗi mã hoá nghiêm trọng. Thiết bị mới tự thiết lập session GỬI
// riêng với từng người/nhóm khi cần (độc lập, tự fan-out per-device như mọi thiết bị khác).
var e2eLinkAccount = null; // Olm.Account TẠM, chỉ tồn tại lúc đang CHỜ được liên kết (thiết bị MỚI)
var e2eLinkCode = null; // mã đang chờ khớp -- phòng nhận nhầm payload của 1 phiên xin mã cũ/khác
var e2eLinkWaiters = [];

// Thiết bị MUỐN LẤY lịch sử từ thiết bị khác gọi hàm này để xin 1 mã liên kết -- tạo 1 Account TẠM +
// 1 one-time key DÙNG ĐÚNG 1 LẦN cho việc chuyển giao (không phải Account thật của mình, xem javadoc
// phía trên).
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

// Chờ payload liên kết tới -- KHÔNG polling, dựa hẳn vào relay sống WS (case "E2E_TO_DEVICE" trong
// history-ws.js) tự gọi e2eHandleToDeviceItem -> e2eHandleDeviceLinkPayload -> resolve các waiter ở đây.
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

// Nhận gói {msgPlaintext, groupInbound} đã giải mã, NHẬP thẳng vào storage của THIẾT BỊ NÀY -- đọc
// được HẾT lịch sử đã từng giải mã trên thiết bị cũ ngay lập tức (xem javadoc đầu mục). KHÔNG còn
// đụng gì tới identity/Account của thiết bị này (đã có sẵn từ e2eInit, không cần "nhập" nữa).
function e2eApplyLinkedHistory(transfer) {
    return e2eDbPutAll('msgPlaintext', transfer.msgPlaintext)
        .then(function () { return e2eImportGroupInboundEntries(transfer.groupInbound); })
        .then(function () {
            e2eLinkAccount = null;
            e2eLinkCode = null;
        });
}

// Gọi từ e2eHandleToDeviceItem khi tới đúng loại 'device_link_payload' -- giải mã bằng Account TẠM
// (e2eLinkAccount) y hệt e2eDecryptIncoming's nhánh PREKEY, rồi nhập lịch sử vừa nhận được. KHÔNG
// khớp mã đang chờ (payload của 1 phiên xin mã KHÁC, vd tab cũ chưa đóng) thì bỏ qua im lặng.
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

// Thiết bị CÓ SẴN lịch sử gọi sau khi user gõ đúng mã hiển thị trên thiết bị MỚI -- lấy bundle
// (identity+one-time key TẠM của thiết bị mới), gói TOÀN BỘ cache plaintext + Megolm inbound session
// của MÌNH, mã hoá NGUYÊN gói đó bằng 1 Olm session outbound bình thường (y hệt gửi 1 tin DM, KHÔNG
// lưu lại session này -- dùng 1 lần rồi bỏ), rồi gửi qua đúng POST /e2e/to-device sẵn có, địa chỉ đến
// CHÍNH MÌNH (userId khác của thiết bị mới cùng 1 tài khoản, WS relay đã hỗ trợ multi-session cho
// cùng userId, xem RoutingVersionSync#onE2eToDevice).
function e2eApproveDeviceLink(code) {
    if (!e2eReady) return Promise.reject(new Error('Thiết bị này chưa sẵn sàng, thử lại sau'));
    return fetch(HISTORY_API_BASE + '/e2e/device-link/bundle?code=' + encodeURIComponent(code), {
        headers: {'Authorization': 'Bearer ' + authToken}
    }).then(function (res) {
        if (res.status === 404) throw new Error('Mã không đúng hoặc đã hết hạn');
        if (!res.ok) throw new Error('Không lấy được thông tin thiết bị mới, thử lại sau');
        return res.json();
    }).then(function (bundle) {
        return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groupInbound')]).then(function (dumps) {
            var session = new Olm.Session();
            session.create_outbound(e2eAccount, bundle.identityKey, bundle.oneTimeKey);
            var transfer = {msgPlaintext: dumps[0], groupInbound: e2eExportGroupInboundEntries(dumps[1])};
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

// So sánh danh sách thành viên CŨ (trước lần refresh này) với MỚI cho từng group đã mã hoá -- ai đó
// vừa biến mất (bị kick/tự rời) mà mình ĐÃ TỪNG gửi khoá outbound session hiện tại cho họ thì PHẢI
// rotate (xem e2eRotateOutboundGroupSession) để họ hết đọc được tin mình gửi SAU đó. Gọi sau mỗi lần
// refreshConversationList() -- đây là cách duy nhất phát hiện "vừa có người rời" phía những thành
// viên CÒN LẠI (server chỉ báo sống qua WS riêng cho đúng người BỊ xoá, xem RoutingVersionSync
// #onMemberRemoved, không báo cho người còn lại).
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
            // distributedTo lưu theo "userId|deviceId" (xem e2eGetOrCreateOutboundGroupSession), không
            // còn là userId trần -- so khớp PREFIX "id|" thay vì so bằng tuyệt đối.
            var needsRotate = removed.some(function (id) {
                return existing.distributedTo.some(function (entry) { return entry.indexOf(id + '|') === 0; });
            });
            if (needsRotate) return e2eRotateOutboundGroupSession(conv.conversationId, conv.memberUserIds);
        }).catch(function (err) { console.warn('rotate megolm session lỗi', err); });
    });
}

// === Backup lạnh (xuất/nhập file mã hoá cục bộ, KHÔNG BAO GIỜ qua server) ===
// Đã đối chiếu cách 3 hệ thống lớn làm (Matrix "key backup", Signal/WhatsApp "full backup"
// -- xem thảo luận trong lịch sử trò chuyện): Matrix chỉ backup KHOÁ Megolm vì server họ giữ
// ciphertext vĩnh viễn (có khoá là tự giải mã lại được, kể cả tin CHƯA TỪNG mở); Signal/WhatsApp
// backup thẳng NỘI DUNG đã giải mã vì server họ KHÔNG giữ ciphertext lâu dài. App này giống Matrix
// (bảng {@code messages} giữ ciphertext vĩnh viễn) NÊN với GROUP, backup {@code groupInbound} (khoá
// Megolm) là đủ và mạnh hơn hẳn backup nội dung; nhưng với DM (Olm Double Ratchet 2 chiều, KHÔNG
// backup session sống an toàn được -- cùng lý do đã giải thích ở mục "Liên kết thiết bị": rủi ro
// trùng message key nếu phục hồi rồi lỡ dùng song song với thiết bị khác), buộc phải backup thẳng
// {@code msgPlaintext} kiểu Signal/WhatsApp. Kết hợp cả 2 -- ĐÚNG NGUYÊN gói {msgPlaintext,
// groupInbound} đã dùng cho tính năng liên kết thiết bị, chỉ đổi kênh mã hoá.
//
// 2 CÁCH bảo vệ file (người dùng tự chọn, giống Matrix cho cả Security Key lẫn Security Phrase):
//  - "key": 32 byte ngẫu nhiên (256-bit, đủ entropy dùng THẲNG làm khoá AES, không cần KDF) -- hiện 1
//    LẦN DUY NHẤT dạng 64 ký tự hex để người dùng tự lưu (sổ tay, password manager...) -- giống đúng
//    "khoá khôi phục 64 ký tự" của Signal/WhatsApp. KHÔNG LƯU LẠI Ở ĐÂU trong app -- mất là mất thật.
//  - "phrase": mật khẩu tự đặt, entropy thấp hơn nên PHẢI qua PBKDF2 (210k vòng, khuyến nghị OWASP
//    2023) + salt ngẫu nhiên trước khi dùng làm khoá AES.

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

// Tạo nội dung file backup -- {@code mode} 'key' (random 256-bit, trả kèm {@code recoveryKeyHex} để
// UI hiện cho user LƯU LẠI NGAY, không có cách nào lấy lại lần 2) hoặc 'phrase' (mật khẩu tự đặt,
// không trả thêm gì vì người dùng tự nhớ). Trả {@code {fileContent, recoveryKeyHex?}} --
// {@code fileContent} là chuỗi JSON để UI tải xuống thành file.
function e2eCreateBackup(mode, phrase) {
    return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groupInbound')]).then(function (dumps) {
        var payload = JSON.stringify({msgPlaintext: dumps[0], groupInbound: e2eExportGroupInboundEntries(dumps[1])});
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

// Khôi phục từ nội dung file backup (chuỗi JSON, xem e2eCreateBackup) + khoá/mật khẩu tương ứng đúng
// {@code file.mode} -- import thẳng vào storage của THIẾT BỊ NÀY, KHÔNG đụng gì tới identity (giống
// hệt e2eApplyLinkedHistory của tính năng liên kết thiết bị, chỉ khác nguồn dữ liệu là file thay vì
// kênh Olm sống). Sai khoá/mật khẩu hoặc file hỏng -- AES-GCM tự phát hiện (authenticated encryption,
// không chỉ đơn thuần ra rác) -- báo lỗi rõ ràng thay vì import nhầm dữ liệu vô nghĩa.
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
