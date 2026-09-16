// Mã hoá đầu cuối (E2E) bằng MLS — RFC 9420, engine ts-mls (frontend/vendor/mls.js, build IIFE
// gán global `MLS`). 1 MLS group = 1 conversation (DM = group 2 members). Không còn fan-out
// per-device như Olm/Megolm: mọi client trong group cùng giải được 1 ciphertext duy nhất, nên
// server chỉ cần relay Welcome/Commit (tầng báo hiệu) + ciphertext tin nhắn (đi như MESSAGE
// thường có cờ `mls:true` qua colony). Server KHÔNG bao giờ thấy private key hay nội dung đã giải.
//
// Ciphersuite cố định: MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519 (id 1). Không cần thêm dependency
// (X25519+AES-GCM+Ed25519 nằm trong bundle đã build sẵn).
//
// Mỗi THIẾT BỊ (không phải user) có 1 bộ KeyPackage riêng. Client mới bật MLS sẽ sinh 1 lô
// KeyPackage, publish công khai (PUT /mls/key-packages). Khi ai đó add thiết bị này vào 1 group MLS,
// họ claim (dùng 1 lần) 1 KeyPackage của thiết bị này rồi gửi Welcome (mã hoá HPKE đúng cho keypackage
// đó) qua hàng đợi to-device. Thiết bị nhận mở Welcome, join group.
//
// Private key + group state không rời máy: encodeGroupState (TLS-serialize) lưu IndexedDB, private
// keypackage lưu IndexedDB. Xoá dữ liệu trình duyệt = mất khả năng đọc tin mới (phải join lại qua
// liên kết thiết bị) — mất luôn msgPlaintext cache nên mất lịch sử cũ (như mọi client MLS khác).
// Lấy lại lịch sử ĐÃ giải mã từ thiết bị khác cùng tài khoản qua "liên kết thiết bị" (mã 6 ký tự,
// chuyển qua 1 NHÓM MLS TẠM — xem cuối file) hoặc "sao lưu lạnh" (file AES-GCM).

// Toàn bộ symbol của ts-mls nằm trên global `MLS` (bundle IIFE). Gom vào 1 namespace cục bộ `M`
// để gọi tường minh, tránh lỗi "undefined function" (các biến này là const/function export, không
// phải global trần).
var M = (typeof MLS !== 'undefined') ? MLS : null;

var E2E_DB_NAME = 'pingo-mls';
var E2E_DB_VERSION = 1;
var MLS_CIPHERSUITE = 'MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519';
var MLS_PSEUDO_GROUPID_PREFIX = 'pingo:'; // groupId = "pingo:<conversationId>" (opaque bytes)
var MLS_CRED_PREFIX = 'user:';             // basic credential identity = "user:<userId>"
var MLS_DEVICE_ID_STORAGE = 'pingo_mls_device_id';
var MLS_KEYPACKAGE_LOW_WATERMARK = 8;
var MLS_KEYPACKAGE_TOP_UP = 10;

var e2eReady = false;          // true sau khi init MLS + đã có keypackage sẵn sàng dùng
var e2eReadyPromise = null;    // theo dõi lệnh init đang chạy (init async, gọi chồng => trả lại cùng Promise)
var e2eInitializedForUserId = null; // userId mà e2eReady đang đại diện — xem reset trong e2eInit
var e2eCs = null;              // Ciphersuite (object name->id) cache khỏi phải tra lại
var e2eImpl = null;            // CiphersuiteImpl (đã getCiphersuiteImpl) — engine crypto thật
var e2eDeviceId = null;        // id THIẾT BỊ NÀY (UUID, localStorage), public (gửi server để định vị keypackage)
var e2eDb = null;
// Chờ lượt e2eDrainToDevice() ĐẦU TIÊN của phiên (welcome/commit thật đang chờ sẵn trên server) xong
// TRƯỚC KHI cho self external-join (e2eTryExternalJoin) chạy -- bug thật đã gặp: enterApp() gọi
// refreshConversationList()/connect() (dẫn tới decrypt lịch sử) KHÔNG đợi e2eInit().then(e2eDrainToDevice)
// xong, nên lúc vừa login, 1 tin cũ cần decrypt có thể trúng nhánh "chưa có group cục bộ" và tự
// external-join TRƯỚC KHI Welcome thật (đúng epoch, đọc được lịch sử) kịp xử lý -- self external-join
// tạo 1 epoch MỚI, welcome thật xử lý sau đó lưu đè state nhưng epoch canonical trên server đã nhảy qua
// rồi, mọi tin TRƯỚC epoch đó (kể cả tin gửi ngay sau lúc tạo nhóm, trước khi mình từng login) vĩnh viễn
// "epoch too old" -- trong khi đáng lẽ Welcome thật (mình đã có KeyPackage từ trước, nằm trong Welcome
// ngay lúc tạo nhóm) phải cho đọc được từ epoch tạo nhóm. Có timeout (E2E_INITIAL_DRAIN_TIMEOUT_MS) để
// self-heal (member thật sự chưa từng có Welcome nào) không bị treo vĩnh viễn nếu drain lỗi/mạng treo.
var e2eInitialDrainPromise = null;
var e2eInitialDrainResolve = null;
var E2E_INITIAL_DRAIN_TIMEOUT_MS = 8000;
function e2eWaitInitialDrain() {
    if (!e2eInitialDrainPromise) return Promise.resolve();
    return Promise.race([e2eInitialDrainPromise, new Promise(function (resolve) { setTimeout(resolve, E2E_INITIAL_DRAIN_TIMEOUT_MS); })]);
}

// ===== IndexedDB (private key + group state + plaintext cache) =====
function e2eOpenDb() {
    if (e2eDb) return Promise.resolve(e2eDb);
    return new Promise(function (resolve, reject) {
        var req = indexedDB.open(E2E_DB_NAME, E2E_DB_VERSION);
        req.onupgradeneeded = function () {
            var db = req.result;
            // keyPackages: myUserId|deviceId -> {queue:[{pubB64, privJson}]} (FIFO, pub đã publish lên server)
            // groups:      myUserId|conversationId -> {stateB64} (encodeGroupState)
            // msgPlaintext: myUserId|messageId -> {body} (vì MLS private message giải 1 lần theo epoch/ratchet — cache lại cho load lịch sử)
            if (!db.objectStoreNames.contains('keyPackages')) db.createObjectStore('keyPackages');
            if (!db.objectStoreNames.contains('groups')) db.createObjectStore('groups');
            if (!db.objectStoreNames.contains('msgPlaintext')) db.createObjectStore('msgPlaintext');
        };
        req.onsuccess = function () {
            e2eDb = req.result;
            // Connection có thể bị trình duyệt tự đóng (tab đang unload/reload, hoặc 1 tab khác cùng
            // origin xoá/mở lại DB gây versionchange) MÀ code này không hề chủ động gọi .close() --
            // bug thật đã gặp: nếu không tự dọn cache `e2eDb` ở đây, MỌI lần gọi e2eOpenDb() SAU ĐÓ
            // trong CÙNG phiên trang (nếu trang vẫn còn sống, không phải đang unload thật) cứ trả lại
            // ĐÚNG connection đã chết -- db.transaction() ném thẳng "InvalidStateError: database
            // connection is closing" (Uncaught, vì đây là lỗi ĐỒNG BỘ bên trong executor Promise) cho
            // MỌI thao tác IndexedDB tiếp theo (mọi decrypt/encrypt/key-package) VĨNH VIỄN cho tới khi
            // người dùng tự F5 -- coi như toàn bộ E2E chết cứng dù trang vẫn đang chạy bình thường.
            e2eDb.onclose = function () { e2eDb = null; };
            e2eDb.onversionchange = function () { try { e2eDb.close(); } catch (e) {} e2eDb = null; };
            resolve(e2eDb);
        };
        req.onerror = function () { reject(req.error); };
    });
}
function e2eDbGet(store, key) {
    return e2eOpenDb().then(function (db) { return new Promise(function (resolve, reject) {
        var req = db.transaction(store, 'readonly').objectStore(store).get(key);
        req.onsuccess = function () { resolve(req.result === undefined ? null : req.result); };
        req.onerror = function () { reject(req.error); };
    }); });
}
function e2eDbPut(store, key, value) {
    return e2eOpenDb().then(function (db) { return new Promise(function (resolve, reject) {
        var tx = db.transaction(store, 'readwrite');
        tx.objectStore(store).put(value, key);
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { reject(tx.error); };
    }); });
}
function e2eDbDelete(store, key) {
    return e2eOpenDb().then(function (db) { return new Promise(function (resolve, reject) {
        var tx = db.transaction(store, 'readwrite');
        tx.objectStore(store).delete(key);
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { reject(tx.error); };
    }); });
}
function e2eDbGetAllForUser(store) {
    return e2eOpenDb().then(function (db) { return new Promise(function (resolve, reject) {
        var prefix = myUserId + '|';
        var out = [];
        var req = db.transaction(store, 'readonly').objectStore(store).openCursor();
        req.onsuccess = function () {
            var c = req.result;
            if (!c) { resolve(out); return; }
            if (String(c.key).indexOf(prefix) === 0) out.push({ key: c.key, value: c.value });
            c.continue();
        };
        req.onerror = function () { reject(req.error); };
    }); });
}
function e2eDbPutAll(store, entries) {
    if (!entries || !entries.length) return Promise.resolve();
    return e2eOpenDb().then(function (db) { return new Promise(function (resolve, reject) {
        var tx = db.transaction(store, 'readwrite');
        var s = db.objectStore ? null : tx.objectStore(store);
        entries.forEach(function (e) { tx.objectStore(store).put(e.value, e.key); });
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { reject(tx.error); };
    }); });
}
// Xoá MỌI entry của userId trong 1 store (key luôn có dạng "<userId>|...", xem e2eDbGetAllForUser)
// -- KHÔNG đụng dữ liệu của tài khoản khác từng đăng nhập chung trình duyệt này (IndexedDB dùng
// chung 1 origin cho MỌI account, không tách theo account như localStorage key). Nhận userId qua
// THAM SỐ (không đọc global myUserId) -- bug thật đã gặp: callback openCursor chạy BẤT ĐỒNG BỘ, nếu
// đọc global thì tới lúc chạy myUserId có thể ĐÃ bị logout() reset về '' (clearAuth() thường chạy
// trước khi promise IndexedDB kịp resolve), khiến prefix thành '|' và xoá nhầm/xoá hụt.
function e2eDbDeleteAllForUser(store, userId) {
    return e2eOpenDb().then(function (db) { return new Promise(function (resolve, reject) {
        var prefix = userId + '|';
        var tx = db.transaction(store, 'readwrite');
        var s = tx.objectStore(store);
        var req = s.openCursor();
        req.onsuccess = function () {
            var c = req.result;
            if (!c) return;
            if (String(c.key).indexOf(prefix) === 0) c.delete();
            c.continue();
        };
        tx.oncomplete = function () { resolve(); };
        tx.onerror = function () { reject(tx.error); };
    }); });
}
// Xoá SẠCH state MLS cục bộ của {@code userId} (private KeyPackage queue, group state đã join, cache
// tin đã giải mã) -- gọi lúc logout (xem auth.js) SAU KHI đã thu hồi thiết bị trên server, để login
// lại (kể cả trên đúng trình duyệt/tài khoản này) bắt buộc sinh deviceId + KeyPackage MỚI, không vô
// tình tái dùng deviceId ĐÃ BỊ THU HỒI (sẽ bị auth từ chối ngay từ request đầu tiên). Nhận userId qua
// tham số (không phải global) -- xem lý do ở e2eDbDeleteAllForUser.
function e2eClearLocalStateForCurrentUser(userId) {
    return Promise.all(['keyPackages', 'groups', 'msgPlaintext'].map(function (store) { return e2eDbDeleteAllForUser(store, userId); }));
}

// ===== base64 <-> bytes =====
function e2eB64(u8) { var s = ''; for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]); return btoa(s); }
function e2eUnb64(str) { var bin = atob(str); var b = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) b[i] = bin.charCodeAt(i); return b; }

// ===== deviceId (public, định vị keypackage trên server) =====
function e2eGetOrCreateDeviceId() {
    var k = MLS_DEVICE_ID_STORAGE + ':' + myUserId;
    var ex = localStorage.getItem(k);
    if (ex) return ex;
    var id = crypto.randomUUID();
    localStorage.setItem(k, id);
    return id;
}
function e2eGuessDeviceLabel() {
    var ua = navigator.userAgent || '';
    var browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
        : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Trình duyệt';
    var os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android'
        : /iPhone|iPad/.test(ua) ? 'iOS' : /Linux/.test(ua) ? 'Linux' : '';
    return os ? browser + ' trên ' + os : browser;
}

