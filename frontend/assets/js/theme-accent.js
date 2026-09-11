// Theme sáng/tối cho TOÀN app (khác nền riêng từng cuộc trò chuyện, xem BG_STORAGE_KEY), lưu localStorage; data-theme đã set sớm ở <head> để tránh nháy sáng->tối, code ở đây chỉ đồng bộ UI và xử lý khi người dùng đổi.
var THEME_STORAGE_KEY = 'pingoTheme_v1';
var THEME_OPTIONS = [
    {mode: 'light', label: 'Sáng', icon: ICON.sun},
    {mode: 'dark', label: 'Tối', icon: ICON.moon},
    {mode: 'system', label: 'Theo hệ thống', icon: ICON.monitor}
];
function loadThemeMode() {
    try { return localStorage.getItem(THEME_STORAGE_KEY) || 'system'; } catch (e) { return 'system'; }
}
function applyThemeMode(mode) {
    if (mode === 'light' || mode === 'dark') document.documentElement.setAttribute('data-theme', mode);
    else document.documentElement.removeAttribute('data-theme');
}
function setThemeMode(mode) {
    try { localStorage.setItem(THEME_STORAGE_KEY, mode); } catch (e) { /* bỏ qua -- theme chỉ là tuỳ biến hiển thị phụ */ }
    applyThemeMode(mode);
    applyAccentKey(loadAccentKey()); // sáng/tối đổi kéo theo sắc độ accent tương ứng phải áp lại
    refreshThemeMenuSelection();
    closeThemeMenu();
}
function refreshThemeMenuSelection() {
    var current = loadThemeMode();
    document.querySelectorAll('#themeMenu .themeMenuItem').forEach(function (el) {
        el.classList.toggle('active', el.dataset.mode === current);
    });
}
// Đặt vị trí popup neo cạnh 1 nút trong #topbar (notif/theme) -- LUÔN mở XUỐNG dưới nút (đủ chỗ vì
// nút nằm sát mép trên màn hình, không như positionComposeEmojiPicker ưu tiên mở LÊN), căn phải theo
// mép phải nút, kẹp lại trong viewport nếu popup rộng hơn khoảng trống bên trái (màn hình hẹp).
function positionTopbarMenu(triggerEl, menu) {
    var rect = triggerEl.getBoundingClientRect();
    var margin = 8;
    var menuWidth = menu.offsetWidth || 320;
    var idealLeft = rect.right - menuWidth;
    var clampedLeft = Math.max(margin, Math.min(window.innerWidth - menuWidth - margin, idealLeft));
    menu.style.top = (rect.bottom + margin) + 'px';
    menu.style.left = clampedLeft + 'px';
}

function toggleThemeMenu(e) {
    e.stopPropagation(); // không thì document click listener đóng menu (mở bằng logic bên dưới) chạy ngay sau khi vừa mở
    var menu = document.getElementById('themeMenu');
    var opening = !menu.classList.contains('show');
    menu.classList.toggle('show');
    if (opening) positionTopbarMenu(document.getElementById('themeBtn'), menu);
}
function closeThemeMenu() {
    document.getElementById('themeMenu').classList.remove('show');
}
document.getElementById('themeBtn').innerHTML = ICON.contrast;
document.getElementById('themeBtn').onclick = toggleThemeMenu;
// Portal thẳng ra document.body (KHÔNG còn lồng trong #themeMenuWrap) -- cùng lý do/cùng pattern với
// mọi popup khác trong app (#reactionPicker, .attachMenu, #bgPickerOverlay...), xem CSS #themeMenu.
document.body.appendChild(document.getElementById('themeMenu'));
var themeMenuEl = document.getElementById('themeMenu');
THEME_OPTIONS.forEach(function (opt) {
    var item = document.createElement('button');
    item.type = 'button';
    item.className = 'themeMenuItem';
    item.dataset.mode = opt.mode;
    item.innerHTML = opt.icon + '<span>' + opt.label + '</span><span class="themeCheck">✓</span>';
    item.onclick = function (e) { e.stopPropagation(); setThemeMode(opt.mode); };
    themeMenuEl.appendChild(item);
});
refreshThemeMenuSelection();
document.addEventListener('click', closeThemeMenu);
document.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeThemeMenu(); });

