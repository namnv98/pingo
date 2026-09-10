// Xoá MỀM 1 tin CỦA CHÍNH MÌNH -- server tự kiểm tra lại quyền (so from_user_id thật trong DB, xem
// ChatSessionManager#handleDelete bên colony), nút xoá vốn cũng chỉ hiện với tin "mine" nên đây chỉ
// là hàng rào phụ phía client, không phải chốt bảo mật thật.
function deleteMessage(conversationId, messageId) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    appConfirm('Xoá tin nhắn này? Không thể hoàn tác.', {title: 'Xoá tin nhắn', confirmText: 'Xoá', cancelText: 'Huỷ', danger: true}).then(function(ok){ if(!ok) return; send({type: 'DELETE', id: messageId, conversationId: conversationId}); });
}

// Thay nội dung 1 dòng tin bằng placeholder "đã bị xoá" -- dùng chung cho cả tin MỚI bị xoá (nhận
// frame DELETE) lẫn tin xoá TỪ TRƯỚC nạp lại từ lịch sử (GET /messages trả deleted:true). Xoá luôn
// reaction/nút hành động -- tin đã xoá thì không còn gì để react/xoá thêm nữa.
function renderDeletedPlaceholder(row) {
    var bubbleEl = row.querySelector('.bubble');
    if (bubbleEl) {
        bubbleEl.innerHTML = '';
        bubbleEl.classList.remove('media-only');
        bubbleEl.classList.add('deletedPlaceholder');
        bubbleEl.innerText = 'Tin nhắn đã bị xoá';
    }
    var reactionsRow = row.querySelector('.reactions-row');
    if (reactionsRow) reactionsRow.innerHTML = '';
    var actionsEl = row.querySelector('.msg-actions');
    if (actionsEl) actionsEl.remove();
    delete reactionsByMessageId[row.dataset.messageId];
}

// Nhận frame DELETE (của mình lẫn người khác, xem javadoc fan-out bên BackendStreamGateway) -- tìm
// đúng row theo messageId (TOÀN BỘ #chatMain, kể cả card không đang active, giống handleSeenReceived).
function handleMessageDeleted(conversationId, messageId) {
    if (!messageId) return;
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (row) renderDeletedPlaceholder(row);
    document.querySelectorAll('.reply-quote[data-reply-to-id="' + CSS.escape(messageId) + '"]').forEach(function(q){
        q.classList.add('reply-not-found');
        var sn = q.querySelector('.reply-quote-snippet');
        if (sn) sn.innerText = 'Tin nh\u1eafn \u0111\u00e3 b\u1ecb xo\u00e1';
    });
    // Tin đã xoá thì không còn gì để "đọc" nữa -- bớt khỏi chấm đỏ (nếu đang tính là chưa đọc) thay
    // vì để nguyên 1 số đếm cho 1 tin giờ chẳng còn nội dung gì để xem.
    var entry = conversations[conversationId];
    if (entry && entry.unreadIdSet.delete(messageId)) {
        entry.totalUnreadCount = Math.max(0, (entry.totalUnreadCount || 0) - 1);
        updateJumpBadge(entry);
    }
}

// Bấm lại ĐÚNG emoji mình đang chọn -- gửi body rỗng để HUỶ (server hiểu "thiếu emoji" = huỷ, xem
// MessageType#REACTION); chọn emoji khác -- gửi emoji mới, server tự THAY THẾ (không cộng dồn).
function sendReaction(conversationId, messageId, emoji) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    var mine = reactionsByMessageId[messageId] && reactionsByMessageId[messageId][myUserId];
    var body = mine === emoji ? {} : {emoji: emoji};
    send({type: 'REACTION', id: messageId, conversationId: conversationId, body: body});
}

// Nhận frame REACTION (của mình lẫn người khác, xem javadoc fan-out bên BackendStreamGateway) --
// cập nhật state cục bộ rồi vẽ lại huy hiệu trên đúng bubble (tìm theo messageId, không quan tâm
// conversation nào đang mở, giống handleSeenReceived).
function handleReactionReceived(messageId, userId, emoji) {
    if (!messageId || !userId) return;
    if (!reactionsByMessageId[messageId]) reactionsByMessageId[messageId] = {};
    if (emoji) {
        reactionsByMessageId[messageId][userId] = emoji;
    } else {
        delete reactionsByMessageId[messageId][userId];
    }
    renderReactions(messageId);
}

// rowEl tuỳ chọn -- truyền thẳng khi ĐÃ có sẵn tham chiếu (vd appendMessageBubble, tránh 1 lớp bug
// đã gặp: gọi querySelector cho 1 node vừa tạo nhưng chưa appendChild vào DOM thì tìm không ra).
// Không truyền thì tự dò qua document (dùng khi nhận frame REACTION live, không giữ sẵn tham chiếu).
// Tooltip riêng cho reaction (danh sách ai đã thả) -- 1 phần tử dùng chung, tái định vị mỗi lần
// hiện, cùng cách làm với reactionPicker (tránh tạo/xoá DOM liên tục mỗi lần hover).
function ensureReactionTooltip() {
    var tip = document.getElementById('reactionTooltip');
    if (tip) return tip;
    tip = document.createElement('div');
    tip.id = 'reactionTooltip';
    document.body.appendChild(tip);
    return tip;
}

function showReactionTooltip(targetEl, text) {
    var tip = ensureReactionTooltip();
    tip.innerText = text;
    tip.classList.add('show');
    var rect = targetEl.getBoundingClientRect();
    var tipWidth = tip.offsetWidth;
    var idealLeft = rect.left + rect.width / 2 - tipWidth / 2;
    var left = Math.max(4, Math.min(window.innerWidth - tipWidth - 4, idealLeft));
    tip.style.left = left + 'px';
    tip.style.top = Math.max(4, rect.top - tip.offsetHeight - 10) + 'px';
    // Box có thể bị đẩy lệch khỏi vị trí "ngay giữa badge" khi kẹp sát lề màn hình -- mũi tên phải tự
    // bù lại độ lệch đó để luôn trỏ đúng vào badge, thay vì đứng yên ở giữa box (xem reactionPicker).
    var arrowLeft = (rect.left + rect.width / 2 - left);
    arrowLeft = Math.max(10, Math.min(tipWidth - 10, arrowLeft));
    tip.style.setProperty('--arrow-left', arrowLeft + 'px');
}

function hideReactionTooltip() {
    var tip = document.getElementById('reactionTooltip');
    if (tip) tip.classList.remove('show');
}