// ===== credential / groupId helpers =====
// Credential mang CẢ deviceId (không chỉ userId) -- 1 group member MLS = ĐÚNG 1 leaf = ĐÚNG 1 thiết
// bị (khác Olm cũ: mọi thiết bị cùng group giải chung được 1 ciphertext, nên "user" là đơn vị add
// đủ dùng). Với MLS, add 1 user KHÔNG tự động khiến MỌI thiết bị của họ đọc được -- phải add riêng
// từng thiết bị thành từng leaf (xem e2eDrainAddCommits/e2eCommitAddMany) -- bug thật đã gặp: add
// đại diện 1 thiết bị bất kỳ khiến các thiết bị khác của CHÍNH người đó (kể cả thiết bị họ đang thật
// sự dùng) kẹt vĩnh viễn ở "chưa tham gia nhóm", không có cách nào tự phục hồi.
var MLS_CRED_DEVICE_SEP = '@';
function e2eCredential() { return { credentialType: 'basic', identity: new TextEncoder().encode(MLS_CRED_PREFIX + myUserId + MLS_CRED_DEVICE_SEP + e2eDeviceId) }; }
function e2eGroupId(conversationId) { return new TextEncoder().encode(MLS_PSEUDO_GROUPID_PREFIX + conversationId); }
function e2eLeafIdentity(node) { try { return new TextDecoder().decode(node.leaf.credential.identity); } catch (e) { return ''; } }
// Tách userId khỏi phần "@<deviceId>" -- tương thích ngược với credential CŨ (trước khi thêm
// deviceId) không có dấu '@', coi nguyên phần còn lại là userId.
function e2eLeafUserId(node) {
    var id = e2eLeafIdentity(node);
    if (id.indexOf(MLS_CRED_PREFIX) !== 0) return null;
    var rest = id.slice(MLS_CRED_PREFIX.length);
    var sep = rest.indexOf(MLS_CRED_DEVICE_SEP);
    return sep === -1 ? rest : rest.slice(0, sep);
}
function e2eLeafDeviceId(node) {
    var id = e2eLeafIdentity(node);
    var sep = id.indexOf(MLS_CRED_DEVICE_SEP);
    return sep === -1 ? null : id.slice(sep + 1);
}
// LeafIndex thật của node leaf ở vị trí k = 2k ( RFC tree: leaves ở node index chẵn). ts-mls dùng LeafIndex
// = vị trí lá ĐẾM THEO THỨ TỰ (0,1,2...), xem spike: remove dùng leafIdx đếm trên leaves-filtered đúng.
function e2eLeafNodes(state) { return (state.ratchetTree || []).map(function (n, idx) { return { n: n, idx: idx }; }).filter(function (x) { return x.n && x.n.nodeType === 'leaf'; }); }
// TẤT CẢ leafIndex thuộc về targetUserId -- 1 user có thể có NHIỀU leaf (nhiều thiết bị), khác bản
// trước (e2eLeafIndexOf số ít) chỉ trả 1 -- dùng khi remove: phải loại HẾT thiết bị của người bị kick,
// không chỉ đúng 1 thiết bị đại diện.
function e2eLeafIndexesOf(state, targetUserId) {
    var leaves = e2eLeafNodes(state);
    var out = [];
    for (var i = 0; i < leaves.length; i++) { if (e2eLeafUserId(leaves[i].n) === targetUserId) out.push(i); }
    return out;
}
function e2eMemberUserIds(state) { return e2eLeafNodes(state).map(function (x) { return e2eLeafUserId(x.n); }).filter(function (id) { return id; }); }
// e2eMemberUserIds nhưng KHỬ TRÙNG -- dùng ở chỗ chỉ cần biết "những USER nào" đang trong group
// (không quan tâm 1 user có mấy leaf/thiết bị), ví dụ tính danh sách nhận 1 Commit chung.
function e2eMemberUserIdSet(state) { return Array.from(new Set(e2eMemberUserIds(state))); }

// ===== KeyPackage: local queue + publish + consume =====
function e2eKpKey() { return myUserId + '|' + e2eDeviceId; }
function e2eLoadKpQueue() {
    return e2eDbGet('keyPackages', e2eKpKey()).then(function (r) { return (r && r.queue) ? r.queue : []; });
}
function e2eSaveKpQueue(queue) { return e2eDbPut('keyPackages', e2eKpKey(), { queue: queue }); }

// Generate `count` keypackages, trả mảng {pubB64, privJson} để đưa vào queue + publish pubB64 lên server.
// PRIVATED keypackage KHÔNG BAO GIỜ rời máy — chỉ pubB64 (MLSCiphertext KeyPackage, public) lên server.
function e2eGenerateKeyPackages(count) {
    var chain = Promise.resolve([]);
    for (var i = 0; i < count; i++) {
        chain = chain.then(function (list) {
            return M.generateKeyPackage(e2eCredential(), M.defaultCapabilities(), M.defaultLifetime, [], e2eImpl).then(function (r) {
                var pubWire = M.encodeMlsMessage({ keyPackage: r.publicPackage, wireformat: 'mls_key_package', version: 'mls10' });
                list.push({
                    pubB64: e2eB64(pubWire),
                    privJson: JSON.stringify({ init: Array.from(r.privatePackage.initPrivateKey), hpke: Array.from(r.privatePackage.hpkePrivateKey), sig: Array.from(r.privatePackage.signaturePrivateKey) })
                });
                return list;
            });
        });
    }
    return chain;
}
function e2ePubToKeyPackage(pubB64) {
    var d = M.decodeMlsMessage(e2eUnb64(pubB64), 0)[0];
    if (d.wireformat !== 'mls_key_package') throw new Error('không phải keypackage wire');
    return d.keyPackage;
}
function e2ePrivFromJson(privJson) {
    var j = JSON.parse(privJson);
    return { initPrivateKey: new Uint8Array(j.init), hpkePrivateKey: new Uint8Array(j.hpke), signaturePrivateKey: new Uint8Array(j.sig) };
}

