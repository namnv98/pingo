// Service worker cho web push (Firebase Cloud Messaging) -- PHẢI nằm ở root (cùng cấp index.html)
// để scope mặc định "/" bao trọn cả app khi đăng ký ở push-notifications.js.
importScripts('https://www.gstatic.com/firebasejs/10.13.2/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/10.13.2/firebase-messaging-compat.js');

firebase.initializeApp({
  apiKey: "AIzaSyCkTEm22R7lJzQ3yDSq8CKgXbPggBvDSCQ",
  authDomain: "app-chat-5711a.firebaseapp.com",
  projectId: "app-chat-5711a",
  storageBucket: "app-chat-5711a.firebasestorage.app",
  messagingSenderId: "82896283761",
  appId: "1:82896283761:web:e5dbba5a1ef90615f993d0",
  measurementId: "G-RHG38Q9ZQ1"
});

// Chiếm quyền kiểm soát trang NGAY (không đợi hết mọi tab cũ đóng) -- thiếu 2 dòng này thì lần
// đăng ký SW đầu tiên KHÔNG "controlling" trang hiện tại cho tới lúc reload, khiến
// firebase.messaging() ở phía main thread (push-notifications.js) tra cứu registration đang kiểm
// soát ra undefined -- gặp thật: "TypeError: Cannot read properties of undefined (reading
// 'pushManager')" dù getToken() vẫn chạy được (nó tự truyền registration tường minh, không phụ
// thuộc "controller").
self.addEventListener('install', function (event) { event.waitUntil(self.skipWaiting()); });
self.addEventListener('activate', function (event) { event.waitUntil(self.clients.claim()); });

var messaging = firebase.messaging();

// herald chỉ gửi data-only message (xem PushService#sendToUser bên herald -- Message.builder()
// không setNotification), nên Firebase KHÔNG tự hiện notification lúc tab đóng/nền như payload
// kiểu "notification" thường -- phải tự showNotification() ở đây.
messaging.onBackgroundMessage(function (payload) {
  var data = payload.data || {};
  self.registration.showNotification(data.title || 'Pingo', {
    body: data.body || '',
    icon: 'images/logo.svg',
    tag: data.conversationId || undefined, // nhiều tin dồn dập cùng conversation -- gộp thành 1 notification thay vì rung liên tục.
    data: {conversationId: data.conversationId}
  });
});

// Bấm vào notification -- ưu tiên focus tab đang mở sẵn (báo conversationId qua postMessage để
// push-notifications.js tự mở đúng hội thoại) thay vì luôn mở tab mới.
self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var conversationId = event.notification.data && event.notification.data.conversationId;
  event.waitUntil(
    clients.matchAll({type: 'window', includeUncontrolled: true}).then(function (windowClients) {
      for (var i = 0; i < windowClients.length; i++) {
        var client = windowClients[i];
        if ('focus' in client) {
          client.postMessage({type: 'pingo-notification-click', conversationId: conversationId});
          return client.focus();
        }
      }
      if (clients.openWindow) return clients.openWindow(self.registration.scope);
    })
  );
});