function renderReactions(messageId, rowEl) {
    var row = rowEl || document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    var container = row && row.querySelector('.reactions-row');
    if (!container) return;
    var wrapEl = row.querySelector('.bubble-wrap');
    var conversationId = row.dataset.conversationId;
    var entry = conversations[conversationId];
    var logEl = entry && entry.logEl;
    var mapping = reactionsByMessageId[messageId] || {};
    var byEmoji = {};
    Object.keys(mapping).forEach(function (uid) {
        var emoji = mapping[uid];
        (byEmoji[emoji] = byEmoji[emoji] || []).push(uid);
    });
    // Tách riêng phần ĐỔI DOM (sửa container + toggle has-reactions, cả 2 đều có thể đổi chiều cao
    // bubble) khỏi phần render, để đo offsetHeight trước/sau + bù scrollTop bằng getBoundingClientRect
    // (KHÔNG dùng offsetTop: .conv-log không có position:relative nên row.offsetTop không hề cùng hệ
    // toạ độ với logEl.scrollTop, so sánh 2 cái đó với nhau là sai -- đã tự làm sai y hệt vậy ở 1 lần
    // sửa trước).
    function mutate() {
        container.innerHTML = '';
        Object.keys(byEmoji).forEach(function (emoji) {
            var uids = byEmoji[emoji];
            var pill = document.createElement('button');
            pill.type = 'button';
            pill.className = 'reaction-pill' + (uids.indexOf(myUserId) !== -1 ? ' mine' : '');
            // Giống Facebook thật: chỉ hiện SỐ LƯỢNG khi >= 2 người -- 1 người thì chỉ cần mỗi emoji là đủ.
            pill.innerHTML = '<span class="emoji"></span>' + (uids.length > 1 ? '<span class="count"></span>' : '');
            var emojiEl = pill.querySelector('.emoji');
            var iconSrc = REACTION_ICON_BY_EMOJI[emoji];
            // Fallback về ký tự emoji thô cho reaction cũ không khớp bộ icon hiện tại (vd đổi bộ icon
            // sau khi đã có dữ liệu reaction cũ trong DB) -- không để badge trống trơn.
            if (iconSrc) emojiEl.innerHTML = '<img src="' + iconSrc + '" width="14" height="14" alt="' + emoji + '">';
            else emojiEl.innerText = emoji;
            if (uids.length > 1) pill.querySelector('.count').innerText = uids.length;
            // Tooltip tự làm (đẹp/nhanh hơn title mặc định của trình duyệt) -- xem showReactionTooltip.
            var tooltipText = uids.map(displayName).join(', ') + ' đã bày tỏ cảm xúc ' + emoji;
            pill.onmouseenter = function () { showReactionTooltip(pill, tooltipText); };
            pill.onmouseleave = hideReactionTooltip;
            pill.onclick = function (e) { e.stopPropagation(); hideReactionTooltip(); sendReaction(conversationId, messageId, emoji); };
            container.appendChild(pill);
        });
        // Chỉ có ý nghĩa cho bong bóng DM (.bubble-wrap không tồn tại ở tin nhóm phẳng) -- badge đè
        // lên góc bubble cần chừa khoảng trống bên dưới để không đè lên giờ/seen-status, xem CSS
        // .has-reactions -- bật cờ này cũng đổi padding-bottom của bubble (xem CSS), tức bubble có
        // thể phình cao hơn, đúng lý do cần bọc withScrollAnchored/cuộn bù ở dưới.
        if (wrapEl) wrapEl.classList.toggle('has-reactions', Object.keys(byEmoji).length > 0);
    }
    if (!logEl) {
        mutate();
        return;
    }
    // Đo TRƯỚC/SAU (row.offsetHeight) rồi bù scrollTop NGAY LẬP TỨC (gán tức thời, KHÔNG
    // smoothScrollToBottom/behavior:'smooth') -- đây là BÙ LỆCH do bubble phình vài px vì reaction,
    // không phải "tin mới thật sự tới" (chỗ ĐÓ mới nên animate, xem appendMessageBubble). Animate 1
    // quãng ngắn thế này nhìn như giật/nháy chứ không mượt, đúng lý do applyScrollAnchor/
    // composeResizeObserver cũng cố tình gán tức thời thay vì animate (xem comment 2 hàm đó).
    var before = row.offsetHeight;
    mutate();
    var delta = row.offsetHeight - before;
    if (delta === 0) return;
    if (entry.stickToBottom) {
        // Đang neo đáy -- GÁN THẲNG bằng scrollHeight (trình duyệt tự kẹp về max hợp lệ), KHÔNG cộng
        // dồn += delta như nhánh dưới. Lý do: khi bubble CO LẠI (bỏ reaction, delta âm), trình duyệt
        // tự kéo scrollTop về max MỚI ngay khi scrollHeight giảm -- xảy ra TRƯỚC dòng này, sớm hơn cả
        // lúc code chạy tới đây. Cộng thêm delta (âm) lần nữa thành trừ KÉP, vọt lên quá đà, không
        // còn thật sự ở đáy (đúng bug "bỏ reaction lúc đang ở cuối cùng bị lỗi"). Gán thẳng
        // scrollHeight né hẳn vấn đề thứ tự này -- đằng nào cũng luôn muốn kết quả CUỐI CÙNG là đúng
        // đáy, không cần quan tâm delta dương hay âm.
        logEl.scrollTop = logEl.scrollHeight;
    } else {
        // Đang cuộn lên xem tin cũ hơn -- CHỈ bù khi row KHÔNG nằm hẳn dưới khung nhìn (đang hiện
        // HOẶC đã cuộn qua rồi đều bù, xem chat trước); row nằm hẳn dưới khung nhìn (chưa cuộn tới)
        // thì bỏ qua, bù lúc đó sẽ kéo người dùng lệch khỏi chỗ đang xem vô cớ.
        var logRect = logEl.getBoundingClientRect();
        var rowRect = row.getBoundingClientRect();
        if (rowRect.top < logRect.bottom) logEl.scrollTop += delta;
    }
}

