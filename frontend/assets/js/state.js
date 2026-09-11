var ws = null;
var myUserId = '';
var myUsername = '';
var authToken = '';
var countSent = 0;
var countReceived = 0;
var reconnectDelayMs = 1000;
var RECONNECT_MAX_DELAY_MS = 8000;
// Client tu ping co dinh moi PING_INTERVAL_MS (kieu Slack) thay vi doi server phat hien idle; server van giu lop tu ping du phong cho client khac.
var PING_INTERVAL_MS = 10000;
var pingIntervalId = null;
var conversations = {}; // conversationId -> { label, el, logEl, historyLoaded }
var pendingReadAcks = []; // [{id, conversationId}, ...] -- tin đã hiển thị nhưng CHƯA gửi READ vì lúc đó tab đang nền/mất focus (xem flushPendingReadAcks)
var activeConversationId = null; // conversationId đang hiển thị trong #chatMain (chỉ 1 tại 1 thời điểm, xem selectConversation)
// Badge "chưa đọc" đọc trực tiếp conv.unreadCount từ server (tính bằng SQL) -- không dùng cờ tạm ở client vì reload mất hết (đã gặp thật).
var lastConvList = []; // cache list gần nhất từ GET /conversations, dùng để re-render sidebar cục bộ (vd tăng tạm unreadCount lúc tin sống tới) mà không cần fetch lại
var selectedGroupMemberIds = []; // userId đang chọn làm thành viên group (tab "Tạo group", xem toggleGroupMember)

// userId -> true/false; snapshot ban đầu lấy từ GET /presence, WS (PRESENCE) chỉ báo thay đổi về sau, không tự biết trạng thái ban đầu.
var onlineUserIds = {};
// HERALD_API_BASE: NodePort riêng của herald -- xem herald/helm/templates/services.yaml.
var HERALD_API_BASE = 'http://localhost:31007';

// conversationId -> { userId: timeoutId }; server không có frame "đã dừng gõ" riêng -- tự suy hết hạn bằng timeout.
var typingTimers = {};
var TYPING_EXPIRE_MS = 4000;
// conversationId -> lúc gần nhất MÌNH gửi TYPING đi -- throttle, không gửi mỗi keystroke.
var lastTypingSentAt = {};
var TYPING_THROTTLE_MS = 2500;

// messageId -> { userId: emoji } -- nguồn vẽ lại huy hiệu (renderReactions), cập nhật qua frame REACTION hoặc GET /messages.
var reactionsByMessageId = {};
// Icon ảnh thay emoji Unicode để hiển thị đồng nhất mọi OS; emoji vẫn là định danh gửi server (chỉ lưu chuỗi emoji, không biết icon) và để chèn vào ô nhập tin.
var REACTIONS = [
    {emoji: '👍', icon: 'images/chat/like.svg'},
    {emoji: '❤️', icon: 'images/chat/heart.svg'},
    {emoji: '😆', icon: 'images/chat/haha.svg'},
    {emoji: '😮', icon: 'images/chat/wow.svg'},
    {emoji: '😢', icon: 'images/chat/sad.svg'},
    {emoji: '😠', icon: 'images/chat/angry.svg'}
];
var REACTION_ICON_BY_EMOJI = {};
REACTIONS.forEach(function (r) { REACTION_ICON_BY_EMOJI[r.emoji] = r.icon; });
var reactionPickerTarget = null; // {conversationId, messageId} đang mở picker cho tin nào
var reactionPickerTriggerEl = null; // nút 🙂 đã mở picker hiện tại -- giữ hiện cưỡng bức (class "picker-open") tới khi đóng

// localStorage cache token JWT thật (do colony cấp) để khỏi đăng nhập lại mỗi lần mở trang, tới khi hết hạn (xem AUTH_ERROR).
var STORAGE_TOKEN_KEY = 'pingoAuthToken';
var STORAGE_ID_KEY = 'pingoUserId';
var STORAGE_USERNAME_KEY = 'pingoUsername';

// NodePort riêng của colony cho REST API -- đổi theo publicHttp.port nếu chạy local bằng "mvn exec:java" thay vì k3s.
var HISTORY_API_BASE = 'http://localhost:31002';

