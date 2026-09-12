// Web push (Firebase Cloud Messaging) -- lấy FCM token của thiết bị này rồi đăng ký với herald
// (PUT /push-tokens, xem HeraldApiHandlers) để PushService bên đó có thể bắn push thật lúc tab
// đóng/nền. Chuông thông báo trong app (history-ws.js) đọc thẳng GET /notifications, KHÔNG phụ
// thuộc file này -- mất quyền/token push chỉ mất noti hệ điều hành, không mất noti trong app.
var FIREBASE_CONFIG = {
    apiKey: "AIzaSyCkTEm22R7lJzQ3yDSq8CKgXbPggBvDSCQ",
    authDomain: "app-chat-5711a.firebaseapp.com",
    projectId: "app-chat-5711a",
    storageBucket: "app-chat-5711a.firebasestorage.app",
    messagingSenderId: "82896283761",
    appId: "1:82896283761:web:e5dbba5a1ef90615f993d0",
    measurementId: "G-RHG38Q9ZQ1"
};
// VAPID public key (Firebase Console > Project settings > Cloud Messaging > Web Push certificates).
var FIREBASE_VAPID_KEY = 'BMlPrfjP4Q2Hma1nW66NeCzdJgVCFfXNVowWmFQn4KeZIizkw0qeeUbF49yWy70ky_cpXBJE0WZ2bZ4AJriezLk';

var firebaseMessaging = null;
var currentPushToken = null; // token FCM hiện tại của thiết bị này, null nếu chưa xin được (chưa cấp quyền/trình duyệt không hỗ trợ) -- dùng để huỷ đăng ký lúc logout().

function firebaseMessagingSupported() {
    return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
}

// Gọi từ enterApp() sau khi đăng nhập -- xin quyền + lấy token + đăng ký với herald. Best-effort
// giống PushService bên herald: lỗi ở bất kỳ bước nào chỉ log ra console, không chặn luồng vào app.
function initPushNotifications() {
    if (!firebaseMessagingSupported()) {
        console.warn('[push] trình duyệt/context này không hỗ trợ web push (cần serviceWorker+PushManager+Notification, và http(s)/localhost -- không chạy được qua file://)');
        return;
    }
    if (typeof firebase === 'undefined') {
        console.warn('[push] SDK firebase chưa load được (CDN gstatic.com bị chặn bởi ad-blocker/mạng?) -- bỏ qua đăng ký push');
        return;
    }
    if (!firebase.apps.length) firebase.initializeApp(FIREBASE_CONFIG);

    navigator.serviceWorker.register('firebase-messaging-sw.js')
        .then(function () {
            // Đợi SW thực sự "ready" (active + kiểm soát trang, xem skipWaiting/clients.claim trong
            // firebase-messaging-sw.js) rồi mới tạo firebaseMessaging -- tạo sớm hơn (lúc SW còn
            // "installing"/chưa kiểm soát trang, lần đăng ký ĐẦU TIÊN luôn vậy) khiến SDK tra cứu
            // registration đang kiểm soát ra undefined, ném "Cannot read properties of undefined
            // (reading 'pushManager')" (gặp thật, không chặn getToken() vì hàm đó nhận registration
            // tường minh riêng, nhưng vẫn là lỗi uncaught rác console).
            return navigator.serviceWorker.ready;
        })
        .then(function (registration) {
            try {
                firebaseMessaging = firebase.messaging();
            } catch (err) {
                console.warn('[push] không khởi tạo được firebase messaging', err);
                return;
            }
            // App đang mở/foreground -- FCM không tự hiện notification cho data-only message (xem
            // firebase-messaging-sw.js), mà chuông thông báo trong app đã đủ nên chỉ cần đồng bộ lại nó.
            firebaseMessaging.onMessage(function () {
                loadNotifications();
            });
            requestPushPermissionAndToken(registration);
        })
        .catch(function (err) { console.warn('không đăng ký được service worker push', err); });
}

// Click notification lúc tab nền (xem notificationclick trong firebase-messaging-sw.js) -- đăng ký
// 1 lần ở scope module là đủ, không phụ thuộc initPushNotifications() có chạy xong hay chưa.
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('message', function (event) {
        var conversationId = event.data && event.data.type === 'pingo-notification-click' && event.data.conversationId;
        if (!conversationId) return;
        var conv = lastConvList.filter(function (c) { return c.conversationId === conversationId; })[0];
        openConversation(conversationId, conv && conversationLabel(conv), conv && membersSubtitle(conv));
        if (isNarrowViewport()) document.getElementById('layout').classList.add('mobileChatOpen');
    });
}

function requestPushPermissionAndToken(registration) {
    // Đã bị chặn từ trước (vd lần trước test lỡ bấm "Block") -- Chrome/Firefox sẽ KHÔNG hiện lại
    // popup xin quyền nữa cho tới khi tự vào site settings gỡ chặn thủ công, đây không phải bug.
    if (Notification.permission === 'denied') {
        console.warn('[push] quyền notification đã bị chặn (denied) từ trước cho origin này -- vào site settings của trình duyệt (icon khoá/(i) cạnh URL) để gỡ, trình duyệt sẽ không tự hiện lại popup');
        return;
    }
    // permission === 'granted' từ trước: requestPermission() resolve NGAY, không hiện popup nào cả
    // (đúng hành vi chuẩn, không phải lỗi) -- vẫn cần chạy tiếp để lấy/refresh token.
    Notification.requestPermission()
        .then(function (permission) {
            console.log('[push] Notification.permission =', permission);
            if (permission !== 'granted') return;
            return firebaseMessaging.getToken({vapidKey: FIREBASE_VAPID_KEY, serviceWorkerRegistration: registration})
                .then(function (token) {
                    if (!token) {
                        console.warn('[push] getToken() trả về rỗng -- kiểm tra lại VAPID key/config Firebase');
                        return;
                    }
                    currentPushToken = token;
                    console.log('[push] đã lấy được FCM token, đang đăng ký với herald:', token);
                    return fetch(HERALD_API_BASE + '/push-tokens', {
                        method: 'PUT',
                        headers: {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + authToken},
                        body: JSON.stringify({token: token})
                    }).then(function (res) {
                        if (!res.ok) throw new Error('HTTP ' + res.status);
                        console.log('[push] đăng ký token với herald thành công');
                    });
                });
        })
        .catch(function (err) { console.warn('[push] không lấy được token push FCM', err); });
}

// Gọi từ logout() TRƯỚC KHI clearAuth() (cần authToken còn hợp lệ cho Authorization header) --
// huỷ đăng ký token của THIẾT BỊ NÀY, tránh máy đã đăng xuất vẫn nhận push cho user cũ.
function unregisterPushToken() {
    if (!currentPushToken) return;
    var token = currentPushToken;
    currentPushToken = null;
    fetch(HERALD_API_BASE + '/push-tokens?token=' + encodeURIComponent(token), {
        method: 'DELETE',
        headers: {'Authorization': 'Bearer ' + authToken}
    }).catch(function (err) { console.warn('không huỷ được push token', err); });
}