function messageBodyText(body) {
    return body && body.message !== undefined ? body.message : JSON.stringify(body);
}
function snippetForBody(body) {
    if (!body) return 'Tin nh\u1eafn';
    var files = (typeof getMessageFiles === 'function') ? getMessageFiles(body) : [];
    if (files.length) {
        var cap = (body.message || '').trim();
        var label = files.length === 1 ? ((files[0].fileMime||'').indexOf('video/')===0 ? '\uD83C\uDFA5 Video' : '\uD83D\uDDBC H\u00ecnh \u1ea3nh') : ('\uD83D\uDCCE ' + files.length + ' t\u1ec7p');
        var shortCap = cap ? (cap.length>40? cap.slice(0,40)+'\u2026' : cap) : '';
        return shortCap ? label + ' \u00b7 ' + shortCap : label;
    }
    var t = (body.message || '').trim();
    if (!t) return 'Tin nh\u1eafn';
    return t.length > 80 ? t.slice(0,80) + '\u2026' : t;
}
function replyThumbForBody(body) {
    if (!body) return null;
    var files = (typeof getMessageFiles === 'function') ? getMessageFiles(body) : [];
    if (files.length) {
        var f = files[0];
        var isVideo = (f.fileMime||'').indexOf('video/')===0;
        var url = null;
        try { url = (isVideo && typeof posterUrlFor==='function') ? (posterUrlFor(f) || f.fileUrl) : f.fileUrl; } catch(e) { url = f.fileUrl; }
        if (url) return {url: url, isVideo: isVideo};
    }
    if (body.preview && body.preview.image) return {url: body.preview.image, isVideo: false};
    return null;
}
function buildReplyQuoteEl(replyTo, conversationId) {
    var q = document.createElement('div');
    q.className = 'reply-quote';
    q.dataset.replyToId = replyTo.messageId;
    var thumbHtml = replyTo.thumbUrl ? '<img class="reply-quote-thumb" src="' + replyTo.thumbUrl.replace(/"/g,'&quot;') + '" alt="">' : '';
    q.innerHTML = thumbHtml + '<div class="reply-quote-body"><div class="reply-quote-name"></div><div class="reply-quote-snippet"></div></div>';
    q.querySelector('.reply-quote-name').innerText = displayName(replyTo.fromUserId);
    q.querySelector('.reply-quote-snippet').innerText = replyTo.snippet || 'Tin nh\u1eafn';
    var th = q.querySelector('.reply-quote-thumb');
    if (th) th.onerror = function(){ th.style.display='none'; };
    q.onclick = function(e){ e.stopPropagation(); jumpToMessage(conversationId, replyTo.messageId, replyTo.ts); };
    return q;
}
function ensureAppModal(){
    var ov = document.getElementById('appModalOverlay');
    if (ov) return ov;
    ov = document.createElement('div');
    ov.id = 'appModalOverlay';
    ov.innerHTML = '<div class="appModal" role="dialog" aria-modal="true"><div class="appModalHead"></div><div class="appModalBody"></div><input class="appModalInput" type="text"><div class="appModalHint" style="display:none"></div><div class="appModalFoot"></div></div>';
    document.body.appendChild(ov);
    ov.addEventListener('click', function(e){ if(e.target===ov){ var c=ov._onCancel; if(c) c(); } });
    return ov;
}
function appAlert(message, title){
    return new Promise(function(resolve){
        var ov = ensureAppModal();
        ov.querySelector('.appModalHead').innerText = title || 'Th\u00f4ng b\u00e1o';
        ov.querySelector('.appModalBody').innerText = message;
        var inp = ov.querySelector('.appModalInput'); inp.classList.remove('show','invalid'); inp.value='';
        var hint = ov.querySelector('.appModalHint'); hint.style.display='none'; hint.innerText='';
        var foot = ov.querySelector('.appModalFoot'); foot.innerHTML='';
        var ok = document.createElement('button'); ok.className='appModalBtn primary'; ok.innerText='OK';
        foot.appendChild(ok);
        function close(){ ov.classList.remove('show'); document.removeEventListener('keydown', onKey); }
        function onKey(e){ if(e.key==='Escape'){ close(); resolve(); } if(e.key==='Enter'){ close(); resolve(); } }
        ok.onclick = function(){ close(); resolve(); };
        ov._onCancel = function(){ close(); resolve(); };
        ov.classList.add('show');
        document.addEventListener('keydown', onKey);
        ok.focus();
    });
}
function appConfirm(message, opts){
    opts = opts || {};
    return new Promise(function(resolve){
        var ov = ensureAppModal();
        ov.querySelector('.appModalHead').innerText = opts.title || 'X\u00e1c nh\u1eadn';
        ov.querySelector('.appModalBody').innerText = message;
        var inp = ov.querySelector('.appModalInput'); inp.classList.remove('show','invalid'); inp.value='';
        var hint = ov.querySelector('.appModalHint'); hint.style.display='none'; hint.innerText='';
        var foot = ov.querySelector('.appModalFoot'); foot.innerHTML='';
        var cancel = document.createElement('button'); cancel.className='appModalBtn'; cancel.innerText = opts.cancelText || 'Hu\u1ef7';
        var ok = document.createElement('button'); ok.className='appModalBtn ' + (opts.danger ? 'danger' : 'primary'); ok.innerText = opts.confirmText || 'X\u00e1c nh\u1eadn';
        foot.appendChild(cancel); foot.appendChild(ok);
        function close(v){ ov.classList.remove('show'); document.removeEventListener('keydown', onKey); resolve(v); }
        function onKey(e){ if(e.key==='Escape') close(false); }
        cancel.onclick = function(){ close(false); };
        ok.onclick = function(){ close(true); };
        ov._onCancel = function(){ close(false); };
        ov.classList.add('show');
        document.addEventListener('keydown', onKey);
        ok.focus();
    });
}
function appPrompt(message, defaultValue, opts){
    opts = opts || {};
    return new Promise(function(resolve){
        var ov = ensureAppModal();
        ov.querySelector('.appModalHead').innerText = opts.title || 'Nh\u1eadp th\u00f4ng tin';
        ov.querySelector('.appModalBody').innerText = message;
        var inp = ov.querySelector('.appModalInput'); inp.value = defaultValue || ''; inp.placeholder = opts.placeholder || ''; inp.classList.add('show'); inp.classList.remove('invalid');
        var hint = ov.querySelector('.appModalHint');
        if (opts.hint){ hint.innerText = opts.hint; hint.style.display='block'; hint.className='appModalHint'; } else { hint.style.display='none'; hint.innerText=''; }
        var foot = ov.querySelector('.appModalFoot'); foot.innerHTML='';
        var cancel = document.createElement('button'); cancel.className='appModalBtn'; cancel.innerText = opts.cancelText || 'Hu\u1ef7';
        var ok = document.createElement('button'); ok.className='appModalBtn primary'; ok.innerText = opts.confirmText || 'L\u01b0u';
        foot.appendChild(cancel); foot.appendChild(ok);
        function close(v){ ov.classList.remove('show'); document.removeEventListener('keydown', onKey); resolve(v); }
        function onKey(e){ if(e.key==='Escape') close(null); if(e.key==='Enter') doOk(); }
        function doOk(){
            var v = inp.value;
            if (opts.required && !v.trim()){ inp.classList.add('invalid'); hint.innerText = opts.requiredMessage || 'Kh\u00f4ng \u0111\u01b0\u1ee3c \u0111\u1ec3 tr\u1ed1ng'; hint.style.display='block'; hint.className='appModalHint error'; inp.focus(); return; }
            if (opts.validate){ var err = opts.validate(v); if(err){ inp.classList.add('invalid'); hint.innerText=err; hint.style.display='block'; hint.className='appModalHint error'; inp.focus(); return; } }
            close(v);
        }
        cancel.onclick = function(){ close(null); };
        ok.onclick = doOk;
        ov._onCancel = function(){ close(null); };
        ov.classList.add('show');
        document.addEventListener('keydown', onKey);
        inp.focus(); inp.select();
        inp.oninput = function(){ inp.classList.remove('invalid'); if(!opts.hint){ hint.style.display='none'; } else { hint.className='appModalHint'; } };
    });
}
function showToast(msg){
    var t=document.createElement('div'); t.innerText=msg;
    t.style.cssText='position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:rgba(24,24,27,.92);color:#fff;padding:8px 14px;border-radius:8px;font-size:13px;z-index:9999;opacity:0;transition:opacity .2s';
    document.body.appendChild(t); requestAnimationFrame(function(){ t.style.opacity='1'; });
    setTimeout(function(){ t.style.opacity='0'; setTimeout(function(){ t.remove(); },300); },2200);
}
function jumpToMessage(conversationId, targetMessageId, targetTs) {
    var entry = conversations[conversationId];
    if (!entry) return;
    if (typeof selectConversation === 'function' && conversationId !== activeConversationId) {
        selectConversation(conversationId);
        entry = conversations[conversationId];
    }
    // Đợi trang mặc định (loadHistory, do selectConversation ở trên vừa gọi/đang gọi dở) NẠP + VẼ
    // XONG HẲN trước khi quyết định gì cả -- không thì đường "seek" riêng bên dưới chạy SONG SONG với
    // loadHistory, ai render xong SAU sẽ ghi đè DOM của người xong TRƯỚC (logEl.innerHTML = ''),
    // scroll/tô sáng tính trên 1 DOM sắp bị xoá là vô nghĩa -- bug thật đã gặp: bấm noti mở đúng
    // conversation nhưng vị trí cuộn/tin hiện ra sai lung tung tuỳ ai xong trước ai xong sau.
    Promise.resolve(loadHistory(conversationId)).then(function () {
        performJumpToMessage(conversationId, targetMessageId, targetTs);
    });
}

function performJumpToMessage(conversationId, targetMessageId, targetTs) {
    var entry = conversations[conversationId];
    if (!entry) return;
    var row = entry.logEl.querySelector('[data-message-id="' + CSS.escape(targetMessageId) + '"]');
    if (row) {
        row.scrollIntoView({behavior: 'smooth', block: 'center'});
        row.classList.add('jump-highlight');
        setTimeout(function(){ row.classList.remove('jump-highlight'); }, 1300);
        return;
    }
    if (targetTs == null) {
        showToast('Tin g\u1ed1c ch\u01b0a \u0111\u01b0\u1ee3c t\u1ea3i \u2014 h\u00e3y cu\u1ed9n l\u00ean \u0111\u1ec3 t\u1ea3i th\u00eam');
        return;
    }
    if (entry._seeking) return;
    entry._seeking = true;
    showToast('\u0110ang t\u1ea3i tin g\u1ed1c...');
    var SEEK_HALF = Math.floor(HISTORY_PAGE_SIZE / 2) || 15;
    Promise.all([
        fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + SEEK_HALF + '&before=' + (targetTs + 1)),
        fetchJson('/messages?conversationId=' + encodeURIComponent(conversationId) + '&limit=' + SEEK_HALF + '&after=' + targetTs)
    ]).then(function(results){
        var beforeMsgs = results[0] || [];
        var afterMsgs = results[1] || [];
        var logEl = entry.logEl;
        try { entry.readObserver.disconnect(); } catch(e) {}
        logEl.innerHTML = '';
        entry.renderedMessageIds.clear();
        entry.lastMessageMeta = null;
        entry.lastDividerDateKey = null;
        entry.lastGroupRow = null;
        entry.oldestLoadedTs = null;
        entry.newestLoadedTs = null;
        entry.suppressAutoScroll = true;
        entry.skipReadTracking = true;
        entry.scrollAnchor = null;
        beforeMsgs.slice().reverse().forEach(function(m){
            appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
        });
        afterMsgs.forEach(function(m){
            appendMessageBubble(conversationId, m.fromUserId, m.body, m.ts, m.id, m.seen, m.reactions, m.deleted);
        });
        entry.suppressAutoScroll = false;
        entry.skipReadTracking = false;
        var allTs = [];
        beforeMsgs.forEach(function(m){ allTs.push(m.ts); });
        afterMsgs.forEach(function(m){ allTs.push(m.ts); });
        if (allTs.length) {
            entry.oldestLoadedTs = Math.min.apply(null, allTs);
            entry.newestLoadedTs = Math.max.apply(null, allTs);
        }
        entry.hasMoreOlder = beforeMsgs.length === SEEK_HALF;
        entry.hasMoreNewer = afterMsgs.length === SEEK_HALF;
        var targetRow = logEl.querySelector('[data-message-id="' + CSS.escape(targetMessageId) + '"]');
        if (targetRow) {
            requestAnimationFrame(function(){
                targetRow.scrollIntoView({behavior: 'smooth', block: 'center'});
                targetRow.classList.add('jump-highlight');
                setTimeout(function(){ targetRow.classList.remove('jump-highlight'); }, 1500);
            });
        } else {
            logEl.scrollTop = Math.max(0, (logEl.scrollHeight - logEl.clientHeight) / 2);
            showToast('Kh\u00f4ng t\u00ecm th\u1ea5y tin g\u1ed1c (c\u00f3 th\u1ec3 \u0111\u00e3 b\u1ecb xo\u00e1)');
        }
    }).catch(function(err){
        console.warn('seek failed', err);
        showToast('Kh\u00f4ng t\u1ea3i \u0111\u01b0\u1ee3c tin g\u1ed1c: ' + (err.message||''));
    }).finally(function(){ entry._seeking = false; });
}