// NodePort của file-server (upload/download ảnh-video); metadata (id/path/mime) do hall cấp phát (xem HallApiHandlers/FileRegistry), file thật lưu/đọc trên file-server.
var FILE_SERVER_BASE = 'http://localhost:31008';
// Khớp "upload.max: 20m" của file-server -- chặn sớm phía client cho nhanh, server vẫn tự chặn lại.
var MAX_UPLOAD_BYTES = 1024 * 1024 * 1024;
// Giới hạn UI khi chọn nhiều file cùng lúc -- mỗi file vẫn upload+gửi thành 1 tin riêng (không có khái niệm "1 tin nhiều file" ở server).
var MAX_PENDING_FILES = 10;

var knownUsers = []; // [{id, username, firstSeenAt, avatarFileId}, ...] từ GET /users
var usernameById = {}; // cache tra nhanh id -> username khi hiển thị chat log
var avatarFileIdById = {}; // cache tra nhanh id -> avatarFileId (ảnh đại diện, có thể null), xem conversationAvatar()

// true từ khi enterApp() chạy tới khi logout()/AUTH_ERROR -- connect()/ws.onclose dùng để biết có nên tự mở/giữ WebSocket.
var identityConfirmed = false;

// Icon outline tự vẽ (SVG, stroke="currentColor"), không phụ thuộc icon font/CDN ngoài để index.html chạy được khi mở trực tiếp bằng file://.
var ICON = {
    search: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>',
    star: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26"/></svg>',
    edit: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>',
    // trash/pin/reply/lock/link dùng Font Awesome (CDN) thay vì SVG ở đây.
    info: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>',
    paperclip: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>',
    plus: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>',
    sparkle: '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M12 2l1.8 5.2L19 9l-5.2 1.8L12 16l-1.8-5.2L5 9l5.2-1.8L12 2z"/></svg>',
    sticker: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H8a5 5 0 0 0-5 5v8a5 5 0 0 0 5 5h6l6-6V8a5 5 0 0 0-5-5z"/><path d="M14 21v-3a3 3 0 0 1 3-3h3"/></svg>',
    mail: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 6l-10 7L2 6"/><rect x="2" y="4" width="20" height="16" rx="2"/></svg>',
    users: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
    refresh: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>',
    hash: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/><line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/></svg>',
    clock: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></svg>',
    activity: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>',
    messageCircle: '<svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
    back: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>',
    send: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>',
    down: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></svg>',
    emoji: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><line x1="9" y1="9" x2="9.01" y2="9"/><line x1="15" y1="9" x2="15.01" y2="9"/></svg>',
    play: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
    close: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="6" y1="18" x2="18" y2="6"/></svg>',
    closeSm: '<svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="6" x2="18" y2="18"/><line x1="6" y1="18" x2="18" y2="6"/></svg>',
    chevronLeft: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>',
    chevronRight: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>',
    image: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>',
    contrast: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 3a9 9 0 0 0 0 18z" fill="currentColor" stroke="none"/></svg>',
    sun: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="4.5"/><line x1="12" y1="1.5" x2="12" y2="4"/><line x1="12" y1="20" x2="12" y2="22.5"/><line x1="4.2" y1="4.2" x2="6" y2="6"/><line x1="18" y1="18" x2="19.8" y2="19.8"/><line x1="1.5" y1="12" x2="4" y2="12"/><line x1="20" y1="12" x2="22.5" y2="12"/><line x1="4.2" y1="19.8" x2="6" y2="18"/><line x1="18" y1="6" x2="19.8" y2="4.2"/></svg>',
    moon: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/></svg>',
    monitor: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="4" width="19" height="13" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>'
};
// Nút tĩnh trong HTML (không tự vẽ lại qua JS) -- gán icon 1 lần lúc script chạy, DOM đã có sẵn vì
// <script> nằm cuối <body>.
document.getElementById('sidebarSearchIcon').innerHTML = ICON.search;
document.getElementById('newConvSearchIcon').innerHTML = ICON.search;
document.getElementById('globalSearchBtn').innerHTML = ICON.search;
document.getElementById('newConvCloseBtn').innerHTML = ICON.closeSm;
document.getElementById('userListRefreshBtn').innerHTML = ICON.refresh;
document.getElementById('convListRefreshBtn').innerHTML = ICON.refresh;
document.getElementById('infoStatusIcon').innerHTML = ICON.activity;
document.getElementById('infoTypeIcon').innerHTML = ICON.messageCircle;
document.getElementById('infoMemberIcon').innerHTML = ICON.users;
document.getElementById('infoActivityIcon').innerHTML = ICON.clock;
document.getElementById('infoConvIdIcon').innerHTML = ICON.hash;

