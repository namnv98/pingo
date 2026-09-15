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
        req.onsuccess = function () { e2eDb = req.result; resolve(e2eDb); };
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
function e2eCredential() { return { credentialType: 'basic', identity: new TextEncoder().encode(MLS_CRED_PREFIX + myUserId) }; }
function e2eGroupId(conversationId) { return new TextEncoder().encode(MLS_PSEUDO_GROUPID_PREFIX + conversationId); }
function e2eLeafIdentity(node) { try { return new TextDecoder().decode(node.leaf.credential.identity); } catch (e) { return ''; } }
function e2eLeafUserId(node) { var id = e2eLeafIdentity(node); return id.indexOf(MLS_CRED_PREFIX) === 0 ? id.slice(MLS_CRED_PREFIX.length) : null; }
// LeafIndex thật của node leaf ở vị trí k = 2k ( RFC tree: leaves ở node index chẵn). ts-mls dùng LeafIndex
// = vị trí lá ĐẾM THEO THỨ TỰ (0,1,2...), xem spike: remove dùng leafIdx đếm trên leaves-filtered đúng.
function e2eLeafNodes(state) { return (state.ratchetTree || []).map(function (n, idx) { return { n: n, idx: idx }; }).filter(function (x) { return x.n && x.n.nodeType === 'leaf'; }); }
function e2eLeafIndexOf(state, targetUserId) {
    var leaves = e2eLeafNodes(state);
    for (var i = 0; i < leaves.length; i++) { if (e2eLeafUserId(leaves[i].n) === targetUserId) return i; }
    return -1;
}
function e2eMemberUserIds(state) { return e2eLeafNodes(state).map(function (x) { return e2eLeafUserId(x.n); }).filter(function (id) { return id; }); }

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

// Top-up: nếu queue local (chưa dùng) còn ít hơn ngưỡng, sinh thêm + publish. Queue local giữ CẢ private,
// server chỉ giữ public để người khác claim. 1 keypackage chỉ dùng 1 lần (claim = xoá server-side).
function e2eMaybeTopUpKeyPackages() {
    return e2eLoadKpQueue().then(function (queue) {
        if (queue.length >= MLS_KEYPACKAGE_LOW_WATERMARK) return null;
        return e2eGenerateKeyPackages(MLS_KEYPACKAGE_TOP_UP).then(function (fresh) {
            e2eSaveKpQueue(queue.concat(fresh));
            return e2ePublishKeyPackages(fresh.map(function (f) { return f.pubB64; }));
        });
    });
}