// Accent màu chủ đạo (lưu localStorage như theme); mỗi preset có cặp giá trị sáng/tối riêng vì accent-soft cần đổi hẳn công thức giữa 2 theme, không chỉ nhạt hơn -- áp lại mỗi khi đổi theme hoặc OS đổi theme lúc đang "Theo hệ thống".
var ACCENT_STORAGE_KEY = 'pingoAccent_v1';
var ACCENT_PRESETS = [
    {key: 'purple', label: 'Tím', light: {accent: '#6d5bf5', accentDark: '#5a48e0', accentSoft: '#efecff', accentRgb: '109, 91, 245'}, dark: {accent: '#7c6cf7', accentDark: '#6a5aeb', accentSoft: '#251f3d', accentRgb: '124, 108, 247'}},
    {key: 'blue', label: 'Xanh dương', light: {accent: '#0084ff', accentDark: '#0066cc', accentSoft: '#e7f3ff', accentRgb: '0, 132, 255'}, dark: {accent: '#3b9eff', accentDark: '#0084ff', accentSoft: '#123a5c', accentRgb: '59, 158, 255'}},
    {key: 'green', label: 'Xanh lá', light: {accent: '#25d366', accentDark: '#1da851', accentSoft: '#e3faec', accentRgb: '37, 211, 102'}, dark: {accent: '#34e37c', accentDark: '#25d366', accentSoft: '#123822', accentRgb: '52, 227, 124'}},
    {key: 'indigo', label: 'Chàm', light: {accent: '#5865f2', accentDark: '#4752c4', accentSoft: '#eceefe', accentRgb: '88, 101, 242'}, dark: {accent: '#7289fa', accentDark: '#5865f2', accentSoft: '#262b4d', accentRgb: '114, 137, 250'}},
    {key: 'pink', label: 'Hồng', light: {accent: '#ec4899', accentDark: '#db2777', accentSoft: '#fce7f3', accentRgb: '236, 72, 153'}, dark: {accent: '#f472b6', accentDark: '#ec4899', accentSoft: '#4a1942', accentRgb: '244, 114, 182'}},
    {key: 'orange', label: 'Cam', light: {accent: '#f97316', accentDark: '#ea580c', accentSoft: '#ffedd5', accentRgb: '249, 115, 22'}, dark: {accent: '#fb923c', accentDark: '#f97316', accentSoft: '#4a2a10', accentRgb: '251, 146, 60'}}
];
function loadAccentKey() {
    try { return localStorage.getItem(ACCENT_STORAGE_KEY) || 'purple'; } catch (e) { return 'purple'; }
}
function isDarkThemeActive() {
    var attr = document.documentElement.getAttribute('data-theme');
    if (attr === 'dark') return true;
    if (attr === 'light') return false;
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
}
function applyAccentKey(key) {
    var preset = ACCENT_PRESETS.find(function (p) { return p.key === key; }) || ACCENT_PRESETS[0];
    var vals = isDarkThemeActive() ? preset.dark : preset.light;
    var rootStyle = document.documentElement.style;
    rootStyle.setProperty('--accent', vals.accent);
    rootStyle.setProperty('--accent-dark', vals.accentDark);
    rootStyle.setProperty('--accent-soft', vals.accentSoft);
    rootStyle.setProperty('--accent-rgb', vals.accentRgb);
}
function setAccentKey(key) {
    try { localStorage.setItem(ACCENT_STORAGE_KEY, key); } catch (e) { /* bỏ qua -- màu chỉ là tuỳ biến hiển thị phụ */ }
    applyAccentKey(key);
    refreshAccentSwatchSelection();
}
function refreshAccentSwatchSelection() {
    var current = loadAccentKey();
    document.querySelectorAll('#themeMenu .accentSwatch').forEach(function (el) {
        el.classList.toggle('active', el.dataset.accent === current);
    });
}
applyAccentKey(loadAccentKey());
if (window.matchMedia) {
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
        if (loadThemeMode() === 'system') applyAccentKey(loadAccentKey());
    });
}
var accentDividerEl = document.createElement('div');
accentDividerEl.className = 'themeMenuDivider';
themeMenuEl.appendChild(accentDividerEl);
var accentLabelEl = document.createElement('div');
accentLabelEl.className = 'accentSwatchLabel';
accentLabelEl.textContent = 'Màu chủ đạo';
themeMenuEl.appendChild(accentLabelEl);
var accentRowEl = document.createElement('div');
accentRowEl.className = 'accentSwatchRow';
ACCENT_PRESETS.forEach(function (preset) {
    var sw = document.createElement('button');
    sw.type = 'button';
    sw.className = 'accentSwatch';
    sw.title = preset.label;
    sw.dataset.accent = preset.key;
    sw.style.background = preset.light.accent;
    sw.onclick = function (e) { e.stopPropagation(); setAccentKey(preset.key); };
    accentRowEl.appendChild(sw);
});
themeMenuEl.appendChild(accentRowEl);
refreshAccentSwatchSelection();