// Tô màu các cụm "@ai_đó" ngay trong nội dung tin -- thuần hiển thị (không tra cứu/link thật tới
// user nào), giống cách mention hiện màu xanh trong ảnh tham khảo. Link http(s) trong tin KHÔNG kèm
// ảnh -- biến thành <a> bấm được. PHẢI escape HTML trước khi chèn BẤT KỲ thẻ nào (nội dung tin đến
// từ người dùng khác qua mạng) -- chỉ chèn thẻ span/a của CHÍNH mình sau khi đã escape, không bao giờ
// tin trực tiếp text thô vào innerHTML.
function renderMessageText(el, text) {
    var escaped = text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    var html = escaped.replace(/(^|\s)(@[^\s]+)/g, function (whole, pre, mention) {
        return pre + '<span class="mention">' + mention + '</span>';
    });
    html = html.replace(/(^|[\s>])(https?:\/\/[^\s<]+)/g, function (whole, pre, url) {
        var cleanUrl = url.replace(/[.,!?;:'")\]]+$/, '');
        var trailing = url.slice(cleanUrl.length);
        return pre + '<a class="msgLink" href="' + cleanUrl.replace(/"/g, '&quot;') +
            '" target="_blank" rel="noopener noreferrer">' + cleanUrl + '</a>' + trailing;
    });
    el.innerHTML = html;
}

// Nhận dạng tin nhắn CHỈ chứa đúng 1 link (không kèm chữ nào khác) -- kiểu này thay vì hiện chữ
// thô, vẽ 1 thẻ preview nhúng (ảnh + tiêu đề + mô tả + domain) như Messenger/Telegram/Zalo.
//
// CHẤP CẢ link viết tắt không có scheme ("youtube.com/x", "t.me/abc") -- người dùng dán link kiểu này
// rất nhiều, bản cũ chỉ nhận https?:// nên dán "youtube.com" vào là KHÔNG có gì hiện cả (báo lỗi thật).
// Trả về URL ĐÃ chuẩn hoá có https:// để dùng thẳng làm href + tham số ?url= cho hall.
//
// Điều kiện chặt để không biến chữ thường thành link: cả dòng phải là 1 chuỗi không khoảng trắng,
// TLD chỉ gồm chữ cái 2-24 ký tự (loại "3.14", "1.2.3.4", "ok."), có scheme thì chỉ http(s).
// Bản server (LinkPreviewService#soleUrl) GIỮNG ĐÚNG luật này -- 2 bên lệch nhau là colony không enrich.
var SOLE_URL_RE = /^https?:\/\/\S+$/i;
var BARE_DOMAIN_RE = /^(?:[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?\.)+[a-z]{2,24}(?::\d{1,5})?(?:\/\S*)?$/i;

function extractSoleUrl(text) {
    var trimmed = (text || '').trim();
    if (!trimmed || trimmed.length > 2048) return null;
    if (SOLE_URL_RE.test(trimmed)) return trimmed;
    if (BARE_DOMAIN_RE.test(trimmed)) {
        try {
            var abs = new URL('https://' + trimmed);
            // hostname() bắt mỗi nhãn hợp lệ + TLD có dấu chấm; thử cả URL() để chắc không phải chuỗi lạ.
            if (abs.hostname.indexOf('.') > 0) return abs.href;
        } catch (e) {
            return null;
        }
        return null;
    }
    return null;
}

// Domain thuần từ URL (bỏ "www.") -- dùng làm nhãn nhỏ đầu thẻ preview.
function hostnameOf(url) {
    try { return new URL(url).hostname.replace(/^www\./, ''); }
    catch (e) { return url; }
}

// Cache kết quả preview theo URL -- 1 tab chỉ fetch 1 lần cho cùng 1 link, dù link xuất hiện ở nhiều
// tin khác nhau (tránh spam hall mỗi lần load lại lịch sử có cùng link đó).
//
// KHÔNG còn chỉ in-memory như bản đầu: seed từ localStorage (có TTL) lúc khởi động, ghi ngược lại mỗi
// lần fetch xong. Bản in-memory không sống qua reload, nghĩa là MỖI lần mở trang, toàn bộ tin CŨ chưa
// có body.preview lại nở card gây giật layout lần nữa. Đây là đường cho TIN CŨ -- đường chính cho TIN
// MỚI là body.preview (client gắn lúc gửi, colony enrich cho tin nào thiếu), vẽ đồng bộ, không fetch gì.
var LINK_PREVIEW_CACHE_KEY = 'pingoLinkPreviewCache';
var LINK_PREVIEW_CACHE_TTL_MS = 7 * 24 * 3600 * 1000;
var LINK_PREVIEW_CACHE_MAX = 300;

// Đọc cache bền: trả map {url: {meta, at}} -- tự loại phần tử hết TTL. localStorage bị chặn (chế độ
// riêng tư, quota) thì coi như rỗng, KHÔNG được ném lỗi làm sập cả script.
function readLinkPreviewStore() {
    try {
        var raw = localStorage.getItem(LINK_PREVIEW_CACHE_KEY);
        if (!raw) return {};
        var parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object') return {};
        var now = Date.now();
        var live = {};
        Object.keys(parsed).forEach(function (url) {
            var entry = parsed[url];
            if (entry && entry.meta && entry.exp > now) live[url] = entry;
        });
        return live;
    } catch (e) {
        return {};
    }
}

// Ghi cache bền -- cắt còn LINK_PREVIEW_CACHE_MAX phần tử mới nhất (at = thời điểm fetch) để không phình
// localStorage vô hạn. Lỗi quota cũng nuốt luôn: cache chỉ là tối ưu, mất thì fetch lại.
function writeLinkPreviewStore(cache, at) {
    try {
        var now = Date.now();
        var trimmed = {};
        Object.keys(cache)
            .sort(function (a, b) { return (at[b] || 0) - (at[a] || 0); })
            .slice(0, LINK_PREVIEW_CACHE_MAX)
            .forEach(function (url) { trimmed[url] = {meta: cache[url], exp: now + LINK_PREVIEW_CACHE_TTL_MS, at: at[url] || now}; });
        localStorage.setItem(LINK_PREVIEW_CACHE_KEY, JSON.stringify(trimmed));
    } catch (e) {
        // bỏ qua -- xem javadoc
    }
}

// 2 map in-memory song song: {url: meta} (shape KHÔNG đổi so với bản đầu -- meta chỉ có đúng 4 field
// title/description/image/domain, nên gắn thẳng vào body.preview gửi lên server là sạch sẽ) và {url:
// timestampFetch} riêng cho việc cắt TTL. Gộp `at` vào trong meta sẽ rò rỉ field thừa xuống DB body.
var linkPreviewCache = {};
var linkPreviewCacheAt = {};
(function seedLinkPreviewCache() {
    var store = readLinkPreviewStore();
    Object.keys(store).forEach(function (url) {
        linkPreviewCache[url] = store[url].meta;
        linkPreviewCacheAt[url] = store[url].at || 0;
    });
})();

// Tự fetch HTML của 1 trang bất kỳ từ trình duyệt BỊ CHẶN bởi CORS (trang khác origin) -- phải qua
// hall GET /link-preview (backend tự fetch + parse og:, không phụ thuộc bên thứ 3 nào).
function fetchLinkPreviewMeta(url) {
    if (linkPreviewCache[url]) return Promise.resolve(linkPreviewCache[url]);
    return fetch(HISTORY_API_BASE + '/link-preview?url=' + encodeURIComponent(url))
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (json) {
            // hall LUÔN trả {"data": <preview>} hoặc {"data": null} (xem LinkPreviewRegistry) -- null
            // nghĩa là "không có preview", KHÔNG phải lỗi. Bản cũ phải viết `json.data || json` để đoán
            // shape vì nhánh lỗi trả {"domain":...} trần; giờ chỉ còn 1 dạng.
            var data = json ? json.data : null;
            if (!data) return null;
            var result = {
                title: data.title || null,
                description: data.description || null,
                image: data.image || null,
                domain: data.domain || hostnameOf(url)
            };
            // Không có gì để vẽ (chỉ mỗi domain) thì coi như KHÔNG có preview, đừng cache kết quả vô
            // dụng -- để lần sau còn thử lại, và để caller rơi đúng về nhánh chữ link trần.
            if (!result.title && !result.description && !result.image) return null;
            linkPreviewCache[url] = result;
            linkPreviewCacheAt[url] = Date.now();
            writeLinkPreviewStore(linkPreviewCache, linkPreviewCacheAt);
            return result;
        });
}

// Dựng card preview HOÀN CHỈNH từ metadata đã biết -- dùng cho CẢ 2 đường: vẽ đồng bộ khi body.preview
// có sẵn (tin mới), và tô vào skeleton khi fetch xong (tin cũ). Cùng 1 hàm để 2 đường ra ĐÚNG 1 hình
// dạng/kích thước, không mỗi nơi dựng 1 kiểu rồi lệch nhau.
// meta.image có thể lỗi lúc tải (og:image hết hạn / chặn hotlink -- rất hay gặp với CDN mạng xã hội):
// thay bằng khối chữ domain GIỮ NGUYÊN 150px, KHÔNG remove() -- xoá đi sẽ làm card co lại 150px và
// đẩy mọi tin bên dưới trôi theo, đúng cái bug đang sửa ở đây.
// onImageSettled (tuỳ chọn): gọi lại SAU KHI <img> (nếu có) tải xong/lỗi -- ẢNH LÀ THỨ DUY NHẤT trong
// card còn đổi kích thước sau khi card đã lên DOM (ảnh là replaced element, width:100% CSS không tự
// biết trước tỉ lệ thật cho tới khi decode xong, khác hẳn text/domain đã có kích thước cố định ngay).
// Caller (renderLinkPreview*) dùng đúng callback này để canh lại nút react/xoá (positionMsgActions),
// giống hệt cách onMediaReady đang làm cho ảnh/video đính kèm thường -- không có ảnh thì gọi NGAY vì
// không còn gì phải đợi.
function buildLinkPreviewCard(url, meta, onImageSettled) {
    var card = document.createElement('a');
    card.className = 'linkPreviewCard';
    card.href = url;
    card.target = '_blank';
    card.rel = 'noopener noreferrer';
    if (meta.image) {
        var img = document.createElement('img');
        img.className = 'linkPreviewImage';
        img.src = meta.image;
        img.alt = '';
        img.onload = function () { if (onImageSettled) onImageSettled(); };
        img.onerror = function () {
            var fallback = document.createElement('div');
            fallback.className = 'linkPreviewImageFallback';
            fallback.textContent = meta.domain || hostnameOf(url);
            card.replaceChild(fallback, img);
            if (onImageSettled) onImageSettled();
        };
        card.appendChild(img);
    }
    var info = document.createElement('div');
    info.className = 'linkPreviewInfo';
    var domain = document.createElement('div');
    domain.className = 'linkPreviewDomain';
    domain.textContent = meta.domain || hostnameOf(url);
    info.appendChild(domain);
    if (meta.title) {
        var title = document.createElement('div');
        title.className = 'linkPreviewTitle';
        title.textContent = meta.title;
        info.appendChild(title);
    }
    if (meta.description) {
        var desc = document.createElement('div');
        desc.className = 'linkPreviewDesc';
        desc.textContent = meta.description;
        info.appendChild(desc);
    }
    card.appendChild(info);
    // Không có ảnh -- không còn gì tải bất đồng bộ nữa, gọi callback NGAY (không thì caller đợi mãi).
    if (!meta.image && onImageSettled) onImageSettled();
    return card;
}

// Vẽ card preview ĐỒNG BỘ cho tin ĐÃ CÓ sẵn metadata trong body (tin mới: client gắn lúc gửi, hoặc
// colony đã enrich -- xem ChatSessionManager#enrichLinkPreview). Không fetch, không skeleton -- NHƯNG
// <img> bên trong (nếu meta.image có) vẫn tải bất đồng bộ như mọi ảnh khác, nên vẫn cần onMediaReady
// (xem renderMessageContent) để canh lại nút react/xoá SAU KHI ảnh có kích thước thật, giống hệt cách
// đã làm cho ảnh/video đính kèm thường -- không thì nút vẫn có thể lệch/đè lên card lúc ảnh vừa "nở".
function renderLinkPreviewSync(el, url, meta, onMediaReady) {
    el.appendChild(buildLinkPreviewCard(url, meta, onMediaReady));
}

// Vẽ 1 tin chỉ-toàn-link CHƯA có metadata (tin CŨ gửi trước khi có body.preview): skeleton giữ đúng
// footprint card thật (min-height khớp ảnh 150 + 3 cụm chữ) để phần co/dãn còn lại rất nhỏ, và MỌI
// lần đổi chiều cao đều được bù scrollTop bằng withScrollAnchored() -- không đẩy tin đang xem đi đâu.
// onMediaReady: gọi lại SAU MỖI lần đổi kích thước (skeleton -> card thật/chữ link trần, VÀ sau khi
// <img> trong card thật tải xong) để canh lại nút react/xoá (positionMsgActions) -- thiếu bước này là
// đúng nguyên nhân nút bị đè lên nội dung khi skeleton (cao cố định, rộng gần như co lại bằng 0 vì
// %-width bên trong chưa có gì để tính) đổi thành card/chữ thật (kích thước khác hẳn).
function renderLinkPreview(el, url, onMediaReady) {
    var card = document.createElement('a');
    card.className = 'linkPreviewCard skeleton';
    card.href = url;
    card.target = '_blank';
    card.rel = 'noopener noreferrer';
    var skelImg = document.createElement('div');
    skelImg.className = 'linkPreviewSkeletonImage';
    card.appendChild(skelImg);
    var skelLines = document.createElement('div');
    skelLines.className = 'linkPreviewSkeletonLines';
    ['b1', 'b2', 'b3'].forEach(function (cls) {
        var bar = document.createElement('div');
        bar.className = 'linkPreviewSkeletonBar ' + cls;
        skelLines.appendChild(bar);
    });
    card.appendChild(skelLines);
    el.appendChild(card);

    // Bọc mọi lần đổi DOM bên dưới qua withScrollAnchored: đo chiều cao bubble TRƯỚC và SAU, chênh lệch
    // bao nhiêu thì cộng bấy nhiêu vào scrollTop của log nếu bubble nằm PHÍA TRÊN đỉnh khung nhìn
    // (xem hàm đó). Không có lớp này thì skeleton -> card thật (hoặc -> chữ link trần khi fetch fail)
    // vẫn đẩy nội dung đang đọc trôi đi, dù skeleton đã gần đúng cỡ.
    var row = el.closest('.bubble-row');
    var logEl = row ? row.closest('.conv-log') : null;

    var toPlainText = function () {
        withScrollAnchored(row, logEl, function () {
            el.classList.remove('media-only');
            // Bỏ width cố định đã gán cho card preview (xem renderMessageContent) -- chữ link trần tự
            // co theo nội dung như mọi tin chữ khác, không giữ nguyên khổ rộng 320px/68% của card.
            el.style.width = '';
            el.innerHTML = '';
            renderMessageText(el, url);
        });
        // Chữ link trần lên xong là đã ĐÚNG kích thước cuối (text tĩnh, không còn gì tải thêm) -- canh
        // lại nút NGAY, không đợi gì cả.
        if (onMediaReady) onMediaReady();
    };

    fetchLinkPreviewMeta(url).then(function (meta) {
        // fetchLinkPreviewMeta trả null khi trang không khai báo og: HOẶC lỗi mạng -- trong cả 2
        // trường hợp đều THẲNG THẮN bỏ hẳn thẻ preview (không để khung skeleton treo mãi ở đó), trở về
        // đúng cách vẽ tin chữ link bình thường (bấm được, chỉ không có thẻ nhúng).
        if (!meta) { toPlainText(); return; }
        withScrollAnchored(row, logEl, function () {
            el.replaceChild(buildLinkPreviewCard(url, meta, onMediaReady), card);
        });
        // Card thật (chữ domain/tiêu đề/mô tả) đã có kích thước ổn định ngay -- canh lại 1 lần ở đây;
        // buildLinkPreviewCard sẽ tự gọi onMediaReady LẦN NỮA sau khi <img> bên trong tải xong (nếu có
        // ảnh), đúng lúc card "nở" thêm lần cuối.
        if (onMediaReady) onMediaReady();
    }).catch(function (err) {
        console.warn('không tải được preview cho link', url, err);
        toPlainText();
    });
}

// Bù scrollTop khi 1 bubble ĐỔI CHIỀU CAO (link preview tô vào skeleton, hoặc co về chữ link trần) --
// `.conv-log { overflow-anchor: none }` (xem CSS) đã chủ động tắt cơ chế tự bù của trình duyệt, nên
// phần này JS PHẢI tự làm, giống hệt việc prependOlderMessages/onMediaReady đang làm cho ảnh/video.
// CHỈ bù khi bubble nằm hẳn PHÍA TRÊN đỉnh khung nhìn: đó mới là trường hợp nội dung đang đọc bị đẩy
// trôi. Bubble đang hiện trong khung nhìn thì để nguyên -- người dùng thấy card tự "nở" ngay trước mắt,
// đúng như các app khác; bubble nằm dưới khung nhìn thì càng không liên quan.
function withScrollAnchored(row, logEl, mutate) {
    if (!row || !logEl) { mutate(); return; }
    var before = row.offsetHeight;
    mutate();
    var delta = row.offsetHeight - before;
    if (delta === 0) return;
    var logRect = logEl.getBoundingClientRect();
    var rowRect = row.getBoundingClientRect();
    if (rowRect.bottom <= logRect.top + 1) logEl.scrollTop += delta;
}

// Popup xem ảnh/video full-size (tạo 1 LẦN, tái dùng cho mọi lần bấm -- giống #reactionPicker) --
// KHÔNG nhúng <video> thật ngay trong bong bóng nữa (bé, dễ bấm nhầm giữa cuộn trang/mở popup, dở
// hơn hẳn 1 "trình xem" đàng hoàng) -- media thật (ảnh gốc, hoặc <video controls autoplay>) chỉ dựng
// lúc THẬT SỰ mở popup, xem openMediaLightbox.
function ensureMediaLightbox() {
    var lightbox = document.getElementById('mediaLightbox');
    if (lightbox) return lightbox;
    lightbox = document.createElement('div');
    lightbox.id = 'mediaLightbox';
    lightbox.innerHTML =
        '<button type="button" class="lightboxClose" title="Đóng (Esc)">' + ICON.close + '</button>' +
        '<button type="button" class="lightboxNav lightboxPrev" title="Ảnh/video trước (←)">' + ICON.chevronLeft + '</button>' +
        '<button type="button" class="lightboxNav lightboxNext" title="Ảnh/video sau (→)">' + ICON.chevronRight + '</button>' +
        '<div class="lightboxContent"></div>' +
        '<span class="lightboxCounter"></span>';
    lightbox.querySelector('.lightboxClose').onclick = function (e) { e.stopPropagation(); closeMediaLightbox(); };
    lightbox.querySelector('.lightboxPrev').onclick = function (e) { e.stopPropagation(); lightboxGoTo(-1); };
    lightbox.querySelector('.lightboxNext').onclick = function (e) { e.stopPropagation(); lightboxGoTo(1); };
    // Bấm ra NGOÀI media (đúng vào nền tối) mới đóng -- bấm trúng chính ảnh/video (video còn có
    // controls riêng cần thao tác) không được vô tình đóng mất popup đang xem.
    lightbox.onclick = function (e) { if (e.target === lightbox) closeMediaLightbox(); };
    document.body.appendChild(lightbox);
    return lightbox;
}

// Danh sách file của tin ĐANG MỞ trong lightbox + vị trí hiện tại -- cho phép bấm mũi tên/phím ← →
// xem lần lượt hết các file trong CÙNG 1 tin nhiều file (xem uploadAndSendFiles/getMessageFiles).
// Tin chỉ có 1 file thì mảng này chỉ có đúng 1 phần tử, nút prev/next tự ẩn (renderLightboxCurrent).
var lightboxFiles = [];
var lightboxIndex = 0;

function openMediaLightbox(files, startIndex) {
    lightboxFiles = files;
    lightboxIndex = startIndex || 0;
    ensureMediaLightbox().classList.add('show');
    document.body.style.overflow = 'hidden'; // chặn cuộn nền trong lúc đang xem full-size
    renderLightboxCurrent();
}

function renderLightboxCurrent() {
    var lightbox = ensureMediaLightbox();
    var content = lightbox.querySelector('.lightboxContent');
    content.innerHTML = '';
    var f = lightboxFiles[lightboxIndex];
    var isVideo = (f.fileMime || '').indexOf('video/') === 0;
    var media = document.createElement(isVideo ? 'video' : 'img');
    media.className = 'lightboxMedia';
    // Chừa sẵn khung ĐÚNG PIXEL trước khi ảnh/video thật tải xong (xem fitWithinBox -- gán thẳng
    // width/height bằng pixel tuyệt đối, KHÔNG dùng CSS aspect-ratio, cùng lý do đã đổi ở
    // renderMessageContent) -- không thì popup mở ra RỖNG/co lại rất nhỏ rồi mới "nẩy" bung to đúng
    // kích cỡ lúc ảnh/video tải xong. 92vw/88vh đọc TRỰC TIẾP từ viewport hiện tại (khớp đúng giới hạn
    // max-width/max-height khai trong CSS .lightboxMedia) vì đây là giới hạn theo % viewport, không
    // phải hằng số cố định như trong bong bóng chat.
    if (f.width && f.height) {
        var maxW = window.innerWidth * 0.92;
        var maxH = window.innerHeight * 0.88;
        var fitted = fitWithinBox(f.width, f.height, maxW, maxH);
        media.style.width = fitted.width + 'px';
        media.style.height = fitted.height + 'px';
    }
    media.src = f.fileUrl;
    if (isVideo) {
        media.controls = true;
        media.autoplay = true;
        if (f.fileId) media.poster = FILE_SERVER_BASE + '/v2/api/thumbnail?id=' + encodeURIComponent(f.fileId) + '&size=400x400';
    } else {
        media.alt = '';
    }
    content.appendChild(media);
    var multi = lightboxFiles.length > 1;
    lightbox.querySelector('.lightboxPrev').hidden = !multi;
    lightbox.querySelector('.lightboxNext').hidden = !multi;
    lightbox.querySelector('.lightboxPrev').disabled = (lightboxIndex === 0);
    lightbox.querySelector('.lightboxNext').disabled = (lightboxIndex === lightboxFiles.length - 1);
    var counterEl = lightbox.querySelector('.lightboxCounter');
    counterEl.hidden = !multi;
    counterEl.textContent = (lightboxIndex + 1) + ' / ' + lightboxFiles.length;
}

function lightboxGoTo(delta) {
    var newIndex = lightboxIndex + delta;
    if (newIndex < 0 || newIndex >= lightboxFiles.length) return;
    var lightbox = ensureMediaLightbox();
    var vid = lightbox.querySelector('.lightboxContent video');
    if (vid) vid.pause(); // dừng video ĐANG XEM trước khi chuyển sang file khác, tránh phát tiếng ngầm
    lightboxIndex = newIndex;
    renderLightboxCurrent();
}

function closeMediaLightbox() {
    var lightbox = document.getElementById('mediaLightbox');
    if (!lightbox || !lightbox.classList.contains('show')) return;
    lightbox.classList.remove('show');
    var content = lightbox.querySelector('.lightboxContent');
    var vid = content.querySelector('video');
    if (vid) vid.pause(); // dừng video TRƯỚC KHI dọn DOM -- không thì tiếp tục phát tiếng ngầm dù popup đã ẩn
    content.innerHTML = '';
    document.body.style.overflow = '';
    lightboxFiles = [];
}
document.addEventListener('keydown', function (e) {
    var lightbox = document.getElementById('mediaLightbox');
    if (!lightbox || !lightbox.classList.contains('show')) return;
    if (e.key === 'Escape') closeMediaLightbox();
    else if (e.key === 'ArrowLeft') lightboxGoTo(-1);
    else if (e.key === 'ArrowRight') lightboxGoTo(1);
});

function posterUrlFor(f) {
    var isVideo = (f.fileMime || '').indexOf('video/') === 0;
    return isVideo && f.fileId ? FILE_SERVER_BASE + '/v2/api/thumbnail?id=' + encodeURIComponent(f.fileId) + '&size=400x400' : null;
}

// Tính ĐÚNG width/height bằng PIXEL (không phải chỉ tỷ lệ) sẽ hiện ra sau khi scale kích thước thật
// (w×h) vừa khít trong khung giới hạn maxW×maxH, giữ nguyên tỷ lệ, KHÔNG phóng to quá kích thước gốc
// (scale tối đa = 1). Dùng số PIXEL TUYỆT ĐỐI này gán thẳng vào style.width/height của <img> (KHÔNG
// dùng CSS aspect-ratio nữa) -- đây là kỹ thuật y hệt thuộc tính width/height kinh điển của HTML
// <img> (chuẩn từ những ngày đầu web, luôn được mọi engine hỗ trợ nhất quán 100%, không phụ thuộc
// cách mỗi trình duyệt/phiên bản diễn giải riêng thuộc tính CSS aspect-ratio cho phần tử "replaced" --
// đã thử aspect-ratio trước đó vẫn còn báo bị "nẩy" ở máy thật dù test riêng không tái hiện được, nên
// chuyển hẳn sang cách chắc chắn tuyệt đối này để loại trừ hoàn toàn nghi ngờ).
function fitWithinBox(w, h, maxW, maxH) {
    var scale = Math.min(maxW / w, maxH / h, 1);
    return {width: Math.max(1, Math.round(w * scale)), height: Math.max(1, Math.round(h * scale))};
}
var MSG_MEDIA_MAX_W = 280;
var MSG_MEDIA_MAX_H = 320;

// Vẽ nội dung 1 tin vào .bubble -- ảnh/video (1 file: body.fileUrl shape cũ HOẶC body.files[0]; NHIỀU
// file gộp 1 tin: body.files, xem uploadAndSendFiles/getMessageFiles) hoặc chữ thường (mọi tin cũ/
// không đính kèm gì). onMediaReady (tuỳ chọn) gọi lại SAU KHI (TẤT CẢ, nếu nhiều file) ảnh/video tải
// xong kích thước thật -- cần cho bong bóng DM canh lại nút react (xem buildDmBubbleRow).
function renderMessageContent(el, body, onMediaReady) {
    el.innerHTML = '';
    var files = getMessageFiles(body);
    if (files.length === 1) {
        var f = files[0];
        var isVideo = (f.fileMime || '').indexOf('video/') === 0;
        // Ảnh đại diện video (khung hình giây thứ 5, xem get-thumbnail.lua) -- video trong bong bóng
        // giờ CHỈ hiện đúng cái này (1 <img>, không phải <video> thật) + nút ▶ nổi giữa, xem CSS
        // .playOverlay. Nhờ vậy dùng LẠI được nguyên logic tải ảnh (onload luôn chắc chắn fire) --
        // không còn cần "tự preload poster bằng 1 Image() riêng" như trước nữa (đó là workaround
        // riêng cho việc <video poster> không tự tải metadata, giờ không nhúng <video> nữa thì hết bug).
        var posterUrl = posterUrlFor(f);
        var wrap = document.createElement('div');
        wrap.className = 'msg-media-wrap';
        var thumb = document.createElement('img');
        thumb.className = 'msg-media';
        // Biết trước kích thước thật (xem getMediaDimensions/uploadOneFile) -- gán THẲNG width/height
        // bằng PIXEL tuyệt đối (xem fitWithinBox), KHÔNG phải CSS aspect-ratio -- chừa sẵn ĐÚNG khung
        // ngay lập tức, không đợi poster video/ảnh tải xong mới biết cao rộng bao nhiêu (trước đây
        // <img> chưa tải xong cao 0px, khiến cả bong bóng xẹp lại và nút ▶ trồi ra ngoài lơ lửng xấu
        // xí, xem CSS .msg-media). Tin CŨ (gửi từ trước khi có width/height) rơi về min-width/height
        // cố định trong CSS thay vì hoàn toàn không có gì.
        if (f.width && f.height) {
            var fitted = fitWithinBox(f.width, f.height, MSG_MEDIA_MAX_W, MSG_MEDIA_MAX_H);
            thumb.style.width = fitted.width + 'px';
            thumb.style.height = fitted.height + 'px';
        }
        thumb.src = isVideo ? (posterUrl || f.fileUrl) : f.fileUrl;
        thumb.alt = f.fileName || (isVideo ? 'video' : 'ảnh');
        var markLoaded = function () { thumb.classList.add('loaded'); };
        thumb.onload = function () { markLoaded(); if (onMediaReady) onMediaReady(); };
        thumb.onerror = function () { markLoaded(); if (onMediaReady) onMediaReady(); };
        wrap.appendChild(thumb);
        if (isVideo) {
            var playBtn = document.createElement('span');
            playBtn.className = 'playOverlay';
            playBtn.innerHTML = ICON.play;
            wrap.appendChild(playBtn);
        }
        // Bấm vào (ảnh HAY video) đều mở popup xem full-size riêng -- xem openMediaLightbox.
        wrap.onclick = function (e) {
            e.stopPropagation();
            openMediaLightbox(files, 0);
        };
        el.appendChild(wrap);
        // media-only (không kèm chữ) -- bỏ nền/đệm của bubble, để ảnh/video tự nó là "bong bóng"
        // (đúng kiểu Messenger/Zalo hiện ảnh, không có khối màu bao quanh vô nghĩa).
        el.classList.toggle('media-only', !body.message);
        // Ảnh/video kèm chú thích (tuỳ chọn, giống Messenger/Zalo cho gõ thêm vài chữ khi gửi ảnh).
        if (body.message) {
            var caption = document.createElement('div');
            caption.className = 'msg-caption';
            renderMessageText(caption, body.message);
            el.appendChild(caption);
        }
    } else if (files.length > 1) {
        // NHIỀU file gộp 1 tin -- dạng lưới "album" kiểu Telegram/Messenger: tối đa 4 ô, dư ra bao
        // nhiêu gộp vào chữ "+N" đè lên ô cuối cùng (xem CSS .msg-media-grid-more). Bấm ô nào cũng mở
        // lightbox ĐÚNG tại file đó, có thể lướt tiếp qua các file còn lại (kể cả những cái bị gộp
        // vào "+N") bằng nút prev/next, xem openMediaLightbox.
        var VISIBLE_CAP = 4;
        var visibleCount = Math.min(files.length, VISIBLE_CAP);
        var overflowCount = files.length - visibleCount;
        var grid = document.createElement('div');
        grid.className = 'msg-media-grid';
        var loadedCount = 0;
        var onTileReady = function () {
            loadedCount++;
            if (onMediaReady && loadedCount === visibleCount) onMediaReady();
        };
        for (var i = 0; i < visibleCount; i++) {
            (function (i) {
                var gf = files[i];
                var gIsVideo = (gf.fileMime || '').indexOf('video/') === 0;
                var gPoster = posterUrlFor(gf);
                var item = document.createElement('div');
                item.className = 'msg-media-grid-item';
                // Lẻ số ô hiện + KHÔNG có file nào bị che (không overflow) -- ô cuối dãn hết hàng cho
                // đỡ trống 1 nửa (vd đúng 3 ảnh: 2 ô hàng đầu + 1 ô cuối rộng cả hàng).
                if (overflowCount === 0 && visibleCount % 2 === 1 && i === visibleCount - 1) {
                    item.classList.add('spanFull');
                }
                var img = document.createElement('img');
                img.src = gIsVideo ? (gPoster || gf.fileUrl) : gf.fileUrl;
                img.alt = gf.fileName || (gIsVideo ? 'video' : 'ảnh');
                img.onload = onTileReady;
                img.onerror = onTileReady;
                item.appendChild(img);
                if (gIsVideo) {
                    var pb = document.createElement('span');
                    pb.className = 'playOverlay';
                    pb.innerHTML = ICON.play;
                    item.appendChild(pb);
                }
                if (i === visibleCount - 1 && overflowCount > 0) {
                    var more = document.createElement('span');
                    more.className = 'msg-media-grid-more';
                    more.textContent = '+' + overflowCount;
                    item.appendChild(more);
                }
                item.onclick = function (e) {
                    e.stopPropagation();
                    openMediaLightbox(files, i);
                };
                grid.appendChild(item);
            })(i);
        }
        el.appendChild(grid);
        el.classList.toggle('media-only', !body.message);
        if (body.message) {
            var groupCaption = document.createElement('div');
            groupCaption.className = 'msg-caption';
            renderMessageText(groupCaption, body.message);
            el.appendChild(groupCaption);
        }
    } else {
        var soleUrl = extractSoleUrl(messageBodyText(body));
        if (soleUrl) {
            // Tin CHỈ chứa đúng 1 link, không kèm chữ nào khác -- hiện dạng thẻ nhúng preview
            // (ảnh + tiêu đề + mô tả + domain) như Messenger/Telegram, KHÔNG phải chữ link trần.
            // Bỏ luôn cả nền/đệm bubble (media-only) như ảnh/video -- thẻ card tự lo phần nhìn.
            el.classList.add('media-only');
            // Width cố định cho card preview (px, KHÔNG phải %) được gán ở positionMsgActions -- LÚC
            // NÀY (renderMessageContent) row còn CHƯA gắn vào DOM (buildDmBubbleRow build xong mới trả
            // về cho caller appendChild), .bubble-col chưa có containing block thật để đo, xem chú
            // thích chi tiết ở positionMsgActions.
            var stored = body && body.preview;
            if (stored && (stored.title || stored.description || stored.image)) {
                // Metadata ĐÃ CÓ SẴN trong body: client gắn lúc gửi (pha compose, xem sendMsg) hoặc
                // colony enrich sau khi persist (xem ChatSessionManager#enrichLinkPreview). Vẽ ĐỒNG BỘ
                // -- không fetch, không skeleton -- nên card lên DOM đã đúng kích thước cuối cùng,
                // không bao giờ "nở" sau mà đẩy các tin khác đi. Đây chính là cách các app khác tránh
                // giật layout: biết trước hình dạng cuối cùng thay vì fetch lúc render.
                // "không bao giờ nở sau" chỉ đúng cho phần CHỮ (đã biết trước) -- <img> preview (nếu
                // meta.image có) vẫn tải bất đồng bộ như mọi ảnh khác, vẫn cần onMediaReady để canh lại
                // nút react/xoá sau khi ảnh có kích thước thật (xem renderLinkPreviewSync).
                renderLinkPreviewSync(el, soleUrl, stored, onMediaReady);
            } else {
                // TIN CŨ (gửi trước khi có body.preview) mới phải fetch lúc render -- có skeleton giữ
                // chỗ + bù scrollTop để phần co/dãn còn lại không đẩy tin đang xem, xem renderLinkPreview.
                renderLinkPreview(el, soleUrl, onMediaReady);
            }
        } else {
            el.classList.remove('media-only');
            renderMessageText(el, messageBodyText(body));
        }
    }
}