// Lấy KeyPackage CÔNG KHAI của user đích để add vào group (server claim + xoá dùng 1 lần). Không cần
// private của họ (đương nhiên) — chỉ cần pub để nhét vào Add proposal.
function e2eClaimKeyPackagesFor(userId, limit) {
    return fetch(HISTORY_API_BASE + '/mls/key-packages?userId=' + encodeURIComponent(userId) + '&limit=' + encodeURIComponent(limit), {
        headers: { 'Authorization': 'Bearer ' + authToken }
    }).then(function (res) { if (!res.ok) throw new Error('claim keypackages HTTP ' + res.status); return res.json(); })
        .then(function (data) { return data.keyPackages || []; });
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

// ===== plaintext cache (vì MLS private message chỉ giải được 1 lần theo ratchet position) =====
function e2eCachePlaintext(messageId, plainBody) { return e2eDbPut('msgPlaintext', myUserId + '|' + messageId, { body: plainBody }); }
function e2eGetCachedPlaintext(messageId) { return e2eDbGet('msgPlaintext', myUserId + '|' + messageId).then(function (r) { return r ? r.body : null; }); }

// ===== Init =====
// Điểm vào DUY NHẤT từ enterApp(). Sinh keypackage lô đầu nếu chưa có, publish, bật cờ ready.
function e2eInit() {
    if (e2eInitializedForUserId !== myUserId) { e2eReady = false; e2eReadyPromise = null; e2eImpl = null; e2eCs = null; e2eDeviceId = null; }
    if (e2eReadyPromise) return e2eReadyPromise;
    if (e2eReady) return Promise.resolve();
    if (!M) return Promise.resolve(); // vendor/mls.js chưa load được -> E2E tự tắt, app vẫn chạy
    e2eInitializedForUserId = myUserId;
    e2eDeviceId = e2eGetOrCreateDeviceId();
    e2eReadyPromise = Promise.resolve()
        .then(function () { e2eCs = M.getCiphersuiteFromName(MLS_CIPHERSUITE); return M.getCiphersuiteImpl(e2eCs); })
        .then(function (impl) { e2eImpl = impl; })
        .then(function () { return e2eLoadKpQueue(); })
        .then(function (queue) { return queue.length ? null : e2eMaybeTopUpKeyPackages(); })
        .then(function () { e2eReady = true; return e2eMaybeTopUpKeyPackages(); })
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
    return e2eLoadKpQueue().then(function (queue) {
        if (!queue.length) return e2eMaybeTopUpKeyPackages().then(function () { return e2eLoadKpQueue(); }).then(function (q) { queue = q; if (!queue.length) throw new Error('không sinh được keypackage'); });
        var head = queue[0];
        var minePub = e2ePubToKeyPackage(head.pubB64);
        var minePriv = e2ePrivFromJson(head.privJson);
        return e2eSaveKpQueue(queue.slice(1))
            .then(function () { return M.createGroup(e2eGroupId(conversationId), minePub, minePriv, [], e2eImpl); })
            .then(function (state) { return e2eSaveGroup(conversationId, state).then(function () { return { state: state, pendingAdds: others }; }); })
            .then(function (st) { return e2eDrainAddCommits(conversationId, st); });
    });
}

// Vòng Add tuần tự: add người 1 -> tạo commit (ratchetTreeExtension=true) -> gửi Welcome cho người đó
// -> gửi Commit (PublicMessage) cho MỌI thành viên ĐÃ có trong group (trừ người vừa add) -> save state.
function e2eDrainAddCommits(conversationId, st) {
    if (!st.pendingAdds.length) return Promise.resolve(st.state);
    var next = st.pendingAdds[0];
    var rest = st.pendingAdds.slice(1);
    // Bỏ qua người không còn là member? (chấp nhận add hết memberUserIds tại thời điểm gọi).
    return e2eClaimKeyPackagesFor(next, 1).then(function (bundles) {
        if (!bundles.length) { console.warn('[e2e-mls] người này chưa bật MLS, không add được:', next); return e2eDrainAddCommits(conversationId, { state: st.state, pendingAdds: rest }); }
        var theirPub = e2ePubToKeyPackage(bundles[0].keyPackage);
        return e2eCreateCommitAndDistribute(conversationId, st.state, [{ userId: next, keyPackage: theirPub }], rest).then(function (newState) {
            return e2eDrainAddCommits(conversationId, { state: newState, pendingAdds: rest });
        });
    });
}

// 1 Add-commit chứa 1 hay nhiều Add proposal, broadcast Welcome/Commit tới đúng đối tượng.
// Trả về newState ĐÃ LƯU. consumed keys được zero-out.
function e2eCreateCommitAndDistribute(conversationId, state, adds, pendingAddsForWelcome) {
    var addProposals = adds.map(function (a) { return { proposalType: 'add', add: { keyPackage: a.keyPackage } }; });
    return M.createCommit({ state: state, cipherSuite: e2eImpl }, { extraProposals: addProposals, ratchetTreeExtension: true }).then(function (res) {
        var newState = res.newState;
        (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
        var commitWire = M.encodeMlsMessage(res.commit); // commit dạng PublicMessage (wireAsPublicMessage mặc định? tạo ra commit = MLSMessage) — relay cho member hiện tại
        var welcomeWire = res.welcome ? M.encodeMlsMessage({ welcome: res.welcome, wireformat: 'mls_welcome', version: 'mls10' }) : null;
        var deliveries = [];
        // Welcome riêng cho từng người được add
        if (welcomeWire) {
            adds.forEach(function (a) {
                deliveries.push({ recipientUserId: a.userId, type: 'mls_welcome', conversationId: conversationId, body: { welcome: e2eB64(welcomeWire) } });
            });
        }
        // Commit cho các thành viên HIỆN CÓ (trừ người vừa được add — họ không có group state trước commit này).
        var present = e2eMemberUserIds(newState).filter(function (id) { return id !== myUserId; }).filter(function (id) {
            return !adds.some(function (a) { return a.userId === id; });
        });
        present.forEach(function (id) {
            deliveries.push({ recipientUserId: id, type: 'mls_commit', conversationId: conversationId, body: { commit: e2eB64(commitWire) } });
        });
        return e2eDeliverBatch(deliveries).then(function () { return e2eSaveGroup(conversationId, newState); }).then(function () { return newState; });
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
        var have = e2eMemberUserIds(state);
        var toAdd = want.filter(function (id) { return id !== myUserId && have.indexOf(id) === -1; });
        var toRemove = have.filter(function (id) { return id !== myUserId && want.indexOf(id) === -1; });
        var chain = Promise.resolve(state);
        if (toRemove.length) chain = chain.then(function (s) { return e2eCommitRemove(conversationId, s, toRemove); });
        if (toAdd.length) chain = chain.then(function (s) { return e2eCommitAddMany(conversationId, s, toAdd); });
        return chain;
    });
}

function e2eCommitRemove(conversationId, state, removeUserIds) {
    var proposals = [];
    removeUserIds.forEach(function (id) {
        var li = e2eLeafIndexOf(state, id);
        if (li >= 0) proposals.push({ proposalType: 'remove', remove: { removed: li } });
    });
    if (!proposals.length) return Promise.resolve(state);
    // Sau remove, "present" để gửi commit = member mới trừ mình trừ người bị remove.
    return M.createCommit({ state: state, cipherSuite: e2eImpl }, { extraProposals: proposals, ratchetTreeExtension: false }).then(function (res) {
        var newState = res.newState;
        (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
        var commitWire = e2eB64(M.encodeMlsMessage(res.commit));
        var present = e2eMemberUserIds(newState).filter(function (id) { return id !== myUserId && removeUserIds.indexOf(id) === -1; });
        var deliveries = present.map(function (id) { return { recipientUserId: id, type: 'mls_commit', conversationId: conversationId, body: { commit: commitWire } }; });
        return e2eDeliverBatch(deliveries).then(function () { return e2eSaveGroup(conversationId, newState); }).then(function () { return newState; });
    });
}

function e2eCommitAddMany(conversationId, state, addUserIds) {
    return Promise.all(addUserIds.map(function (id) { return e2eClaimKeyPackagesFor(id, 1).then(function (b) { return b.length ? { userId: id, keyPackage: e2ePubToKeyPackage(b[0].keyPackage) } : null; }); }))
        .then(function (adds) {
            adds = adds.filter(Boolean);
            if (!adds.length) return state;
            return e2eCreateCommitAndDistribute(conversationId, state, adds, []);
        });
}

// ===== Encrypt outgoing (điểm vào từ sendChatMessage) =====
function e2eMaybeEncryptForSend(conversationId, plainBody) {
    var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
    if (!conv || !conv.e2eEnabled) return Promise.resolve(plainBody);
    if (!e2eReady) return Promise.reject(new Error('Mã hoá MLS chưa sẵn sàng, thử lại sau'));
    return e2eSyncGroupMembers(conversationId, conv.memberUserIds).then(function (synced) {
        // synced === null nghĩa là ta KHÔNG phải creator và chưa nhận welcome -> chưa có group.
        // KHÔNG tự tạo (tránh split-brain). Báo lỗi mềm để caller hiện "đang chờ thiết lập".
        if (synced === null) return e2eLoadGroup(conversationId);
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
}

// ===== Decrypt incoming =====
function e2eDecryptIncomingBody(fromUserId, body, messageId, conversationId) {
    var env = { mls: true };
    return e2eLoadGroup(conversationId).then(function (state) {
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
        }).then(function () { return e2eSaveGroup(conversationId, joined.state); })
          .then(function () { e2eRetryFailedMessagesIn(conversationId); });
    }).catch(function (err) { console.warn('[e2e-mls] không join được group (welcome hết hạn / KP đã dùng)', err && err.message); });
}

function e2eHandleCommit(conversationId, body) {
    if (!body || !body.commit || !conversationId) return Promise.resolve();
    var msg = M.decodeMlsMessage(e2eUnb64(body.commit), 0)[0];
    return e2eLoadGroup(conversationId).then(function (state) {
        if (!state) return null; // chưa có group local -> welcome sẽ tới riêng (hoặc ta bị remove -> im lặng)
        return M.processMessage(msg, state, M.emptyPskIndex, M.acceptAll, e2eImpl).then(function (res) {
            (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
            if (!res.newState) return null;
            // Nếu commit remove CHÍNH MÌnh (selfRemoved) -> xoá group local (không đọc được tin mới).
            if (res.newState.activeState && res.newState.activeState.kind === 'externalCommit') {} // noop
            return e2eSaveGroup(conversationId, res.newState).then(function () { e2eRetryFailedMessagesIn(conversationId); });
        });
    }).catch(function (err) { console.warn('[e2e-mls] commit apply lỗi (epoch lệch? đã remove?)', err && err.message); });
}

function e2eDrainToDevice() {
    if (!e2eReady) return Promise.resolve();
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
        .catch(function (err) { console.warn('không drain được e2e to-device', err); });
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
function e2eCancelDeviceLinkWait() { e2eLinkCode = null; e2eLinkKp = null; e2eLinkWaiters = []; }

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
            return e2eLoadKpQueue().then(function (queue) {
                var mine = queue[0] || null;
                var gen = mine ? Promise.resolve(mine) : e2eGenerateKeyPackages(1).then(function (l) { return l[0]; });
                return gen.then(function (me) {
                    var mePub = e2ePubToKeyPackage(me.pubB64), mePriv = e2ePrivFromJson(me.privJson);
                    var chain = mine ? e2eSaveKpQueue(queue.slice(1)) : Promise.resolve();
                    return chain.then(function () {
                        return M.createGroup(linkGroupId, mePub, mePriv, [], e2eImpl).then(function (s0) {
                            return M.createCommit({ state: s0, cipherSuite: e2eImpl }, { extraProposals: [{ proposalType: 'add', add: { keyPackage: theirPub } }], ratchetTreeExtension: true }).then(function (res) {
                                (res.consumed || []).forEach(function (z) { try { M.zeroOutUint8Array(z); } catch (e) {} });
                                var welcomeWire = e2eB64(M.encodeMlsMessage({ welcome: res.welcome, wireformat: 'mls_welcome', version: 'mls10' }));
                                // gói history
                                return Promise.all([e2eDbGetAllForUser('msgPlaintext'), e2eDbGetAllForUser('groups')]).then(function (dumps) {
                                    var transfer = JSON.stringify({ msgPlaintext: dumps[0], groups: dumps[1].map(function (e) { return { key: e.key, stateB64: e.value.stateB64 }; }) });
                                    return M.createApplicationMessage(res.newState, new TextEncoder().encode(transfer), e2eImpl).then(function (ar) {
                                        var appWire = e2eB64(M.encodeMlsMessage({ privateMessage: ar.privateMessage, wireformat: 'mls_private_message', version: 'mls10' }));
                                        // 2 message tới thiết bị mới: trước welcome, sau payload — thiết bị mới join
                                        // group bằng welcome rồi decrypt payload. Gửi cùng lúc (server giữ thứ tự array?
                                        // POST /e2e/to-device loop theo thứ tự -> queue FIFO -> drain theo created_at ASC).
                                        return e2eDeliverBatch([
                                            { recipientUserId: myUserId, type: 'mls_link_welcome', body: { code: code.toUpperCase(), welcome: welcomeWire } },
                                            { recipientUserId: myUserId, type: 'mls_link_payload', body: { code: code.toUpperCase(), ct: appWire } }
                                        ]);
                                    });
                                });
                            });
                        });
                    });
                });
            });
        });
}

// Device mới: nhận welcome group link -> join -> giữ state tạm. Nhận payload -> decrypt -> import -> resolve.
var e2eLinkGroupState = null; // state group link đang chờ payload (không phải conversation nào, chỉ in-memory)
function e2eHandleLinkWelcome(senderUserId, body) {
    if (!body || !body.welcome || !e2eLinkKp) return Promise.resolve();
    if (e2eLinkCode && body.code && body.code !== e2eLinkCode) return Promise.resolve();
    var welcome = M.decodeMlsMessage(e2eUnb64(body.welcome), 0)[0].welcome;
    var pub = e2ePubToKeyPackage(e2eLinkKp.pubB64), priv = e2ePrivFromJson(e2eLinkKp.privJson);
    return M.joinGroup(welcome, pub, priv, M.emptyPskIndex, e2eImpl).then(function (s) { e2eLinkGroupState = s; })
        .catch(function (err) { console.warn('[e2e-mls] link welcome fail', err); });
}
function e2eHandleLinkPayload(senderUserId, body) {
    if (!body || !body.ct || !e2eLinkGroupState) return Promise.resolve();
    if (e2eLinkCode && body.code && body.code !== e2eLinkCode) return Promise.resolve();
    var msg = M.decodeMlsMessage(e2eUnb64(body.ct), 0)[0];
    return M.processPrivateMessage(e2eLinkGroupState, msg.privateMessage, M.emptyPskIndex, e2eImpl).then(function (res) {
        if (!res || !res.message) return;
        var transfer = JSON.parse(new TextDecoder().decode(res.message));
        return e2eImportTransfer(transfer).then(function () {
            var waiters = e2eLinkWaiters; e2eLinkWaiters = [];
            waiters.forEach(function (w) { clearTimeout(w._timer); w.resolve(transfer); });
            e2eLinkCode = null; e2eLinkKp = null; e2eLinkGroupState = null;
        });
    }).catch(function (err) { console.warn('[e2e-mls] link payload fail', err); });
}
function e2eImportTransfer(transfer) {
    // msgPlaintext entries (key myUserId|msgId -> {body}) nhập thẳng; group entries: chỉ import group CHƯA có
    // local (không đè group hiện có — group local có thể đã advance epoch cao hơn file export).
    var mp = (transfer.msgPlaintext || []).filter(function (e) { return String(e.key).indexOf(myUserId + '|') === 0; });
    var gs = (transfer.groups || []).filter(function (e) { return String(e.key).indexOf(myUserId + '|') === 0; });
    var gEntries = gs.map(function (e) { return { key: e.key, value: { stateB64: e.value.stateB64 || e.stateB64 } }; });
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

// ===== Check rotation: member list ĐỔI (kick/rời) -> remove/commit khi gửi kế tiếp (e2eSyncGroupMembers
// đã làm, không cần code riêng). Nếu MÌNH bị remove ở đâu đó, group local không xoá tự động ở đây vì
// ta không còn nhận commit — chấp nhận: lần gửi tin sẽ fail epoch -> xoá local + tạo lại ở bước 2. =====
// Proactive tạo group cho conv đã bật E2E nhưng chưa có group local (vd conv bật trước fix này,
// hoặc creator vừa reload). CHỈ creator (min userId) mới tạo -- tránh split-brain (2 người cùng tạo
// 2 group riêng -> decrypt chéo -> CryptoError OperationError, bug thật đã gặp). Người không phải
// creator thì chờ welcome (e2eHandleWelcome -> e2eJoinWelcomeTry).
function e2eCheckGroupRotations(oldList, newList) {
    if (!e2eReady) return;
    newList.forEach(function (conv) {
        if (!conv.e2eEnabled) return;
        var sorted = (conv.memberUserIds || []).slice().sort();
        if (sorted.length === 0 || sorted[0] !== myUserId) return; // không phải creator -> chờ welcome
        e2eLoadGroup(conv.conversationId).then(function (state) {
            if (state) return; // đã có group, không tạo lại
            // Creator thấy e2eEnabled nhưng chưa có group -> tạo ngay + welcome mọi member
            return e2eCreateGroupWithMembers(conv.conversationId, conv.memberUserIds);
        }).catch(function (err) { console.warn('[e2e-mls] proactive create group lỗi', err); });
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