function e2ePublishKeyPackages(pubB64List) {
    if (!pubB64List.length) return Promise.resolve();
    return fetch(HISTORY_API_BASE + '/mls/key-packages', {
        method: 'PUT', headers: { 'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: e2eDeviceId, keyPackages: pubB64List })
    }).then(function (res) { if (!res.ok) throw new Error('upload keypackages HTTP ' + res.status); });
}

// Số KeyPackage server ĐANG THỰC SỰ giữ cho thiết bị này -- dùng để phát hiện lệch (server mất dữ
// liệu do restore/migrate DB, hoặc 1 lần publish trước đó lỗi giữa chừng mà không ai để ý).
function e2eFetchServerKeyPackageCount() {
    return fetch(HISTORY_API_BASE + '/mls/key-package-count?deviceId=' + encodeURIComponent(e2eDeviceId), {
        headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) { if (!res.ok) throw new Error('key-package-count HTTP ' + res.status); return res.json(); })
        .then(function (data) { return data.count || 0; });
}

// Top-up: nếu queue local (chưa dùng) còn ít hơn ngưỡng, sinh thêm + publish. Queue local giữ CẢ private,
// server chỉ giữ public để người khác claim. 1 keypackage chỉ dùng 1 lần (claim = xoá server-side).
// LUÔN đối chiếu với server count trước (bug thật đã gặp: queue local vẫn còn nhiều nhưng server đã
// mất hết bản public tương ứng -- vd restore/migrate DB -- client cứ đinh ninh "đủ rồi" và không bao
// giờ publish lại, khiến KHÔNG AI add được mình vào group MLS mới, kẹt vĩnh viễn ở "đang chờ thiết lập
// nhóm mã hoá" mà không có lỗi rõ ràng nào). Server thiếu so với queue cục bộ -> publish lại NGUYÊN
// các key đang có (không sinh mới, PUT là idempotent) để đồng bộ lại, trước khi xét có cần top-up thêm
// key mới hay không.
function e2eMaybeTopUpKeyPackages() {
    return e2eLoadKpQueue().then(function (queue) {
        return e2eFetchServerKeyPackageCount().catch(function (err) {
            // Không lấy được count (mạng lỗi...) -- coi như "chưa rõ", KHÔNG chặn hẳn init vì 1 lần gọi lỗi,
            // giả định server vẫn khớp local để giữ hành vi cũ (chỉ theo watermark local) làm fallback.
            console.warn('[e2e-mls] không lấy được server key-package count, fallback theo local', err);
            return queue.length;
        }).then(function (serverCount) {
            var resync = queue.length > 0 && serverCount < queue.length
                ? e2ePublishKeyPackages(queue.map(function (q) { return q.pubB64; }))
                : Promise.resolve();
            if (queue.length >= MLS_KEYPACKAGE_LOW_WATERMARK) return resync;
            return resync.then(function () {
                return e2eGenerateKeyPackages(MLS_KEYPACKAGE_TOP_UP).then(function (fresh) {
                    return e2eSaveKpQueue(queue.concat(fresh)).then(function () {
                        return e2ePublishKeyPackages(fresh.map(function (f) { return f.pubB64; }));
                    });
                });
            });
        });
    });
}

// Claim ĐÚNG 1 KeyPackage của MỖI thiết bị userId đang có (không phải 1 cái đại diện) -- dùng khi
// ADD 1 user vào group MLS, xem javadoc e2eCredential. Trả mảng {keyPackage, deviceId}, 1 phần tử/
// thiết bị -- caller tự add từng cái thành 1 leaf riêng.
function e2eClaimAllDeviceKeyPackagesFor(userId) {
    return fetch(HISTORY_API_BASE + '/mls/key-packages?userId=' + encodeURIComponent(userId) + '&allDevices=true', {
        headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) { if (!res.ok) throw new Error('claim keypackages HTTP ' + res.status); return res.json(); })
        .then(function (data) { return data.keyPackages || []; });
}

// ===== Quản lý thiết bị (màn hình "Hồ sơ của bạn" -> "Thiết bị của tôi", xem sidebar-conversations.js) =====
// Danh sách thiết bị CÒN SỐNG (chưa bị gỡ) của chính mình -- xem MlsDeviceRegistry#listDevices.
function e2eListDevices() {
    return fetch(HISTORY_API_BASE + '/mls/devices', {
        headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) { if (!res.ok) throw new Error('list devices HTTP ' + res.status); return res.json(); })
        .then(function (data) { return data.devices || []; });
}
// Gỡ (= THU HỒI ngay lập tức, xem MlsDeviceRegistry#revokeDevice) 1 thiết bị -- thiết bị đó bị từ
// chối MỌI request (kể cả AUTH qua WebSocket) từ giây phút này, dù JWT chưa hết hạn. Dùng cho cả
// "gỡ thiết bị khác" (refreshProfileDeviceList) lẫn tự thu hồi chính mình lúc logout (xem auth.js).
function e2eDeleteDevice(deviceId) {
    return fetch(HISTORY_API_BASE + '/mls/devices?deviceId=' + encodeURIComponent(deviceId), {
        method: 'DELETE', headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) { if (!res.ok) return res.json().catch(function () { return {}; }).then(function (d) { throw new Error(d.error || ('HTTP ' + res.status)); }); });
}
// Trong `deviceIds` (của BẤT KỲ userId nào -- server tra thẳng cache Hazelcast, không giới hạn theo
// chủ sở hữu, xem javadoc HallApiHandlers#checkRevokedMlsDevices), trả về đúng các deviceId ĐÃ BỊ
// THU HỒI -- dùng để tự phát hiện "leaf chết" trong cây MLS (xem e2ePruneDeadLeaves).
function e2eCheckRevokedDeviceIds(deviceIds) {
    if (!deviceIds.length) return Promise.resolve([]);
    return fetch(HISTORY_API_BASE + '/mls/devices/revoked-check', {
        method: 'POST', headers: { 'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceIds: deviceIds })
    }).then(function (res) { if (!res.ok) throw new Error('revoked-check HTTP ' + res.status); return res.json(); })
        .then(function (data) { return data.revokedDeviceIds || []; });
}

// ===== Group state =====
function e2eGroupKey(conversationId) { return myUserId + '|' + conversationId; }
function e2eLoadGroup(conversationId) {
    return e2eDbGet('groups', e2eGroupKey(conversationId)).then(function (r) {
        if (!r || !r.stateB64) return null;
        var st = M.decodeGroupState(e2eUnb64(r.stateB64), 0)[0];
        // decodeGroupState KHÔNG kèm clientConfig — gán lại default (spike phát hiện, nếu không sẽ TypeError khi createCommit)
        st.clientConfig = {
            keyRetentionConfig: M.defaultKeyRetentionConfig,
            lifetimeConfig: M.defaultLifetimeConfig,
            keyPackageEqualityConfig: M.defaultKeyPackageEqualityConfig,
            paddingConfig: M.defaultPaddingConfig,
            authService: M.defaultAuthenticationService
        };
        return st;
    });
}
function e2eSaveGroup(conversationId, state) {
    return e2eDbPut('groups', e2eGroupKey(conversationId), { stateB64: e2eB64(M.encodeGroupState(state)) });
}
function e2eDeleteGroup(conversationId) {
    return e2eDbDelete('groups', e2eGroupKey(conversationId));
}

// KHOÁ 1-LẦN-1-LÚC theo conversationId cho MỌI thao tác đọc-sửa-ghi group state (load -> process ->
// save) -- bug thật đã tự bắt được lúc viết test: e2eHandleCommit (tới qua "E2E_TO_DEVICE") và
// e2eDecryptIncomingBody (tới qua "MESSAGE") KHÔNG hề chain với nhau trong history-ws.js's onmessage
// (2 case độc lập, mỗi case tự gọi thẳng không đợi case kia) -- nếu 1 tin ỨNG DỤNG bình thường (epoch
// cũ) và 1 COMMIT (epoch mới, do người khác add/remove) tới CÙNG conversation gần như đồng thời, cả 2
// cùng load group từ CÙNG state gốc rồi race nhau save: nếu save của tin thường xong SAU save của
// commit, epoch cục bộ bị TỤT LẠI (mất hẳn epoch bump) dù server + thành viên khác đã ở epoch mới --
// từ đó conversation này epoch-mismatch vĩnh viễn, mọi tin sau "không giải mã được"/"epoch too old".
// Y hệt lớp bug e2eExternalJoinInFlight đã vá cho external-join, nhưng CHƯA áp dụng cho commit/decrypt/
// gửi tin thường -- dùng 1 khoá CHUNG (áp cho cả 3 nhánh) thay vì vá riêng lẻ.
var e2eConvLocks = {}; // conversationId -> promise (đuôi hàng đợi hiện tại)
function e2eWithConvLock(conversationId, fn) {
    var key = String(conversationId);
    var prev = e2eConvLocks[key] || Promise.resolve();
    var run = prev.catch(function () {}).then(fn);
    // Luôn giữ chain "sống" dù fn() reject -- 1 lượt lỗi (VD epoch lệch/decrypt fail) không được phép
    // làm kẹt khoá vĩnh viễn cho các lượt SAU của CÙNG conversation.
    e2eConvLocks[key] = run.catch(function () {});
    return run;
}

// KHOÁ CHUNG (không theo conversationId -- hàng đợi KeyPackage là 1 tài nguyên DUY NHẤT/thiết bị,
// dùng chung cho MỌI conversation) cho mọi thao tác đọc-sửa-ghi 'keyPackages' (e2eLoadKpQueue/
// e2eSaveKpQueue) -- bug thật đã tự bắt được lúc viết test: e2eCheckGroupRotations forEach gọi
// e2eCreateGroupWithMembers cho TỪNG conversation dưới khoá RIÊNG của conversation đó (e2eWithConvLock)
// -- khoá đó KHÔNG bảo vệ được hàng đợi KP dùng CHUNG. Nếu 2 conversation khác nhau cùng cần
// proactive-create group gần như đồng thời, cả 2 CÙNG thấy queue dưới watermark, CÙNG generate +
// publish lên server, rồi race nhau save -- lượt lưu SAU đè mất batch của lượt lưu TRƯỚC: server đã
// publish CẢ 2 batch (key package public, ai cũng claim được) nhưng máy này chỉ còn giữ private key
// của 1 batch -- batch kia thành key "mồ côi", ai claim trúng để add mình vào group mới sẽ khiến
// welcome đó KHÔNG BAO GIỜ join được (e2eJoinWelcomeTry thử hết queue, không khớp, im lặng fail).
// CHỈ bọc ở CÁC ĐIỂM VÀO cấp cao nhất (e2eInit, e2eCreateGroupWithMembers, e2eJoinWelcomeTry,
// e2eApproveDeviceLink, e2eDoTryExternalJoin) -- KHÔNG bọc thêm bên trong e2eMaybeTopUpKeyPackages/
// e2eExternalJoinAttempt (chúng luôn được gọi TỪ BÊN TRONG 1 trong 5 điểm trên), tránh gọi lồng cùng
// key (2 lượt e2eWithConvLock lồng nhau cùng key sẽ tự deadlock -- lượt trong đợi lượt ngoài xong mới
// chạy, lượt ngoài lại đang đợi chính lượt trong).
var E2E_KP_QUEUE_LOCK_KEY = '__kpqueue__';
function e2eWithKpQueueLock(fn) { return e2eWithConvLock(E2E_KP_QUEUE_LOCK_KEY, fn); }

// ===== GroupInfo công khai (RFC 9420 "External Commit") =====
// Cho phép 1 THÀNH VIÊN đã có trong conversation (DB) nhưng CHƯA TỪNG join được group MLS (chưa ai
// online add hộ qua Welcome -- ví dụ họ chỉ vừa đăng nhập lần đầu) TỰ MÌNH join ngay, KHÔNG PHỤ
// THUỘC có ai khác đang online hay không -- bug thật đã gặp: trước đây member mới CHỈ có 1 con
// đường DUY NHẤT vào nhóm là được người khác (đã có group cục bộ) chủ động add hộ; nếu không ai
// online/chủ động làm gì, người mới kẹt vĩnh viễn dù đã là thành viên hợp lệ. GroupInfo là opaque
// blob (server không đọc được nội dung, giống KeyPackage) chứa ratchet_tree + "external_pub" --
// BẤT KỲ AI publish lại lên server mỗi lần epoch đổi (tạo nhóm/add/remove/nhận commit VÀ khi TỰ
// external-join), best-effort (publish lỗi không chặn luồng chính, chỉ khiến external-join tạm
// dùng epoch cũ hơn 1 chút -- ts-mls tự xử lý epoch qua Commit bình thường).
// Publish "best-effort" (KHÔNG cần biết ai thắng/thua) -- dùng cho e2eHandleCommit (đang ÁP DỤNG lại
// 1 commit ĐÃ ĐÚNG/đã được server chấp nhận từ nơi khác, không phải mình tự tạo commit) -- server có
// từ chối (đã có epoch mới hơn publish trước) cũng không sao, state cục bộ của mình vẫn đúng.
function e2ePublishGroupInfoBestEffort(conversationId, expectedEpoch, state) {
    return e2ePublishGroupInfo(conversationId, expectedEpoch, state).catch(function (err) {
        console.warn('[e2e-mls] publish group info lỗi (không chặn)', err);
    });
}
// CAS (compare-and-swap) theo epoch -- xem javadoc bảng mls_group_info/HallApiHandlers#putMlsGroupInfo.
// {@code expectedEpoch} = epoch TRƯỚC commit vừa tạo (state cũ mình dựa vào). Trả
// {won:true} nếu server chấp nhận, {won:false, groupInfoB64, epoch} (GroupInfo/epoch HIỆN TẠI trên
// server) nếu THUA -- caller PHẢI bỏ hẳn commit/state vừa tạo khi thua, không lưu/deliver.
function e2ePublishGroupInfo(conversationId, expectedEpoch, state) {
    return M.createGroupInfoWithExternalPubAndRatchetTree(state, [], e2eImpl).then(function (gi) {
        var b64 = e2eB64(M.encodeMlsMessage({ groupInfo: gi, wireformat: 'mls_group_info', version: 'mls10' }));
        var newEpoch = Number(state.groupContext.epoch);
        return fetch(HISTORY_API_BASE + '/mls/group-info?conversationId=' + encodeURIComponent(conversationId), {
            method: 'PUT', headers: { 'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json' },
            body: JSON.stringify({ groupInfo: b64, expectedEpoch: expectedEpoch, newEpoch: newEpoch })
        }).then(function (res) {
            if (!res.ok) throw new Error('publish group info HTTP ' + res.status);
            return res.json();
        }).then(function (data) {
            if (data.ok) return { won: true };
            return { won: false, groupInfoB64: data.groupInfo || null, epoch: data.epoch };
        });
    });
}
function e2eFetchGroupInfo(conversationId) {
    return fetch(HISTORY_API_BASE + '/mls/group-info?conversationId=' + encodeURIComponent(conversationId), {
        headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) { if (!res.ok) throw new Error('fetch group info HTTP ' + res.status); return res.json(); })
        .then(function (data) { return { groupInfoB64: data.groupInfo || null, epoch: data.epoch }; });
}

// TỰ join group bằng GroupInfo công khai (External Commit) -- KHÔNG cần ai add hộ. Dùng khi mình LÀ
// thành viên conversation (server xác nhận qua GET /mls/group-info) nhưng chưa có group cục bộ nào
// (chưa từng nhận Welcome). Trả state MỚI nếu thành công, null nếu group thật sự chưa từng tồn tại
// (chưa ai publish GroupInfo -- lúc đó vẫn đúng là phải chờ creator tạo nhóm lần đầu).
//
// KHOÁ 1-LẦN-1-LÚC theo conversationId -- bug thật đã gặp: gửi (hoặc retry) nhiều tin liên tiếp
// NGAY SAU lúc login lần đầu, trước khi lần external-join ĐẦU TIÊN kịp lưu xong group cục bộ
// (bất đồng bộ), mỗi lần gửi đều thấy "chưa có group" và TỰ TẠO THÊM 1 EXTERNAL COMMIT RIÊNG --
// nhiều external commit cùng dựa trên 1 epoch GroupInfo CŨ (chưa ai kịp thấy epoch mới) tạo ra
// NHIỀU LEAF XUNG ĐỘT cho CÙNG 1 người, phía nhận giải mã sai ("Không giải mã được tin nhắn MLS").
// Lần gọi sau khi ĐÃ có 1 lần đang chạy dùng LẠI đúng promise đó thay vì tự join thêm lần nữa.
var e2eExternalJoinInFlight = {};
function e2eTryExternalJoin(conversationId) {
    if (e2eExternalJoinInFlight[conversationId]) return e2eExternalJoinInFlight[conversationId];
    var clear = function () { delete e2eExternalJoinInFlight[conversationId]; };
    // Đợi lượt drain-to-device ĐẦU TIÊN của phiên xong trước (xem javadoc e2eInitialDrainPromise) --
    // nếu Welcome thật đang nằm sẵn trong hàng đợi, nó cần được xử lý TRƯỚC, không phải đua song song
    // với external-join (thua thì mất trắng lịch sử epoch cũ, dù mình có Welcome đúng epoch đó chờ sẵn).
    // e2eDoTryExternalJoin tự re-check e2eLoadGroup ngay khi bắt đầu, nên nếu Welcome đã tới trong lúc
    // đợi thì external-join tự thành no-op (trả về state Welcome vừa lưu), không tốn thêm 1 epoch nào.
    var p = e2eWaitInitialDrain().then(function () { return e2eDoTryExternalJoin(conversationId); });
    p.then(clear, clear);
    e2eExternalJoinInFlight[conversationId] = p;
    return p;
}
function e2eDoTryExternalJoin(conversationId) {
    return e2eLoadGroup(conversationId).then(function (state) {
        if (state) return state; // race: vừa nhận Welcome/join xong ở nơi khác trong lúc đang xử lý
        return e2eFetchGroupInfo(conversationId).then(function (gi) {
            if (!gi.groupInfoB64) return null; // group thật sự chưa từng tồn tại -- không có gì để tự join vào
            // e2eWithKpQueueLock -- xem javadoc chỗ khai báo: e2eExternalJoinAttempt tiêu 1 KeyPackage
            // từ hàng đợi CHUNG, không được race với top-up/tiêu key ở 1 conversation KHÁC đang chạy
            // song song.
            return e2eWithKpQueueLock(function () { return e2eExternalJoinAttempt(conversationId, gi, 1); });
        });
    });
}

// Thử tự join bằng ĐÚNG 1 GroupInfo/epoch cụ thể -- CAS lúc publish (xem javadoc bảng
// mls_group_info) có thể THUA nếu ai đó (vd creator đang add member khác) thắng epoch này trước
// đúng lúc mình đang xử lý. KHÁC e2eCreateCommitAndDistribute/e2eCommitRemove (người thua ở đó vẫn
// là member CŨ, tự bắt kịp qua to-device khi commit của người thắng tới) -- ở đây mình CHƯA là
// member nào cả nên KHÔNG ai deliver commit của người thắng cho mình, phải TỰ thử lại bằng
// GroupInfo/epoch MỚI mà CAS trả kèm lúc thua, giới hạn số lần (E2E_EXTERNAL_JOIN_MAX_ATTEMPTS) để
// không lặp vô hạn nếu 2 bên cứ liên tục đụng epoch nhau.
var E2E_EXTERNAL_JOIN_MAX_ATTEMPTS = 4;
function e2eExternalJoinAttempt(conversationId, gi, attempt) {
    return e2eLoadKpQueue().then(function (queue) {
        if (queue.length) return queue;
        return e2eMaybeTopUpKeyPackages().then(function () { return e2eLoadKpQueue(); }).then(function (q) {
            if (!q.length) throw new Error('không sinh được keypackage để tự tham gia nhóm');
            return q;
        });
    }).then(function (queue) {
        var head = queue[0];
        var myPub = e2ePubToKeyPackage(head.pubB64);
        var myPriv = e2ePrivFromJson(head.privJson);
        var giMsg = M.decodeMlsMessage(e2eUnb64(gi.groupInfoB64), 0)[0];
        return M.joinGroupExternal(giMsg.groupInfo, myPub, myPriv, false, e2eImpl).then(function (res) {
            return e2ePublishGroupInfo(conversationId, gi.epoch, res.newState).then(function (cas) {
                if (!cas.won) {
                    if (attempt >= E2E_EXTERNAL_JOIN_MAX_ATTEMPTS || !cas.groupInfoB64) {
                        throw new Error('tự tham gia nhóm thất bại (đụng độ liên tục với người khác đang đổi nhóm)');
                    }
                    console.warn('[e2e-mls] thua CAS lúc external-join (epoch ' + gi.epoch + '), thử lại với epoch ' + cas.epoch);
                    return e2eExternalJoinAttempt(conversationId, { groupInfoB64: cas.groupInfoB64, epoch: cas.epoch }, attempt + 1);
                }
                // Thắng CAS -- giờ mới thật sự tiêu KeyPackage (bỏ khỏi queue) + lưu state + báo cho
                // mọi thành viên hiện có biết mình vừa tự join, họ cần Commit này để cập nhật ratchet
                // tree đúng dù không ai "mời" mình cả.
                return e2eSaveKpQueue(queue.slice(1)).then(function () {
                    return e2eSaveGroup(conversationId, res.newState).then(function () {
                        // BUG thật đã gặp: res.publicMessage (từ joinGroupExternal) là PHẦN THÂN
                        // {content,auth,wireformat} CHƯA bọc lớp {version, wireformat, publicMessage}
                        // mà encodeMlsMessage cần ở NGOÀI CÙNG (khác res.commit của createCommit -- đã
                        // tự bọc sẵn) -- gọi thẳng encodeMlsMessage(res.publicMessage) LUÔN ném "undefined
                        // is not iterable" bên trong mls.js, khiến bước báo các thành viên khác biết
                        // mình vừa tự join KHÔNG BAO GIỜ chạy tới (external-join coi như "thành công" cục
                        // bộ nhưng không ai khác biết) -- các thành viên cũ sau đó tự phát hiện thiếu
                        // mình qua đồng bộ định kỳ rồi ADD LẠI bằng 1 leaf HOÀN TOÀN KHÁC (dùng keypackage
                        // dự phòng khác), tạo ra 2 leaf xung đột cho CÙNG 1 người -> gốc rễ của cả loạt
                        // lỗi "Commit cannot contain multiple ... proposals ... same leaf" / giải mã sai
                        // đã gặp suốt phiên debug này. Phải bọc đúng khuôn trước khi encode.
                        var commitB64 = e2eB64(M.encodeMlsMessage({ version: 'mls10', wireformat: 'mls_public_message', publicMessage: res.publicMessage }));
                        var present = e2eMemberUserIdSet(res.newState).filter(function (id) { return id !== myUserId; });
                        var deliveries = present.map(function (id) { return { recipientUserId: id, type: 'mls_commit', conversationId: conversationId, body: { commit: commitB64 } }; });
                        return e2eDeliverBatch(deliveries).then(function () { return res.newState; });
                    });
                });
            });
        });
    });
}

// ===== plaintext cache (vì MLS private message chỉ giải được 1 lần theo ratchet position) =====
function e2eCachePlaintext(messageId, plainBody) { return e2eDbPut('msgPlaintext', myUserId + '|' + messageId, { body: plainBody }); }
function e2eGetCachedPlaintext(messageId) { return e2eDbGet('msgPlaintext', myUserId + '|' + messageId).then(function (r) { return r ? r.body : null; }); }

// ===== Init =====
// Điểm vào DUY NHẤT từ enterApp(). Sinh keypackage lô đầu nếu chưa có, publish, bật cờ ready.
function e2eInit() {
    if (e2eInitializedForUserId !== myUserId) {
        e2eReady = false; e2eReadyPromise = null; e2eImpl = null; e2eCs = null; e2eDeviceId = null;
        e2eInitialDrainPromise = null; e2eInitialDrainResolve = null;
    }
    if (e2eReadyPromise) return e2eReadyPromise;
    if (e2eReady) return Promise.resolve();
    if (!M) return Promise.resolve(); // vendor/mls.js chưa load được -> E2E tự tắt, app vẫn chạy
    e2eInitializedForUserId = myUserId;
    e2eDeviceId = e2eGetOrCreateDeviceId();
    // Tạo SẴN promise chờ drain ở đây (trước cả khi e2eReady=true) -- đóng hẳn race window: bất kỳ
    // decrypt/rotation nào gọi e2eTryExternalJoin (chỉ có thể xảy ra sau khi e2eReady=true, tức sau
    // dòng này) đều thấy e2eInitialDrainPromise đã tồn tại và đợi đúng lượt drain của phiên này.
    e2eInitialDrainPromise = new Promise(function (resolve) { e2eInitialDrainResolve = resolve; });
    e2eReadyPromise = Promise.resolve()
        .then(function () { e2eCs = M.getCiphersuiteFromName(MLS_CIPHERSUITE); return M.getCiphersuiteImpl(e2eCs); })
        .then(function (impl) { e2eImpl = impl; })
        // e2eWithKpQueueLock -- xem javadoc chỗ khai báo: chỉ mình e2eInit không tự đụng chính nó
        // (đã memo hoá qua e2eReadyPromise), nhưng vẫn có thể chạy chồng lên e2eCreateGroupWithMembers/
        // e2eDoTryExternalJoin của 1 conversation khác nếu chúng khởi động ngay sau khi e2eReady=true
        // (giữa 2 lượt top-up của chính init này) -- khoá chung để không đụng độ.
        .then(function () {
            return e2eWithKpQueueLock(function () {
                return e2eLoadKpQueue()
                    .then(function (queue) { return queue.length ? null : e2eMaybeTopUpKeyPackages(); })
                    .then(function () { e2eReady = true; return e2eMaybeTopUpKeyPackages(); });
            });
        })
        .catch(function (err) { console.warn('[e2e-mls] init lỗi — không dùng được MLS phiên này', err); e2eReady = false; });
    return e2eReadyPromise;
}

// ===== Enable encryption cho conversation =====
// Chỉ PUT server + refresh. Group MLS sẽ do CREATOR (min userId) proactive tạo ở e2eCheckGroupRotations
// khi refresh thấy e2eEnabled && !state -- tránh split-brain (2 người cùng tạo 2 group riêng).
function e2eEnableConversation(conversationId) {
    return fetch(HISTORY_API_BASE + '/conversations/e2e?conversationId=' + encodeURIComponent(conversationId), {
        method: 'PUT', headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) {
        if (!res.ok) return res.json().catch(function () { return {}; }).then(function (d) { throw new Error(d.error || ('HTTP ' + res.status)); });
        refreshConversationList();
    });
}

// ===== Tạo group + add mọi thành viên (lần đầu gửi tin trong conv đã bật E2E) =====
// Nhóm MLS luôn có MÌNH + tất cả memberUserIds hiện tại. Ta tạo group chỉ chứa chính mình rồi
// Add lần lượt từng người khác (mỗi Add = 1 commit + 1 Welcome riêng cho người đó).
// Vì mỗi Add là 1 epoch mới, client khác join đúng epoch qua Welcome (chứa ratchet_tree extension).
function e2eCreateGroupWithMembers(conversationId, memberUserIds) {
    var others = memberUserIds.filter(function (id) { return id !== myUserId; });
    // e2eWithKpQueueLock -- xem javadoc chỗ khai báo: chỉ bọc phần TIÊU key khỏi hàng đợi chung (load
    // -> maybe top-up -> pop+save), KHÔNG bọc cả e2eDrainAddCommits (network/commit riêng của group
    // này, không đụng hàng đợi KP) để tránh giữ khoá chung lâu hơn cần thiết.
    return e2eWithKpQueueLock(function () {
        return e2eLoadKpQueue().then(function (queue) {
            if (!queue.length) return e2eMaybeTopUpKeyPackages().then(function () { return e2eLoadKpQueue(); }).then(function (q) { queue = q; if (!queue.length) throw new Error('không sinh được keypackage'); return queue; });
            return queue;
        }).then(function (queue) {
            var head = queue[0];
            var minePub = e2ePubToKeyPackage(head.pubB64);
            var minePriv = e2ePrivFromJson(head.privJson);
            return e2eSaveKpQueue(queue.slice(1)).then(function () { return { minePub: minePub, minePriv: minePriv }; });
        });
    }).then(function (mine) {
        return M.createGroup(e2eGroupId(conversationId), mine.minePub, mine.minePriv, [], e2eImpl)
            .then(function (state) { return e2eSaveGroup(conversationId, state).then(function () { return { state: state, pendingAdds: others }; }); })
            .then(function (st) { return e2eDrainAddCommits(conversationId, st); });
    });
}

// Vòng Add tuần tự: add người 1 -> tạo commit (ratchetTreeExtension=true) -> gửi Welcome cho người đó
// -> gửi Commit (PublicMessage) cho MỌI thành viên ĐÃ có trong group (trừ người vừa add) -> save state.
// ADD HẾT các thiết bị hiện có của người này (1 leaf/thiết bị, xem e2eClaimAllDeviceKeyPackagesFor) --
// không chỉ 1 thiết bị đại diện, để MỌI thiết bị của họ đều đọc được tin nhóm (xem javadoc e2eCredential).
function e2eDrainAddCommits(conversationId, st) {
    if (!st.pendingAdds.length) return Promise.resolve(st.state);
    var next = st.pendingAdds[0];
    var rest = st.pendingAdds.slice(1);
    // Bỏ qua người không còn là member? (chấp nhận add hết memberUserIds tại thời điểm gọi).
    return e2eClaimAllDeviceKeyPackagesFor(next).then(function (bundles) {
        if (!bundles.length) {
            console.warn('[e2e-mls] người này chưa bật MLS, không add được:', next);
            // Báo NGAY cho người đang tạo group biết -- trước đây chỉ console.warn (im lặng), người kia
            // kẹt vĩnh viễn ở "đang chờ thiết lập nhóm mã hoá" mà không ai biết lý do thật (xem
            // e2eMaybeTopUpKeyPackages ở trên cho 1 nguyên nhân cụ thể đã gặp). showToast TRƯỚC ĐÂY tự
            // biến mất sau 2.2s -- việc này thường xảy ra NỀN (proactive rotation/sync), người dùng
            // không đứng nhìn màn hình đúng lúc đó thì mất luôn cảnh báo -- đổi sang banner NẰM LẠI
            // trong header đoạn chat (showConvE2eWarning, xem messaging-core.js) tới khi tự đóng.
            if (typeof showConvE2eWarning === 'function') showConvE2eWarning(conversationId, next, (typeof displayName === 'function' ? displayName(next) : next) + ' chưa sẵn sàng mã hoá -- tin nhắn sẽ KHÔNG đến được cho người này cho tới khi họ mở lại app');
            return e2eDrainAddCommits(conversationId, { state: st.state, pendingAdds: rest });
        }
        var adds = bundles.map(function (b) { return { userId: next, keyPackage: e2ePubToKeyPackage(b.keyPackage) }; });
        return e2eCreateCommitAndDistribute(conversationId, st.state, adds, rest).then(function (newState) {
            // Add thành công -- nếu người này TỪNG bị báo "chưa sẵn sàng" ở 1 lượt trước đó (VD lần
            // trước claim rỗng, lần này họ đã mở app publish key), tự xoá đúng dòng cảnh báo của họ.
            if (typeof clearConvE2eWarningForUser === 'function') clearConvE2eWarningForUser(conversationId, next);
            return e2eDrainAddCommits(conversationId, { state: newState, pendingAdds: rest });
        });
    });
}

// 1 Add-commit chứa 1 hay nhiều Add proposal (có thể NHIỀU proposal cùng userId -- add nhiều thiết
// bị của CÙNG 1 người trong 1 lần, xem e2eDrainAddCommits/e2eCommitAddMany), broadcast Welcome/Commit
// tới đúng đối tượng. Trả về newState ĐÃ LƯU. consumed keys được zero-out.
function e2eCreateCommitAndDistribute(conversationId, state, adds, pendingAddsForWelcome) {
    var addProposals = adds.map(function (a) { return { proposalType: 'add', add: { keyPackage: a.keyPackage } }; });
    // userId nào ĐÃ là member TRƯỚC commit này (dù đang add THÊM 1 thiết bị mới cho chính họ) --
    // dùng để tính đúng "present" bên dưới, phân biệt với userId HOÀN TOÀN MỚI (chỉ nhận Welcome).
    var wasAlreadyMember = new Set(e2eMemberUserIds(state));
    var expectedEpoch = Number(state.groupContext.epoch);
    return M.createCommit({ state: state, cipherSuite: e2eImpl }, { extraProposals: addProposals, ratchetTreeExtension: true }).then(function (res) {
        var newState = res.newState;
        (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
        // CAS (khử split-brain, xem javadoc mls_group_info) TRƯỚC KHI lưu/deliver bất cứ gì -- THUA
        // (ai đó đã thắng epoch này trước mình, vd 1 member khác đang tự External-Join CÙNG LÚC) thì
        // bỏ HẲN commit vừa tạo, không lưu state cục bộ, không báo ai. Người thắng tự khắc deliver
        // Commit của HỌ cho mình (mình vẫn "present" ở state CŨ trước khi thua) -- mình tự bắt kịp
        // qua đường bình thường (e2eHandleCommit) khi commit đó tới, không cần tự retry ở đây.
        return e2ePublishGroupInfo(conversationId, expectedEpoch, newState).then(function (cas) {
            if (!cas.won) {
                console.warn('[e2e-mls] thua CAS lúc add-commit (epoch ' + expectedEpoch + ') -- bỏ commit, chờ commit của người thắng tới qua to-device');
                throw Object.assign(new Error('CAS lost khi add-commit'), { e2eCasLost: true });
            }
            var commitWire = M.encodeMlsMessage(res.commit); // commit dạng PublicMessage (wireAsPublicMessage mặc định? tạo ra commit = MLSMessage) — relay cho member hiện tại
            var welcomeWire = res.welcome ? M.encodeMlsMessage({ welcome: res.welcome, wireformat: 'mls_welcome', version: 'mls10' }) : null;
            var deliveries = [];
            // Welcome cho từng NGƯỜI được add (KHỬ TRÙNG theo userId, không phải theo leaf) -- 1 Welcome
            // MLS có thể mang bí mật cho NHIỀU leaf mới cùng lúc (kể cả nhiều leaf của CÙNG 1 người, mỗi
            // leaf ứng 1 thiết bị), và hạ tầng to-device vốn PER-USER (mọi thiết bị của họ đều nhận được
            // relay), nên chỉ cần gửi ĐÚNG 1 lần/người -- mỗi thiết bị tự thử khớp ĐÚNG leaf của mình qua
            // e2eJoinWelcomeTry (thử từng private key cục bộ cho tới khi khớp, bỏ qua nếu không phải phần
            // dành cho mình). Gửi trùng nhiều lần không sai nhưng lãng phí.
            if (welcomeWire) {
                Array.from(new Set(adds.map(function (a) { return a.userId; }))).forEach(function (uid) {
                    deliveries.push({ recipientUserId: uid, type: 'mls_welcome', conversationId: conversationId, body: { welcome: e2eB64(welcomeWire) } });
                });
            }
            // Commit cho các thành viên ĐÃ CÓ MẶT TỪ TRƯỚC commit này (kể cả người đang được add THÊM 1
            // thiết bị mới -- thiết bị CŨ của họ vẫn cần Commit để biết cây vừa đổi, chỉ thiết bị MỚI mới
            // chỉ cần Welcome). Người HOÀN TOÀN MỚI (chưa từng có leaf nào) bị loại -- họ chỉ cần Welcome.
            var present = e2eMemberUserIdSet(newState).filter(function (id) { return id !== myUserId && wasAlreadyMember.has(id); });
            present.forEach(function (id) {
                deliveries.push({ recipientUserId: id, type: 'mls_commit', conversationId: conversationId, body: { commit: e2eB64(commitWire) } });
            });
            return e2eDeliverBatch(deliveries).then(function () { return e2eSaveGroup(conversationId, newState); }).then(function () { return newState; });
        });
    });
}

function e2eDeliverBatch(deliveries) {
    if (!deliveries.length) return Promise.resolve();
    return fetch(HISTORY_API_BASE + '/e2e/to-device', {
        method: 'POST', headers: { 'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json' },
        body: JSON.stringify({ deliveries: deliveries })
    }).then(function (res) { if (!res.ok) throw new Error('deliver batch HTTP ' + res.status); });
}

// ===== Đồng bộ membership: gửi tin cần group chứa ĐÚNG member hiện tại =====
// Nếu group local thiếu người -> add họ (commit). Nếu group local THỪA người (bị kick/rời) -> remove họ
// (commit) rồi broadcast cho các member còn lại. Làm TRƯỚC KHI encrypt để epoch luôn khớp member list.
// CHỈ người bật E2E (creator) mới tạo group (ở e2eEnableConversation). Người khác KHÔNG tự tạo khi
// chưa có state local -- chờ welcome từ creator (e2eHandleWelcome -> e2eJoinWelcomeTry). Tránh
// SPLIT-BRAIN (2 bên cùng tạo 2 group riêng -> encrypt/decrypt chéo -> AEAD OperationError).
function e2eSyncGroupMembers(conversationId, memberUserIds) {
    return e2eLoadGroup(conversationId).then(function (state) {
        if (!state) {
            // Chưa có group local: KHÔNG tự tạo (tránh split-brain). Chờ welcome từ creator
            // (min userId, proactive tạo ở e2eCheckGroupRotations). Trả null -> caller báo "đang chờ".
            return null;
        }
        var want = memberUserIds.slice();
        var have = e2eMemberUserIds(state); // 1 phần tử/LEAF -- 1 người nhiều thiết bị lặp lại nhiều lần, phải khử trùng trước khi so sánh member-list (không phải leaf-list)
        var haveUnique = Array.from(new Set(have));
        // Cảnh báo "chưa sẵn sàng mã hoá" (showConvE2eWarning, xem e2eDrainAddCommits/e2eCommitAddMany)
        // lưu CỤC BỘ theo từng trình duyệt/thiết bị (localStorage) -- nếu người vừa add-commit THÀNH
        // CÔNG lại là 1 THIẾT BỊ/PHIÊN KHÁC (không phải máy đang hiện cảnh báo -- VD chính mình có 2
        // thiết bị, hoặc người kia trong nhóm là bên thắng race add), máy đang hiện cảnh báo không có
        // cách nào TỰ BIẾT để xoá nếu chỉ xoá đúng tại chỗ add-commit vừa thành công (bug thật vừa tự
        // phát hiện). e2eSyncGroupMembers chạy ĐỊNH KỲ trên MỌI thiết bị/phiên (qua e2eCheckGroupRotations,
        // e2eMaybeEncryptForSend) và luôn tính lại ĐÚNG THỰC TẾ HIỆN TẠI (haveUnique, đọc thẳng từ cây
        // thành viên MLS) -- bất kỳ ai đã có mặt trong đó, BẤT KỂ thiết bị/phiên nào đã add họ, đều an
        // toàn để tự dọn cảnh báo cũ của chính người đó ở ĐÂY -- tự "hội tụ" đúng trên mọi thiết bị sau
        // tối đa 1 lượt sync, không cần đồng bộ trực tiếp giữa các thiết bị/phiên với nhau.
        if (typeof clearConvE2eWarningForUser === 'function') haveUnique.forEach(function (id) { clearConvE2eWarningForUser(conversationId, id); });
        var toAdd = want.filter(function (id) { return id !== myUserId && haveUnique.indexOf(id) === -1; });
        // BUG thật đã gặp: dùng thẳng `have` (còn trùng lặp theo số leaf) làm removeUserIds khiến
        // e2eCommitRemove lặp lại e2eLeafIndexesOf cho CÙNG 1 người nhiều lần -> nhiều proposal remove
        // trỏ ĐÚNG 1 leaf -> M.createCommit ném ValidationError "multiple ... proposals ... same leaf",
        // sync không bao giờ thành công, mọi tin sau đó (kể cả tin mới) đứng yên "chưa tham gia nhóm"
        // /"không giải mã được" vì epoch không bao giờ khớp lại được. Phải khử trùng trước.
        var toRemove = haveUnique.filter(function (id) { return id !== myUserId && want.indexOf(id) === -1; });
        var chain = Promise.resolve(state);
        if (toRemove.length) chain = chain.then(function (s) { return e2eCommitRemove(conversationId, s, toRemove); });
        if (toAdd.length) chain = chain.then(function (s) { return e2eCommitAddMany(conversationId, s, toAdd); });
        return chain;
    });
}

function e2eCommitRemove(conversationId, state, removeUserIds) {
    var proposals = [];
    // Khử trùng phòng thủ -- gọi 2 lần e2eLeafIndexesOf cho CÙNG 1 người sẽ tạo 2 proposal remove
    // trỏ ĐÚNG 1 leaf, M.createCommit ném ValidationError (xem e2eSyncGroupMembers, nơi đã tự khử
    // trùng trước khi gọi vào đây -- giữ luôn ở đây cho chắc nếu sau này có chỗ gọi khác quên khử).
    removeUserIds = Array.from(new Set(removeUserIds));
    // Loại HẾT thiết bị (leaf) của người bị remove -- 1 người có thể có nhiều leaf (nhiều thiết bị,
    // xem javadoc e2eCredential), remove nửa vời (chỉ 1 leaf) sẽ để sót thiết bị khác của họ vẫn còn
    // đọc được tin nhóm sau khi "đã kick".
    removeUserIds.forEach(function (id) {
        e2eLeafIndexesOf(state, id).forEach(function (li) { proposals.push({ proposalType: 'remove', remove: { removed: li } }); });
    });
    if (!proposals.length) return Promise.resolve(state);
    var expectedEpoch = Number(state.groupContext.epoch);
    // Sau remove, "present" để gửi commit = member mới trừ mình trừ người bị remove.
    return M.createCommit({ state: state, cipherSuite: e2eImpl }, { extraProposals: proposals, ratchetTreeExtension: false }).then(function (res) {
        var newState = res.newState;
        (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
        // CAS -- xem javadoc chỗ tương tự ở e2eCreateCommitAndDistribute. THUA thì bỏ hẳn commit này,
        // không lưu/deliver, chờ commit của người thắng tới qua to-device để tự bắt kịp.
        return e2ePublishGroupInfo(conversationId, expectedEpoch, newState).then(function (cas) {
            if (!cas.won) {
                console.warn('[e2e-mls] thua CAS lúc remove-commit (epoch ' + expectedEpoch + ') -- bỏ commit, chờ commit của người thắng tới qua to-device');
                throw Object.assign(new Error('CAS lost khi remove-commit'), { e2eCasLost: true });
            }
            var commitWire = e2eB64(M.encodeMlsMessage(res.commit));
            var present = e2eMemberUserIdSet(newState).filter(function (id) { return id !== myUserId && removeUserIds.indexOf(id) === -1; });
            var deliveries = present.map(function (id) { return { recipientUserId: id, type: 'mls_commit', conversationId: conversationId, body: { commit: commitWire } }; });
            return e2eDeliverBatch(deliveries).then(function () { return e2eSaveGroup(conversationId, newState); }).then(function () { return newState; });
        });
    });
}

// ===== Dọn "leaf chết" (thiết bị đã logout/bị gỡ nhưng leaf vẫn còn nằm trong cây MLS) =====
// Logout (xem auth.js#logout) chỉ THU HỒI thiết bị + xoá state CỤC BỘ của CHÍNH thiết bị đó -- không
// hề tự Remove leaf của nó khỏi cây MLS ở phía các member KHÁC (MLS không có cơ chế "tự rút lui", chỉ
// người KHÁC mới Remove được 1 leaf). e2eSyncGroupMembers so khớp theo USERID (không theo leaf/thiết
// bị) nên KHÔNG tự nhận ra "leaf này đã chết" -- userId đó vẫn coi là "đã có mặt" qua đúng cái leaf cũ
// đã chết, khiến không ai chủ động add lại thiết bị MỚI của họ nữa (phải tự self-heal qua external-
// join, xem javadoc e2eCheckGroupRotations). Theo thời gian (logout/login nhiều lần) cây tích luỹ leaf
// chết, không tự dọn.
//
// KHÁC e2eCommitRemove (remove theo USERID -- xoá HẾT leaf của 1 người, dùng khi họ rời hẳn
// conversation): hàm này remove ĐÚNG TỪNG LEAF CHẾT theo index, GIỮ NGUYÊN leaf còn sống khác (nếu có)
// của cùng người đó -- 1 người có thể có 1 leaf chết (thiết bị cũ đã logout) VÀ 1 leaf sống (thiết bị
// đang dùng) cùng lúc.
function e2eCommitRemoveDeadLeaves(conversationId, state, leafIndexes) {
    if (!leafIndexes.length) return Promise.resolve(state);
    var proposals = leafIndexes.map(function (li) { return { proposalType: 'remove', remove: { removed: li } }; });
    var expectedEpoch = Number(state.groupContext.epoch);
    return M.createCommit({ state: state, cipherSuite: e2eImpl }, { extraProposals: proposals, ratchetTreeExtension: false }).then(function (res) {
        var newState = res.newState;
        (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
        return e2ePublishGroupInfo(conversationId, expectedEpoch, newState).then(function (cas) {
            if (!cas.won) {
                console.warn('[e2e-mls] thua CAS lúc dọn leaf chết (epoch ' + expectedEpoch + ') -- bỏ commit, thử lại lượt sau');
                throw Object.assign(new Error('CAS lost khi dọn leaf chết'), { e2eCasLost: true });
            }
            var commitWire = e2eB64(M.encodeMlsMessage(res.commit));
            // KHÔNG loại người có leaf vừa bị dọn khỏi "present" như e2eCommitRemove -- họ có thể vẫn
            // còn 1 leaf SỐNG khác (chỉ mất đúng leaf chết), vẫn cần nhận Commit để cây khớp lại.
            var present = e2eMemberUserIdSet(newState).filter(function (id) { return id !== myUserId; });
            var deliveries = present.map(function (id) { return { recipientUserId: id, type: 'mls_commit', conversationId: conversationId, body: { commit: commitWire } }; });
            return e2eDeliverBatch(deliveries).then(function () { return e2eSaveGroup(conversationId, newState); }).then(function () { return newState; });
        });
    });
}

// Chạy NỀN, không chặn gì (chỉ dọn dẹp, không quan trọng bằng add/remove theo membership) -- throttle
// theo conversationId để không gọi /mls/devices/revoked-check ở MỌI lượt refresh (e2eCheckGroupRotations
// chạy khá thường xuyên) -- chỉ cần dọn kiểu "thỉnh thoảng", không cần tức thời.
var E2E_PRUNE_DEAD_LEAVES_COOLDOWN_MS = 6 * 60 * 60 * 1000; // 6 giờ/conversation
var e2ePruneDeadLeavesLastRunAt = {}; // conversationId -> epoch millis lần dọn gần nhất
function e2ePruneDeadLeaves(conversationId, state) {
    if (!state) return Promise.resolve(state);
    var last = e2ePruneDeadLeavesLastRunAt[conversationId] || 0;
    if (Date.now() - last < E2E_PRUNE_DEAD_LEAVES_COOLDOWN_MS) return Promise.resolve(state);
    e2ePruneDeadLeavesLastRunAt[conversationId] = Date.now();
    // `seqIdx` (0,1,2... ĐẾM THEO THỨ TỰ TRÊN DANH SÁCH LEAF ĐàLỌC) -- KHÔNG phải `x.idx` từ
    // e2eLeafNodes (đó là vị trí RAW trong toàn bộ ratchetTree, xen kẽ cả parent-node, không phải
    // "LeafIndex" mà M.createCommit's `remove.removed` cần -- bug thật đã tự bắt được lúc test: dùng
    // nhầm raw idx ném thẳng "Tried to remove empty leaf node". Xem đúng cách làm ở e2eLeafIndexesOf.
    var leaves = e2eLeafNodes(state)
        .map(function (x, seqIdx) { return { idx: seqIdx, deviceId: e2eLeafDeviceId(x.n) }; })
        .filter(function (x) { return x.deviceId && x.deviceId !== e2eDeviceId; }); // bỏ qua leaf của CHÍNH thiết bị này (chắc chắn còn sống)
    if (!leaves.length) return Promise.resolve(state);
    return e2eCheckRevokedDeviceIds(leaves.map(function (x) { return x.deviceId; })).then(function (revokedIds) {
        if (!revokedIds.length) return state;
        var revokedSet = new Set(revokedIds);
        var deadLeafIndexes = leaves.filter(function (x) { return revokedSet.has(x.deviceId); }).map(function (x) { return x.idx; });
        if (!deadLeafIndexes.length) return state;
        console.warn('[e2e-mls] phát hiện', deadLeafIndexes.length, 'leaf chết (thiết bị đã bị thu hồi) trong', conversationId, '-- tự dọn');
        return e2eCommitRemoveDeadLeaves(conversationId, state, deadLeafIndexes);
    }).catch(function (err) {
        // Dọn dẹp best-effort -- lỗi (mạng, thua CAS...) không được phép làm hỏng luồng rotation chính,
        // thử lại ở lượt sau (cooldown đã ghi nhận ở trên, không retry dồn dập ngay).
        console.warn('[e2e-mls] dọn leaf chết lỗi (bỏ qua, thử lại lượt sau)', err && err.message);
        return state;
    });
}

// ADD HẾT thiết bị hiện có của mỗi người trong addUserIds (không chỉ 1 thiết bị đại diện) -- cùng lý
// do với e2eDrainAddCommits, dùng khi member list đổi lúc đồng bộ (không phải lúc tạo group lần đầu).
function e2eCommitAddMany(conversationId, state, addUserIds) {
    return Promise.all(addUserIds.map(function (id) { return e2eClaimAllDeviceKeyPackagesFor(id).then(function (bundles) { return { userId: id, bundles: bundles }; }); }))
        .then(function (results) {
            // Cùng lý do với e2eDrainAddCommits -- báo NGAY thay vì im lặng bỏ qua, xem javadoc ở đó.
            // showConvE2eWarning (banner nằm lại trong header) thay vì showToast (tự biến mất sau 2.2s).
            results.filter(function (r) { return !r.bundles.length; }).forEach(function (r) {
                console.warn('[e2e-mls] người này chưa bật MLS, không add được:', r.userId);
                if (typeof showConvE2eWarning === 'function') showConvE2eWarning(conversationId, r.userId, (typeof displayName === 'function' ? displayName(r.userId) : r.userId) + ' chưa sẵn sàng mã hoá -- tin nhắn sẽ KHÔNG đến được cho người này cho tới khi họ mở lại app');
            });
            var readyUserIds = results.filter(function (r) { return r.bundles.length; }).map(function (r) { return r.userId; });
            var adds = [];
            results.forEach(function (r) { r.bundles.forEach(function (b) { adds.push({ userId: r.userId, keyPackage: e2ePubToKeyPackage(b.keyPackage) }); }); });
            if (!adds.length) return state;
            return e2eCreateCommitAndDistribute(conversationId, state, adds, []).then(function (newState) {
                // Add thành công -- xoá cảnh báo cũ (nếu có) của ĐÚNG những người vừa add được ở lượt này.
                if (typeof clearConvE2eWarningForUser === 'function') readyUserIds.forEach(function (uid) { clearConvE2eWarningForUser(conversationId, uid); });
                return newState;
            });
        });
}

// ===== Encrypt outgoing (điểm vào từ sendChatMessage) =====
function e2eMaybeEncryptForSend(conversationId, plainBody) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv || !conv.e2eEnabled) return Promise.resolve(plainBody);
    if (!e2eReady) return Promise.reject(new Error('Mã hoá MLS chưa sẵn sàng, thử lại sau'));
    // e2eWithConvLock -- xem javadoc chỗ khai báo: gửi tin (đọc-sửa-ghi group state qua sync/commit +
    // encrypt) không được phép race với commit/tin đến CÙNG conversation (WS "E2E_TO_DEVICE"/"MESSAGE"),
    // và cũng không được race với chính nó nếu gửi liên tiếp nhanh (2 lượt sendChatMessage gần nhau).
    return e2eWithConvLock(conversationId, function () {
        return e2eSyncGroupMembers(conversationId, conv.memberUserIds).then(function (synced) {
            // synced === null nghĩa là ta chưa có group cục bộ (chưa từng nhận Welcome từ ai). TRƯỚC ĐÂY
            // chỉ THỤ ĐỘNG chờ (không tự tạo, tránh split-brain) -- bug thật đã gặp: nếu không có AI
            // KHÁC đang online để chủ động add mình, mình kẹt vĩnh viễn dù đã là thành viên hợp lệ
            // (conversation_members). Giờ TỰ THỬ External Commit (e2eTryExternalJoin, RFC 9420) trước --
            // không cần ai add hộ, không phụ thuộc ai online. CHỈ khi GroupInfo cũng chưa từng tồn tại
            // (group thật sự chưa được TẠO LẦN ĐẦU bởi creator -- xem e2eCheckGroupRotations) mới thật
            // sự phải chờ (không có gì để tự join vào).
            if (synced === null) return e2eTryExternalJoin(conversationId);
            return synced;
        }).then(function (state) {
            if (!state) throw new Error('đang chờ thiết lập nhóm mã hoá (chờ người khởi tạo nhóm)');
            var payload = JSON.stringify(plainBody);
            return M.createApplicationMessage(state, new TextEncoder().encode(payload), e2eImpl).then(function (res) {
                (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
                var wire = e2eB64(M.encodeMlsMessage({ privateMessage: res.privateMessage, wireformat: 'mls_private_message', version: 'mls10' }));
                var epoch = Number(state.groupContext.epoch);
                var newEpoch = Number(res.newState.groupContext.epoch); // application message không đổi epoch -> bằng epoch cũ
                return e2eSaveGroup(conversationId, res.newState).then(function () {
                    var env = { e2e: true, mls: true, epoch: epoch, ct: wire };
                    if (plainBody && plainBody.replyTo && plainBody.replyTo.fromUserId) env.replyTo = { fromUserId: plainBody.replyTo.fromUserId };
                    if (plainBody && plainBody.mentionedUserIds && plainBody.mentionedUserIds.length) env.mentionedUserIds = plainBody.mentionedUserIds;
                    return env;
                });
            });
        });
    });
}

// ===== Decrypt incoming =====
function e2eDecryptIncomingBody(fromUserId, body, messageId, conversationId) {
    var env = { mls: true };
    // Chưa có group cục bộ -- TRƯỚC KHI báo "chưa tham gia nhóm", thử TỰ join bằng External Commit
    // (xem e2eTryExternalJoin/e2eCheckGroupRotations): mình có thể đã LÀ thành viên hợp lệ của
    // conversation từ trước (chỉ chưa từng có Welcome tới do lúc add mình chưa online/chưa có
    // KeyPackage) nhưng CHỈ ĐỌC, không tự gõ gửi gì -- nếu không thử ở đây, tin MỚI tới ngay cả lúc
    // đang mở app cũng kẹt "chưa tham gia nhóm" mãi mãi (bug thật đã gặp, xem e2eCheckGroupRotations).
    // Tin CŨ (trước epoch mình join) vẫn không giải mã được -- forward secrecy cố ý, rơi vào nhánh
    // lỗi decrypt bình thường bên dưới, không phải lỗi mới.
    // e2eWithConvLock -- xem javadoc chỗ khai báo: WS "MESSAGE" (tin này) và "E2E_TO_DEVICE" (commit)
    // tới CÙNG conversation không hề chain với nhau ở history-ws.js's onmessage, race trên cùng
    // load->save group state gây tụt epoch nếu không khoá.
    return e2eWithConvLock(conversationId, function () {
        return e2eLoadGroup(conversationId).then(function (state) {
            if (state) return state;
            return e2eTryExternalJoin(conversationId);
        }).then(function (state) {
            if (!state) return { message: '🔒 Tin nhắn MLS (thiết bị này chưa tham gia nhóm)', e2eFailed: true };
            var msg = M.decodeMlsMessage(e2eUnb64(body.ct), 0)[0];
            if (!msg || msg.wireformat !== 'mls_private_message') return { message: '🔒 Tin MLS không hợp lệ', e2eFailed: true };
            return M.processPrivateMessage(state, msg.privateMessage, M.emptyPskIndex, e2eImpl).then(function (res) {
                (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
                // res.kind === 'applicationMessage' => có .message (bytes); hoặc newState-only
                if (res.kind === 'applicationMessage' && res.message) {
                    var plain = JSON.parse(new TextDecoder().decode(res.message));
                    return e2eSaveGroup(conversationId, res.newState).then(function () {
                        if (messageId) return e2eCachePlaintext(messageId, plain).then(function () { return plain; });
                        return plain;
                    });
                }
                return e2eSaveGroup(conversationId, res.newState).then(function () { return { message: '🔒 Không giải mã được (không phải tin ứng dụng)', e2eFailed: true }; });
            });
        });
    });
}

function e2eResolveIncomingBody(fromUserId, body, messageId, conversationId) {
    if (!body || !body.mls) return Promise.resolve(body); // tin thường hoặc tin Olm cũ (e2e true, không mls) — để caller render placeholder cũ
    return e2eGetCachedPlaintext(messageId).then(function (cached) {
        if (cached) return cached;
        if (!e2eReady) return { message: '🔒 Tin nhắn MLS (thiết bị chưa sẵn sàng)', e2eFailed: true };
        return e2eDecryptIncomingBody(fromUserId, body, messageId, conversationId).then(function (plain) {
            if (plain && plain.e2eFailed) {
                if (messageId) e2eFailedEnvelopesByMessageId[messageId] = { fromUserId: fromUserId, conversationId: conversationId, body: body };
                return plain;
            }
            if (messageId) delete e2eFailedEnvelopesByMessageId[messageId];
            return plain;
        }).catch(function (err) {
            console.warn('[e2e-mls] decrypt lỗi', err);
            if (messageId) e2eFailedEnvelopesByMessageId[messageId] = { fromUserId: fromUserId, conversationId: conversationId, body: body };
            return { message: '🔒 Không giải mã được tin nhắn MLS', e2eFailed: true };
        });
    });
}

function e2eResolveEditedBody(fromUserId, body, messageId, conversationId) {
    if (!body || !body.mls) return Promise.resolve(body);
    if (!e2eReady) return Promise.resolve({ message: '🔒 Tin nhắn MLS (thiết bị chưa sẵn sàng)', e2eFailed: true });
    return e2eDecryptIncomingBody(fromUserId, body, null, conversationId).then(function (plain) {
        if (plain && plain.e2eFailed) return plain;
        return e2eCachePlaintext(messageId, plain).then(function () { return plain; });
    });
}

function e2eResolveIncomingBatch(conversationId, messages) {
    // TUẦN TỰ (không Promise.all): mỗi private message advance secret-tree ratchet position của group
    // state; decrypt 2 tin cùng lúc trên CÙNG state sẽ trùng nonce/ratchet -> fail. Gửi theo thứ tự
    // created_at (mảng đã sort đúng khi load lịch sử) để mỗi tin thấy state mới nhất của tin trước.
    var chain = Promise.resolve([]);
    messages.forEach(function (m) {
        chain = chain.then(function (acc) {
            return e2eResolveIncomingBody(m.fromUserId, m.body, m.id, conversationId).then(function (b) { acc.push(Object.assign({}, m, { body: b })); return acc; });
        });
    });
    return chain;
}

function e2eRetryFailedMessagesIn(conversationId) {
    var ids = Object.keys(e2eFailedEnvelopesByMessageId).filter(function (id) { return e2eFailedEnvelopesByMessageId[id].conversationId === conversationId; });
    if (!ids.length) return;
    ids.forEach(function (messageId) {
        var entry = e2eFailedEnvelopesByMessageId[messageId];
        e2eResolveIncomingBody(entry.fromUserId, entry.body, messageId, entry.conversationId).then(function (resolved) {
            if (resolved && (!resolved.e2eFailed || resolved.e2eUnrecoverable)) handleMessageEdited(conversationId, messageId, resolved);
        });
    });
}

// ===== To-device router (MLS types) =====
// mls_welcome: body {welcome: b64(MLSMessage welcome)} — người được add thử join bằng TỪNG cặp
//   (pub,priv) trong queue local cho tới khi khớp keypackage mà creator đã claim (joinGroup throw
//   "No matching secret found" nếu sai -> thử cặp kế). Xem e2eJoinWelcomeTry.
// mls_commit: body {commit: b64} — processMessage trên group state hiện có, lưu newState, retry các
//   tin đang kẹt (e2eRetryFailedMessagesIn) vì commit có thể mang epoch mà tin đang chờ.
function e2eHandleToDeviceItem(type, senderUserId, conversationId, body) {
    if (type === 'mls_welcome') return e2eHandleWelcome(conversationId, body);
    if (type === 'mls_commit') return e2eHandleCommit(conversationId, body);
    if (type === 'mls_link_welcome') return e2eHandleLinkWelcome(senderUserId, body); // device-link group tạm
    if (type === 'mls_link_payload') return e2eHandleLinkPayload(senderUserId, body);
    if (type === 'mls_link_commit') return e2eHandleCommit(null, body); // (không dùng cho link, giữ an toàn)
    return Promise.resolve();
}

function e2eHandleWelcome(conversationId, body) {
    if (!body || !body.welcome || !conversationId) return Promise.resolve();
    var welcome = M.decodeMlsMessage(e2eUnb64(body.welcome), 0)[0].welcome;
    // Thử join bằng TỪNG cặp (pub,priv) trong queue local cho tới khi khớp keypackage mà creator
    // đã claim cho ta (joinGroup throw "Could not decode group secrets" nếu sai -> priv kế tiếp).
    return e2eJoinWelcomeTry(conversationId, welcome);
}

function e2eJoinWelcomeTry(conversationId, welcome) {
    // e2eWithConvLock -- xem javadoc chỗ khai báo: nếu 1 commit KHÁC (VD add tiếp người thứ 2) cho
    // CÙNG conversation tới ngay sau Welcome này (trước khi join+save kịp xong), e2eHandleCommit sẽ
    // thấy "chưa có group local" (join chưa lưu xong) rồi ÂM THẦM BỎ QUA commit đó (return null) --
    // khoá chung đảm bảo commit đó tự đợi welcome lưu xong trước, xử lý đúng thay vì mất trắng.
    return e2eWithConvLock(conversationId, function () {
        // e2eWithKpQueueLock lồng trong e2eWithConvLock -- KHÁC key (conversationId vs
        // E2E_KP_QUEUE_LOCK_KEY) nên không tự deadlock (xem javadoc e2eWithKpQueueLock) -- cần thiết vì
        // đoạn này vừa TÌM vừa XOÁ khỏi hàng đợi KP chung, phải khoá để không race với 1 conversation
        // khác đang top-up/tiêu key cùng lúc.
        return e2eWithKpQueueLock(function () {
            return e2eLoadKpQueue().then(function (queue) {
                var chain = Promise.reject(new Error('no matching keypackage'));
                queue.forEach(function (entry) {
                    chain = chain.catch(function () {
                        var pub = e2ePubToKeyPackage(entry.pubB64);
                        var priv = e2ePrivFromJson(entry.privJson);
                        return M.joinGroup(welcome, pub, priv, M.emptyPskIndex, e2eImpl).then(function (state) {
                            return { state: state, usedPubB64: entry.pubB64 };
                        });
                    });
                });
                return chain;
            }).then(function (joined) {
                // xoá KP đã dùng khỏi queue (đã được add vào group này)
                return e2eLoadKpQueue().then(function (queue) {
                    return e2eSaveKpQueue(queue.filter(function (q) { return q.pubB64 !== joined.usedPubB64; }));
                }).then(function () { return joined.state; });
            });
        }).then(function (state) {
            return e2eSaveGroup(conversationId, state).then(function () { e2eRetryFailedMessagesIn(conversationId); });
        });
    }).catch(function (err) { console.warn('[e2e-mls] không join được group (welcome hết hạn / KP đã dùng)', err && err.message); });
}

function e2eHandleCommit(conversationId, body) {
    if (!body || !body.commit || !conversationId) return Promise.resolve();
    var msg = M.decodeMlsMessage(e2eUnb64(body.commit), 0)[0];
    // e2eWithConvLock -- xem javadoc chỗ khai báo: tránh race với tin ứng dụng/commit khác tới CÙNG
    // conversation gần như đồng thời (2 case độc lập trong history-ws.js's onmessage).
    return e2eWithConvLock(conversationId, function () {
        return e2eLoadGroup(conversationId).then(function (state) {
            if (!state) return null; // chưa có group local -> welcome sẽ tới riêng (hoặc ta bị remove -> im lặng)
            var expectedEpoch = Number(state.groupContext.epoch);
            return M.processMessage(msg, state, M.emptyPskIndex, M.acceptAll, e2eImpl).then(function (res) {
                (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
                if (!res.newState) return null;
                // Nếu commit remove CHÍNH MÌnh (selfRemoved) -> xoá group local (không đọc được tin mới).
                if (res.newState.activeState && res.newState.activeState.kind === 'externalCommit') {} // noop
                return e2eSaveGroup(conversationId, res.newState)
                    // Epoch vừa đổi (commit từ người khác, ĐÃ được server chấp nhận -- CAS ở đây chỉ để
                    // đồng bộ GroupInfo public cho người cần External-Join, KHÔNG cần thắng/thua: state cục
                    // bộ của mình đã đúng dù publish này có thua ai khác public trước). Best-effort.
                    .then(function () { return e2ePublishGroupInfoBestEffort(conversationId, expectedEpoch, res.newState); })
                    .then(function () { e2eRetryFailedMessagesIn(conversationId); });
            });
        });
    }).catch(function (err) { console.warn('[e2e-mls] commit apply lỗi (epoch lệch? đã remove?)', err && err.message); });
}

function e2eDrainToDevice() {
    // Dù nhánh nào cũng phải "mở khoá" e2eWaitInitialDrain() -- không thì self external-join
    // (e2eTryExternalJoin) treo tới hết E2E_INITIAL_DRAIN_TIMEOUT_MS oan uổng.
    var unlockInitialDrain = function () { if (e2eInitialDrainResolve) { var r = e2eInitialDrainResolve; e2eInitialDrainResolve = null; r(); } };
    if (!e2eReady) { unlockInitialDrain(); return Promise.resolve(); }
    return fetch(HISTORY_API_BASE + '/e2e/to-device', { headers: { 'Authorization': 'Bearer ' + authToken } })
        .then(function (res) { return res.ok ? res.json() : []; })
        .then(function (items) {
            return items.reduce(function (chain, item) {
                // drain XÓA SẠCH hàng đợi — nếu 1 device trong nhiều device cùng user drain mất thì device
                // kia không còn welcome. Đây là giới hạn đã biết của hạ tầng to-device per-USER (không per-device)
                // — xem ghi chú ở dưới.
                return chain.then(function () { return e2eHandleToDeviceItem(item.type, item.senderUserId, item.conversationId, item.body); });
            }, Promise.resolve());
        })
        .catch(function (err) { console.warn('không drain được e2e to-device', err); })
        .then(unlockInitialDrain);
}

// ===== Liên kết thiết bị (mã 6 ký tự) — chuyển LỊCH SỬ ĐÃ GIẢI MÃ qua 1 NHÓM MLS TẠM =====
// Thiết bị mới xin mã 6 ký tự + tạo 1 KP TẠM (không publish lên directory). Thiết bị cũ gõ mã ->
// lấy KP tạm (chỉ có 1 cái, của thiết bị mới) -> tạo group tạm chỉ gồm 2 thiết bị -> add thiết bị
// mới (Welcome) -> khi thiết bị mới join, cũ gửi {msgPlaintext, groups} NHƯ 1 application message
// MLS (E2EE thật, server không đọc được) -> thiết bị mới import. Không dùng Olm Account tạm nữa.
var e2eLinkCode = null;
var e2eLinkKp = null;       // {pubB64, privJson} KP tạm của thiết bị mới
var e2eLinkWaiters = [];

function e2eRequestDeviceLink() {
    if (!M) return Promise.reject(new Error('MLS chưa sẵn sàng ở trình duyệt này'));
    return e2eEnsureImpl().then(function () {
        return M.generateKeyPackage(e2eCredential(), M.defaultCapabilities(), M.defaultLifetime, [], e2eImpl);
    }).then(function (r) {
        e2eLinkKp = {
            pubB64: e2eB64(M.encodeMlsMessage({ keyPackage: r.publicPackage, wireformat: 'mls_key_package', version: 'mls10' })),
            privJson: JSON.stringify({ init: Array.from(r.privatePackage.initPrivateKey), hpke: Array.from(r.privatePackage.hpkePrivateKey), sig: Array.from(r.privatePackage.signaturePrivateKey) })
        };
        // xin mã 6 ký tự, gửi pubKey KP tạm (identity_key = pubB64; one_time_key = random placeholder — server không parse)
        var oneTime = e2eB64(crypto.getRandomValues(new Uint8Array(32)));
        return fetch(HISTORY_API_BASE + '/e2e/device-link/request', {
            method: 'POST', headers: { 'Authorization': 'Bearer ' + authToken, 'Content-Type': 'application/json' },
            body: JSON.stringify({ identityKey: e2eLinkKp.pubB64, oneTimeKeyId: 'link', oneTimeKey: oneTime })
        });
    }).then(function (res) { if (!res.ok) throw new Error('không xin được mã liên kết'); return res.json(); })
      .then(function (d) { e2eLinkCode = d.code; return d; });
}
function e2eEnsureImpl() {
    if (e2eImpl) return Promise.resolve();
    e2eCs = M.getCiphersuiteFromName(MLS_CIPHERSUITE);
    return M.getCiphersuiteImpl(e2eCs).then(function (impl) { e2eImpl = impl; });
}
function e2eWaitForDeviceLink(timeoutMs) {
    return new Promise(function (resolve, reject) {
        var entry = { resolve: resolve };
        var timer = setTimeout(function () {
            var i = e2eLinkWaiters.indexOf(entry); if (i !== -1) e2eLinkWaiters.splice(i, 1);
            reject(new Error('Hết thời gian chờ liên kết, thử lấy mã mới'));
        }, timeoutMs || 300000);
        entry._timer = timer;
        e2eLinkWaiters.push(entry);
    });
}
function e2eCancelDeviceLinkWait() { e2eLinkCode = null; e2eLinkKp = null; e2eLinkWaiters = []; e2eLinkGroupStates = {}; }

// Thiết bị cũ approve: tạo group tạm + add KP tạm + gửi app message chứa history (E2EE MLS).
function e2eApproveDeviceLink(code) {
    if (!e2eReady) return Promise.reject(new Error('Thiết bị này chưa sẵn sàng, thử lại sau'));
    return fetch(HISTORY_API_BASE + '/e2e/device-link/bundle?code=' + encodeURIComponent(code.trim().toUpperCase()), { headers: { 'Authorization': 'Bearer ' + authToken } })
        .then(function (res) {
            if (res.status === 404) throw new Error('Mã không đúng hoặc đã hết hạn');
            if (!res.ok) throw new Error('Không lấy được thông tin thiết bị mới');
            return res.json();
        })
        .then(function (bundle) {
            // bundle.identityKey = pubB64 KP tạm của thiết bị mới
            var theirPub = e2ePubToKeyPackage(bundle.identityKey);
            var linkGroupId = new TextEncoder().encode('link:' + code + ':' + crypto.randomUUID());
            // e2eWithKpQueueLock -- xem javadoc chỗ khai báo: pop 1 KP khỏi hàng đợi chung để làm danh
            // tính CHÍNH MÌNH cho group tạm này, không được race với top-up/tiêu key ở 1 conversation
            // khác đang chạy song song.
            return e2eWithKpQueueLock(function () {
                return e2eLoadKpQueue().then(function (queue) {
                    var mine = queue[0] || null;
                    var gen = mine ? Promise.resolve(mine) : e2eGenerateKeyPackages(1).then(function (l) { return l[0]; });
                    return gen.then(function (me) {
                        var chain = mine ? e2eSaveKpQueue(queue.slice(1)) : Promise.resolve();
                        return chain.then(function () { return me; });
                    });
                });
            }).then(function (me) {
                var mePub = e2ePubToKeyPackage(me.pubB64), mePriv = e2ePrivFromJson(me.privJson);
                return M.createGroup(linkGroupId, mePub, mePriv, [], e2eImpl).then(function (s0) {
                    return M.createCommit({ state: s0, cipherSuite: e2eImpl }, { extraProposals: [{ proposalType: 'add', add: { keyPackage: theirPub } }], ratchetTreeExtension: true }).then(function (res) {
                        (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
                        var welcomeWire = e2eB64(M.encodeMlsMessage({ welcome: res.welcome, wireformat: 'mls_welcome', version: 'mls10' }));
                        // gói history
                        return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groups')]).then(function (dumps) {
                            var transfer = JSON.stringify({ msgPlaintext: dumps[0], groups: dumps[1].map(function (e) { return { key: e.key, stateB64: e.value.stateB64 }; }) });
                            return M.createApplicationMessage(res.newState, new TextEncoder().encode(transfer), e2eImpl).then(function (ar) {
                                var appWire = e2eB64(M.encodeMlsMessage({ privateMessage: ar.privateMessage, wireformat: 'mls_private_message', version: 'mls10' }));
                                // attemptId RIÊNG cho mỗi lần approve (kể cả approve 2 lần cùng 1 mã, hoặc 2
                                // relay sống + drain trùng nhau) -- bên nhận khớp welcome<->payload bằng
                                // đúng attemptId thay vì 1 biến global dùng chung, tránh 1 welcome khác group
                                // ghi đè state đang chờ payload của welcome trước đó (AEAD OperationError do
                                // decrypt payload group A bằng key group B -- lỗi thật đã gặp).
                                var attemptId = crypto.randomUUID();
                                // 2 message tới thiết bị mới: trước welcome, sau payload — thiết bị mới join
                                // group bằng welcome rồi decrypt payload. Gửi cùng lúc (server giữ thứ tự array?
                                // POST /e2e/to-device loop theo thứ tự -> queue FIFO -> drain theo created_at ASC).
                                return e2eDeliverBatch([
                                    { recipientUserId: myUserId, type: 'mls_link_welcome', body: { code: code.toUpperCase(), attemptId: attemptId, welcome: welcomeWire } },
                                    { recipientUserId: myUserId, type: 'mls_link_payload', body: { code: code.toUpperCase(), attemptId: attemptId, ct: appWire } }
                                ]);
                            });
                        });
                    });
                });
            });
        });
}

// Device mới: nhận welcome group link -> join -> giữ state tạm. Nhận payload -> decrypt -> import -> resolve.
// State giữ theo TỪNG attemptId (không phải 1 biến global dùng chung) -- to-device được phát cả qua
// relay sống (WS, tới MỌI tab đang mở) LẪN lưu hàng đợi DB (chỉ xoá khi GET /e2e/to-device drain), nên
// nếu 1 mã liên kết bị duyệt 2 lần (bấm 2 lần, hoặc relay sống trùng lúc drain) sẽ có 2 welcome của 2
// group KHÁC NHAU tới gần như cùng lúc -- welcome sau ghi đè state của welcome trước nếu dùng chung 1
// biến, khiến payload trước bị giải mã nhầm key của group sau -> AEAD OperationError (lỗi thật đã gặp).
// Khớp đúng welcome<->payload bằng attemptId server không đọc/không đổi được (sinh ở e2eApproveDeviceLink).
var e2eLinkGroupStates = {}; // attemptId -> state group link đang chờ payload
function e2eHandleLinkWelcome(senderUserId, body) {
    if (!body || !body.welcome || !body.attemptId || !e2eLinkKp) return Promise.resolve();
    if (e2eLinkCode && body.code && body.code !== e2eLinkCode) return Promise.resolve();
    var welcome = M.decodeMlsMessage(e2eUnb64(body.welcome), 0)[0].welcome;
    var pub = e2ePubToKeyPackage(e2eLinkKp.pubB64), priv = e2ePrivFromJson(e2eLinkKp.privJson);
    return M.joinGroup(welcome, pub, priv, M.emptyPskIndex, e2eImpl).then(function (s) { e2eLinkGroupStates[body.attemptId] = s; })
        .catch(function (err) { console.warn('[e2e-mls] link welcome fail', err); });
}
function e2eHandleLinkPayload(senderUserId, body) {
    if (!body || !body.ct || !body.attemptId || !e2eLinkGroupStates[body.attemptId]) return Promise.resolve();
    if (e2eLinkCode && body.code && body.code !== e2eLinkCode) return Promise.resolve();
    var state = e2eLinkGroupStates[body.attemptId];
    delete e2eLinkGroupStates[body.attemptId]; // dùng 1 lần -- attempt khác (nếu có) không bị đụng vào
    var msg = M.decodeMlsMessage(e2eUnb64(body.ct), 0)[0];
    return M.processPrivateMessage(state, msg.privateMessage, M.emptyPskIndex, e2eImpl).then(function (res) {
        if (!res || !res.message) return;
        var transfer = JSON.parse(new TextDecoder().decode(res.message));
        return e2eImportTransfer(transfer).then(function () {
            var waiters = e2eLinkWaiters; e2eLinkWaiters = [];
            waiters.forEach(function (w) { clearTimeout(w._timer); w.resolve(transfer); });
            // KHÔNG reset e2eLinkGroupStates = {} ở đây -- bug thật đã tự bắt được lúc viết test: nếu có
            // 2 attempt cùng chờ song song (VD bấm duyệt 2 lần), reset cả object sẽ xoá luôn state CỦA
            // ATTEMPT KHÁC vẫn đang treo chờ payload của nó, khiến payload đó sau này bail âm thầm
            // (không lỗi, không log) dù welcome của nó đã join thành công. Entry của CHÍNH attempt này
            // đã tự xoá riêng ở trên (delete e2eLinkGroupStates[body.attemptId]) -- đủ dọn phần của mình.
            e2eLinkCode = null; e2eLinkKp = null;
        });
    }).catch(function (err) { console.warn('[e2e-mls] link payload fail', err); });
}
function e2eImportTransfer(transfer) {
    // msgPlaintext entries (key myUserId|msgId -> {body}) nhập thẳng; group entries: chỉ import group CHƯA có
    // local (không đè group hiện có — group local có thể đã advance epoch cao hơn file export).
    var mp = (transfer.msgPlaintext || []).filter(function (e) { return String(e.key).indexOf(myUserId + '|') === 0; });
    var gs = (transfer.groups || []).filter(function (e) { return String(e.key).indexOf(myUserId + '|') === 0; });
    // Bug thật đã gặp: `e.value.stateB64 || e.stateB64` TỰ THROW ngay (TypeError: Cannot read
    // properties of undefined) nếu `e.value` là undefined -- KHÔNG "rơi" được xuống `e.stateB64` như
    // tưởng, vì phải ĐỌC ĐƯỢC `.stateB64` trên `e.value` trước thì `||` mới có gì để so sánh. Cả 2
    // nơi gửi (e2eApproveDeviceLink dòng ~974, e2eCreateBackup dòng ~1105) đều gửi dạng PHẲNG `{key,
    // stateB64}` (không có `.value`) -- nên `e.value` LUÔN undefined trên đường nhận này, crash ngay
    // khi transfer có ÍT NHẤT 1 group (test trước đó dùng tài khoản rỗng, không group nào nên không
    // lộ ra). Phải kiểm tra `e.value` tồn tại trước khi đọc `.stateB64` trên nó.
    var gEntries = gs.map(function (e) { return { key: e.key, value: { stateB64: (e.value && e.value.stateB64) || e.stateB64 } }; });
    return Promise.all([e2eDbPutAll('msgPlaintext', mp), (function () {
        if (!gEntries.length) return Promise.resolve();
        // merge: giữ cái đã có, chỉ thêm cái thiếu
        return e2eDbGetAllForUser('groups').then(function (existing) {
            var have = {}; existing.forEach(function (e) { have[e.key] = true; });
            var toAdd = gEntries.filter(function (e) { return !have[e.key]; });
            return e2eDbPutAll('groups', toAdd);
        });
    })()]).then(function () {});
}

// Ai được phép chủ động tạo group MLS cho conv này -- ƯU TIÊN e2eEnabledBy (chính người đã BẤM NÚT
// bật mã hoá, do server ghi lại ở setEncrypted -- chắc chắn có ít nhất 1 phiên online đúng lúc đó).
// Fallback về min(userId) CHỈ cho conversation đã bật E2E TRƯỚC KHI server có cột e2e_enabled_by
// (e2eEnabledBy null) -- giữ tương thích ngược, không phá các conversation cũ. Trước đây LUÔN dùng
// min(userId) bất kể ai bật -- bug thật đã gặp: nếu đúng người có userId nhỏ nhất trong nhóm CHƯA
// TỪNG mở app (không có phiên nào từng chạy qua đây để proactive-create), KHÔNG AI tạo được group,
// mọi member khác kẹt vĩnh viễn ở "đang chờ thiết lập nhóm mã hoá (chờ người khởi tạo nhóm)" dù đã
// bật E2E và đang online đầy đủ.
function e2eGroupCreatorUserId(conv) {
    if (conv.e2eEnabledBy) return conv.e2eEnabledBy;
    var sorted = (conv.memberUserIds || []).slice().sort();
    return sorted.length ? sorted[0] : null;
}

// ===== Check rotation: member list ĐỔI (kick/rời/vừa login lần đầu) =====
// 2 việc, cho MỌI conv đã bật E2E:
//   1) Chưa có group local -> CHỈ creator (xem e2eGroupCreatorUserId) mới được TẠO MỚI, tránh
//      split-brain (2 người cùng tạo 2 group riêng -> decrypt chéo -> CryptoError OperationError,
//      bug thật đã gặp). Người không phải creator thì chờ welcome (e2eHandleWelcome -> e2eJoinWelcomeTry).
//   2) ĐÃ có group local -> CHỦ ĐỘNG đồng bộ lại member NGAY (add người mới/remove người rời), không
//      chỉ creator mà BẤT KỲ ai đang giữ group đều làm được -- e2eSyncGroupMembers vốn đã được gọi
//      mỗi lúc GỬI TIN (xem e2eMaybeEncryptForSend), đây chỉ thêm 1 điểm kích hoạt SỚM HƠN (mỗi lần
//      refresh, không cần đợi ai gửi tin), không đổi logic add/remove nên KHÔNG tăng thêm rủi ro
//      split-brain nào mới. Bug thật đã gặp: A được thêm vào conversation (DB) từ trước, nhưng lúc
//      đó A CHƯA TỪNG mở app (chưa có KeyPackage) nên bị add-group bỏ qua; A login xong (giờ có key)
//      vẫn không đọc/gửi được gì cho tới khi TÌNH CỜ có ai gửi 1 tin mới -- không ai chủ động add lại
//      A cả, dù A đã publish key sẵn sàng từ lâu. (KHÔNG áp dụng cho lịch sử tin nhắn TRƯỚC lúc A
//      join -- đó là forward secrecy cố ý của E2E, không phải bug, không đọc lại được.)
function e2eCheckGroupRotations(oldList, newList) {
    if (!e2eReady) return;
    newList.forEach(function (conv) {
        if (!conv.e2eEnabled) return;
        // e2eWithConvLock -- xem javadoc chỗ khai báo: refreshConversationList() có thể được gọi lặp
        // (SUBSCRIBE_OK, CONVERSATION_ADDED, ...) gần nhau, mỗi lượt tự đọc-sửa-ghi group của CÙNG
        // conversation -- và lượt này cũng không được đụng độ với commit/tin đến/gửi tin cho conv đó.
        e2eWithConvLock(conv.conversationId, function () {
            return e2eLoadGroup(conv.conversationId).then(function (state) {
                if (state) {
                    return e2eSyncGroupMembers(conv.conversationId, conv.memberUserIds).then(function (syncedState) {
                        // Dọn leaf chết (thiết bị đã logout/bị gỡ, xem javadoc e2ePruneDeadLeaves) SAU
                        // khi sync add/remove theo membership đã xong -- best-effort, có cooldown riêng,
                        // không chặn/không làm hỏng luồng rotation chính nếu lỗi.
                        return e2ePruneDeadLeaves(conv.conversationId, syncedState || state);
                    });
                }
                if (e2eGroupCreatorUserId(conv) === myUserId) {
                    // Creator thấy e2eEnabled nhưng chưa có group -> tạo ngay + welcome mọi member.
                    // "Creator" xét theo USERID, không theo THIẾT BỊ -- nếu chính creator đăng nhập
                    // THÊM 1 thiết bị mới (group state lưu riêng theo từng IndexedDB/thiết bị), thiết
                    // bị mới đó CŨNG rơi vào nhánh này dù nhóm THẬT đã tồn tại (do thiết bị đầu tạo).
                    // M.createGroup tạo state cục bộ epoch=0 MỚI rồi e2eSaveGroup LƯU NGAY (trước cả
                    // khi add-commit đầu tiên kịp CAS) -- bug thật đã tự bắt được lúc viết test: add-
                    // commit đó CHẮC CHẮN thua CAS (nhóm thật đã ở epoch > 0), nhưng state epoch=0 "mồ
                    // côi" đã lỡ lưu rồi -- lần refresh SAU thấy `e2eLoadGroup` trả về CÓ state (dù sai)
                    // nên đi nhánh "state đã có -> e2eSyncGroupMembers" thay vì thử lại, KẸT VĨNH VIỄN,
                    // không đọc/gửi được tin của conversation này trên thiết bị mới. Bắt riêng lỗi
                    // e2eCasLost (ném ở e2eCreateCommitAndDistribute) để DỌN state mồ côi rồi tự
                    // external-join lại đúng epoch thật -- lỗi KHÁC (VD claim keypackage lỗi mạng) vẫn
                    // ném ra như cũ, không đụng vào.
                    return e2eCreateGroupWithMembers(conv.conversationId, conv.memberUserIds).catch(function (err) {
                        if (!err || !err.e2eCasLost) throw err;
                        console.warn('[e2e-mls] proactive-create thua CAS (nhóm đã tồn tại từ thiết bị/nơi khác) -- dọn state mồ côi, tự external-join lại');
                        return e2eDeleteGroup(conv.conversationId).then(function () { return e2eTryExternalJoin(conv.conversationId); });
                    });
                }
                // Không phải creator, chưa có group cục bộ -- TRƯỚC ĐÂY chỉ thụ động "chờ welcome" (đúng
                // cho lúc group vừa tạo, welcome đang trên đường tới), nhưng nếu mình được add vào
                // conversation SAU KHI group MLS đã tồn tại một thời gian (chưa từng online lúc đó nên bị
                // add-group bỏ qua, xem e2eDrainAddCommits) thì sẽ KHÔNG BAO GIỜ có Welcome nào gửi tới nữa
                // -- kẹt vĩnh viễn ở "chưa tham gia nhóm" cho TẤT CẢ tin, kể cả tin MỚI tới trong lúc đang
                // online (bug thật đã gặp: chỉ lúc TỰ GỬI tin mới kích hoạt External Commit trước đây, xem
                // e2eMaybeEncryptForSend -- ai chỉ ĐỌC, không gửi, không bao giờ tự join). Tự thử External
                // Commit ngay ở đây (RFC 9420, xem e2eTryExternalJoin) -- vô hại nếu gọi thừa: trả null êm
                // nếu GroupInfo chưa từng tồn tại (group thật sự chưa tạo lần đầu, đành chờ welcome thật),
                // và khoá in-flight theo conversationId đã chặn tự đụng độ nhiều lần join song song.
                return e2eTryExternalJoin(conv.conversationId).then(function (state2) {
                    if (state2) e2eRetryFailedMessagesIn(conv.conversationId);
                });
            });
        }).catch(function (err) { console.warn('[e2e-mls] proactive create/sync group lỗi', err); });
    });
}

// ===== Backup lạnh (xuất/nhập file AES-GCM, không qua server) — giữ nguyên pattern file cũ =====
// Payload = {msgPlaintext: entries[], groups: [{key, stateB64}]}. Import: msgPlaintext đè, groups giữ-cái-có.
var E2E_BACKUP_PBKDF2_ITERATIONS = 210000;
function e2eBytesToHex(b) { return Array.from(b).map(function (x) { return x.toString(16).padStart(2, '0'); }).join(''); }
function e2eHexToBytes(h) { h = h.replace(/[^0-9a-fA-F]/g, ''); var out = new Uint8Array(Math.floor(h.length / 2)); for (var i = 0; i < out.length; i++) out[i] = parseInt(h.substr(i * 2, 2), 16); return out; }
function e2eAbToBase64(buf) { return btoa(String.fromCharCode.apply(null, new Uint8Array(buf))); }
function e2eBase64ToAb(b64) { var bin = atob(b64); var bytes = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i); return bytes.buffer; }
function e2eDeriveKeyFromPhrase(phrase, salt) {
    return crypto.subtle.importKey('raw', new TextEncoder().encode(phrase), 'PBKDF2', false, ['deriveKey']).then(function (k) {
        return crypto.subtle.deriveKey({ name: 'PBKDF2', salt: salt, iterations: E2E_BACKUP_PBKDF2_ITERATIONS, hash: 'SHA-256' }, k, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    });
}
function e2eImportRawAesKey(k) { return crypto.subtle.importKey('raw', k, 'AES-GCM', false, ['encrypt', 'decrypt']); }
function e2eCreateBackup(mode, phrase) {
    return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groups')]).then(function (d) {
        var payload = JSON.stringify({ msgPlaintext: d[0], groups: d[1].map(function (e) { return { key: e.key, stateB64: e.value.stateB64 }; }) });
        var iv = crypto.getRandomValues(new Uint8Array(12));
        var kp, recoveryKeyHex = null, salt = null;
        if (mode === 'key') { var kb = crypto.getRandomValues(new Uint8Array(32)); recoveryKeyHex = e2eBytesToHex(kb); kp = e2eImportRawAesKey(kb); }
        else { salt = crypto.getRandomValues(new Uint8Array(16)); kp = e2eDeriveKeyFromPhrase(phrase, salt); }
        return kp.then(function (key) { return crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, new TextEncoder().encode(payload)); })
            .then(function (ct) { var file = { v: 2, mode: mode, iv: e2eAbToBase64(iv), ciphertext: e2eAbToBase64(ct) }; if (salt) file.salt = e2eAbToBase64(salt); return { fileContent: JSON.stringify(file), recoveryKeyHex: recoveryKeyHex }; });
    });
}
function e2eRestoreBackup(fileContent, secret) {
    var file; try { file = JSON.parse(fileContent); } catch (e) { return Promise.reject(new Error('File backup không hợp lệ')); }
    if (!file || !file.v || !file.ciphertext || !file.iv) return Promise.reject(new Error('File backup không hợp lệ'));
    if (!M) return Promise.reject(new Error('MLS chưa sẵn sàng để import group state'));
    var iv = new Uint8Array(e2eBase64ToAb(file.iv));
    var kp = file.mode === 'phrase' ? e2eDeriveKeyFromPhrase(secret, new Uint8Array(e2eBase64ToAb(file.salt))) : e2eImportRawAesKey(e2eHexToBytes(secret));
    return kp.then(function (key) { return crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv }, key, e2eBase64ToAb(file.ciphertext)); })
        .then(function (plainBuf) { var t = JSON.parse(new TextDecoder().decode(plainBuf)); return e2eImportTransfer(t); })
        .catch(function (err) { console.warn('e2e restore backup lỗi', err); throw new Error('Sai khoá/mật khẩu hoặc file backup bị hỏng'); });
}
