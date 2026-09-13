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
  var tag = data.conversationId || undefined;
  // KHÔNG dựa vào renotify để Chrome tự "bật lại" noti trùng tag -- gặp thật trên Chrome+Linux
  // (kể cả gọi showNotification() trực tiếp, không qua push): noti ĐẦU TIÊN của 1 tag luôn hiện,
  // nhưng noti THỨ HAI trở đi trùng tag bị Chrome nuốt im lặng dù renotify=true (không phải lỗi
  // app/server, tự tay lặp lại new Notification({tag, renotify:true}) 2 lần cũng thấy y hệt) --
  // renotify chỉ là gợi ý cho trình duyệt, không có gì đảm bảo nó tôn trọng. Né hẳn cơ chế đó: tự
  // đóng noti cũ cùng tag rồi mở noti MỚI (không tag) -- luôn là 1 noti "mới toanh" với trình duyệt
  // nên luôn được alert, đồng thời vẫn không bao giờ có quá 1 noti/hội thoại hiển thị cùng lúc.
  return (tag ? self.registration.getNotifications({tag: tag}) : Promise.resolve([]))
    .then(function (existing) {
      existing.forEach(function (n) { n.close(); });
      self.registration.showNotification(data.title || 'Pingo', {
        body: data.body || '',
        icon: 'images/logo.svg',
        tag: tag, // giữ tag để LẦN SAU tìm + đóng noti này -- không còn dùng nó để trình duyệt tự thay thế/renotify nữa (đã đóng thủ công ở trên trước khi tới đây).
        data: {conversationId: data.conversationId}
      });
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
