// Server tự kiểm tra lại quyền xoá (so from_user_id thật trong DB) -- đây chỉ là hàng rào phụ phía client, không phải chốt bảo mật thật.
function deleteMessage(conversationId, messageId) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    appConfirm('Xoá tin nhắn này? Không thể hoàn tác.', {title: 'Xoá tin nhắn', confirmText: 'Xoá', cancelText: 'Huỷ', danger: true}).then(function(ok){ if(!ok) return; send({type: 'DELETE', id: messageId, conversationId: conversationId}); });
}

// Dùng chung cho cả tin vừa nhận frame DELETE lẫn tin đã xoá nạp lại từ lịch sử (deleted:true).
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

// Frame DELETE có thể của người khác gửi tới (xem javadoc fan-out bên BackendStreamGateway) -- tìm theo messageId trên toàn #chatMain, kể cả conversation không active.
function handleMessageDeleted(conversationId, messageId) {
    if (!messageId) return;
    var row = document.querySelector('[data-message-id="' + CSS.escape(messageId) + '"]');
    if (row) renderDeletedPlaceholder(row);
    document.querySelectorAll('.reply-quote[data-reply-to-id="' + CSS.escape(messageId) + '"]').forEach(function(q){
        q.classList.add('reply-not-found');
        var sn = q.querySelector('.reply-quote-snippet');
        if (sn) sn.innerText = 'Tin nh\u1eafn \u0111\u00e3 b\u1ecb xo\u00e1';
    });
    // Tin đã xoá thì không còn gì để "đọc" -- bớt khỏi chấm đỏ chưa đọc thay vì giữ nguyên số đếm.
    var entry = conversations[conversationId];
    if (entry && entry.unreadIdSet.delete(messageId)) {
        entry.totalUnreadCount = Math.max(0, (entry.totalUnreadCount || 0) - 1);
        updateJumpBadge(entry);
    }
}

// Server hiểu body rỗng = huỷ reaction, emoji mới = tự thay thế (không cộng dồn) -- xem MessageType#REACTION.
function sendReaction(conversationId, messageId, emoji) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    var mine = reactionsByMessageId[messageId] && reactionsByMessageId[messageId][myUserId];
    var body = mine === emoji ? {} : {emoji: emoji};
    send({type: 'REACTION', id: messageId, conversationId: conversationId, body: body});
}

// Nhận frame REACTION (của mình lẫn người khác) -- giống cách handleMessageDeleted xử lý fan-out.
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

// renderReactions nhận rowEl tuỳ chọn để truyền thẳng khi node vừa tạo còn chưa appendChild vào DOM (querySelector sẽ không tìm ra).
// Tooltip dùng 1 phần tử tái định vị mỗi lần hiện, giống reactionPicker (tránh tạo/xoá DOM liên tục mỗi lần hover).
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
    // Box bị kẹp sát lề màn hình thì lệch khỏi vị trí giữa badge -- mũi tên phải tự bù để luôn trỏ đúng vào badge.
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
    // Tách riêng phần đổi DOM để đo offsetHeight trước/sau -- KHÔNG dùng row.offsetTop vì .conv-log không có position:relative nên nó lệch hệ toạ độ với logEl.scrollTop (đã từng sai y hệt vậy).
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
            // Fallback về ký tự emoji thô cho reaction cũ không khớp bộ icon hiện tại -- không để badge trống trơn.
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
        // Bật has-reactions đổi padding-bottom của bubble (xem CSS) nên bubble có thể phình cao hơn -- đây là lý do cần bù scroll ở dưới.
        if (wrapEl) wrapEl.classList.toggle('has-reactions', Object.keys(byEmoji).length > 0);
    }
    if (!logEl) {
        mutate();
        return;
    }
    // Bù scrollTop gán TỨC THỜI (không animate) -- đây là bù lệch do bubble phình vài px, khác với tin mới thật sự tới (chỗ đó mới nên animate, xem appendMessageBubble).
    var before = row.offsetHeight;
    mutate();
    var delta = row.offsetHeight - before;
    if (delta === 0) return;
    if (entry.stickToBottom) {
        // Gán thẳng scrollHeight thay vì += delta: khi bubble co lại (bỏ reaction), trình duyệt đã tự kéo scrollTop về max mới TRƯỚC dòng này, cộng thêm delta âm sẽ trừ kép và vọt lên quá đà (bug đã gặp).
        logEl.scrollTop = logEl.scrollHeight;
    } else {
        // Chỉ bù khi row không nằm hẳn dưới khung nhìn -- row chưa cuộn tới thì bỏ qua, tránh kéo người dùng lệch khỏi chỗ đang xem.
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
// Badge "Đã chuyển tiếp" -- cùng khuôn buildReplyQuoteEl() ở trên (insertBefore vào đầu .bubble),
// nhưng KHÔNG click-to-jump được (không đảm bảo tin gốc còn tồn tại/mình còn quyền xem conversation
// gốc, khác reply luôn cùng 1 conversation với chính nó).
function buildForwardedBadgeEl(forwardedFrom) {
    var b = document.createElement('div');
    b.className = 'forwarded-badge';
    b.innerHTML = '<i class="fa-solid fa-share-from-square"></i><span></span>';
    b.querySelector('span').innerText = 'Đã chuyển tiếp';
    return b;
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
// highlightTerm (tuỳ chọn) -- xem highlightTermInBubble bên dưới, dùng cho kết quả tìm kiếm (trong 1
// đoạn chat lẫn toàn cục) để tô đúng phần CHỮ khớp trong bubble, không chỉ nháy sáng cả dòng như trước.
function jumpToMessage(conversationId, targetMessageId, targetTs, highlightTerm) {
    var entry = conversations[conversationId];
    if (!entry) return;
    if (typeof selectConversation === 'function' && conversationId !== activeConversationId) {
        selectConversation(conversationId);
        entry = conversations[conversationId];
    }
    // Phải đợi loadHistory vẽ xong hẳn rồi mới seek -- chạy song song thì ai xong sau ghi đè DOM (logEl.innerHTML='') của người xong trước, gây bug scroll/tô sáng sai lung tung (đã gặp thật).
    Promise.resolve(loadHistory(conversationId)).then(function () {
        performJumpToMessage(conversationId, targetMessageId, targetTs, highlightTerm);
    });
}

// Tô đúng đoạn CHỮ khớp {@code term} bên trong .bubble của 1 dòng tin -- chỉ đụng vào TEXT NODE (dùng
// TreeWalker), không đụng innerHTML thô, để không phá markup mention/link đã render sẵn trong bubble
// (renderMessageText). Tự gỡ lại sau 1 lúc, không tô vĩnh viễn.
function highlightTermInBubble(row, term) {
    if (!term) return;
    var bubbleEl = row.querySelector('.bubble');
    if (!bubbleEl) return;
    var lowerTerm = term.toLowerCase();
    var walker = document.createTreeWalker(bubbleEl, NodeFilter.SHOW_TEXT, null);
    var textNodes = [];
    var n;
    while ((n = walker.nextNode())) textNodes.push(n);
    textNodes.forEach(function (node) {
        var text = node.nodeValue;
        var lowerText = text.toLowerCase();
        var idx = lowerText.indexOf(lowerTerm);
        if (idx === -1) return;
        var frag = document.createDocumentFragment();
        var lastIndex = 0;
        while (idx !== -1) {
            frag.appendChild(document.createTextNode(text.slice(lastIndex, idx)));
            var mark = document.createElement('mark');
            mark.className = 'searchHit';
            mark.textContent = text.slice(idx, idx + term.length);
            frag.appendChild(mark);
            lastIndex = idx + term.length;
            idx = lowerText.indexOf(lowerTerm, lastIndex);
        }
        frag.appendChild(document.createTextNode(text.slice(lastIndex)));
        node.parentNode.replaceChild(frag, node);
    });
    setTimeout(function () {
        bubbleEl.querySelectorAll('mark.searchHit').forEach(function (m) { m.replaceWith(document.createTextNode(m.textContent)); });
        bubbleEl.normalize(); // gộp lại các text node liền kề sau khi gỡ <mark>, tránh để DOM vụn
    }, 2500);
}

function performJumpToMessage(conversationId, targetMessageId, targetTs, highlightTerm) {
    var entry = conversations[conversationId];
    if (!entry) return;
    var row = entry.logEl.querySelector('[data-message-id="' + CSS.escape(targetMessageId) + '"]');
    if (row) {
        row.scrollIntoView({behavior: 'smooth', block: 'center'});
        row.classList.add('jump-highlight');
        setTimeout(function(){ row.classList.remove('jump-highlight'); }, 1300);
        highlightTermInBubble(row, highlightTerm);
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
                highlightTermInBubble(targetRow, highlightTerm);
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

// PHẢI escape HTML trước khi chèn bất kỳ thẻ nào -- nội dung tin đến từ người dùng khác qua mạng, không bao giờ tin trực tiếp text thô vào innerHTML.
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

// Regex phải khớp ĐÚNG luật bên server (LinkPreviewService#soleUrl) -- lệch nhau là colony không enrich; chấp cả domain trần không scheme vì user hay dán kiểu đó (bản cũ chỉ nhận https?:// từng bị báo lỗi thật).
var SOLE_URL_RE = /^https?:\/\/\S+$/i;
var BARE_DOMAIN_RE = /^(?:[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?\.)+[a-z]{2,24}(?::\d{1,5})?(?:\/\S*)?$/i;

function extractSoleUrl(text) {
    var trimmed = (text || '').trim();
    if (!trimmed || trimmed.length > 2048) return null;
    if (SOLE_URL_RE.test(trimmed)) return trimmed;
    if (BARE_DOMAIN_RE.test(trimmed)) {
        try {
            var abs = new URL('https://' + trimmed);
            if (abs.hostname.indexOf('.') > 0) return abs.href;
        } catch (e) {
            return null;
        }
        return null;
    }
    return null;
}

function hostnameOf(url) {
    try { return new URL(url).hostname.replace(/^www\./, ''); }
    catch (e) { return url; }
}

// Cache preview theo URL, seed từ localStorage (TTL) lúc khởi động -- để tin CŨ (chưa có body.preview) không phải fetch lại/giật layout mỗi lần reload trang.
var LINK_PREVIEW_CACHE_KEY = 'pingoLinkPreviewCache';
var LINK_PREVIEW_CACHE_TTL_MS = 7 * 24 * 3600 * 1000;
var LINK_PREVIEW_CACHE_MAX = 300;

// localStorage bị chặn (chế độ riêng tư, quota) thì coi như rỗng -- KHÔNG được ném lỗi làm sập cả script.
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

// Cắt còn LINK_PREVIEW_CACHE_MAX phần tử mới nhất để không phình localStorage vô hạn; lỗi quota nuốt luôn vì cache chỉ là tối ưu, mất thì fetch lại.
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
    }
}

// 2 map song song thay vì gộp chung: `at` (timestamp fetch) không được lẫn vào meta vì meta gắn thẳng vào body.preview gửi lên server -- gộp sẽ rò rỉ field thừa xuống DB.
var linkPreviewCache = {};
var linkPreviewCacheAt = {};
(function seedLinkPreviewCache() {
    var store = readLinkPreviewStore();
    Object.keys(store).forEach(function (url) {
        linkPreviewCache[url] = store[url].meta;
        linkPreviewCacheAt[url] = store[url].at || 0;
    });
})();

// Fetch HTML của trang khác origin bị CORS chặn từ trình duyệt -- phải qua hall GET /link-preview (backend tự fetch + parse og:).
function fetchLinkPreviewMeta(url) {
    if (linkPreviewCache[url]) return Promise.resolve(linkPreviewCache[url]);
    return fetch(HISTORY_API_BASE + '/link-preview?url=' + encodeURIComponent(url))
        .then(function (res) {
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return res.json();
        })
        .then(function (json) {
            // hall LUÔN trả {"data": preview|null} (xem LinkPreviewRegistry) -- null nghĩa là "không có preview", KHÔNG phải lỗi.
            var data = json ? json.data : null;
            if (!data) return null;
            var result = {
                title: data.title || null,
                description: data.description || null,
                image: data.image || null,
                domain: data.domain || hostnameOf(url)
            };
            // Không có gì để vẽ (chỉ mỗi domain) thì đừng cache -- để lần sau còn thử lại.
            if (!result.title && !result.description && !result.image) return null;
            linkPreviewCache[url] = result;
            linkPreviewCacheAt[url] = Date.now();
            writeLinkPreviewStore(linkPreviewCache, linkPreviewCacheAt);
            return result;
        });
}

// Dùng chung cho cả vẽ đồng bộ (tin mới có sẵn body.preview) lẫn tô vào skeleton (tin cũ fetch xong) để ra đúng 1 hình dạng.
// meta.image lỗi lúc tải (og:image hết hạn/chặn hotlink) -- thay bằng khối chữ domain GIỮ NGUYÊN 150px, KHÔNG remove(), tránh card co lại kéo tin bên dưới trôi theo (bug đã gặp).
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
    // Không có ảnh thì không còn gì tải bất đồng bộ -- gọi callback NGAY, không caller sẽ đợi mãi.
    if (!meta.image && onImageSettled) onImageSettled();
    return card;
}

// Không fetch/skeleton vì metadata đã có sẵn trong body -- nhưng <img> preview vẫn tải bất đồng bộ nên vẫn cần onMediaReady để canh lại nút react/xoá sau khi ảnh có kích thước thật.
function renderLinkPreviewSync(el, url, meta, onMediaReady) {
    el.appendChild(buildLinkPreviewCard(url, meta, onMediaReady));
}

// Tin CŨ chưa có body.preview: hiện skeleton giữ đúng footprint rồi fetch -- mọi lần đổi kích thước phải gọi lại onMediaReady để canh nút react/xoá, thiếu bước này là nguyên nhân nút bị đè lên nội dung.
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

    // Mọi lần đổi DOM bên dưới bọc qua withScrollAnchored -- không thì skeleton -> card thật vẫn đẩy nội dung đang đọc trôi đi dù skeleton đã gần đúng cỡ.
    var row = el.closest('.bubble-row');
    var logEl = row ? row.closest('.conv-log') : null;

    var toPlainText = function () {
        withScrollAnchored(row, logEl, function () {
            el.classList.remove('media-only');
            // Bỏ width cố định đã gán cho card preview -- chữ link trần tự co theo nội dung như mọi tin chữ khác.
            el.style.width = '';
            el.innerHTML = '';
            renderMessageText(el, url);
        });
        if (onMediaReady) onMediaReady();
    };

    fetchLinkPreviewMeta(url).then(function (meta) {
        // null khi trang không khai báo og: hoặc lỗi mạng -- cả 2 trường hợp đều bỏ hẳn thẻ preview, không để skeleton treo mãi.
        if (!meta) { toPlainText(); return; }
        withScrollAnchored(row, logEl, function () {
            el.replaceChild(buildLinkPreviewCard(url, meta, onMediaReady), card);
        });
        if (onMediaReady) onMediaReady();
    }).catch(function (err) {
        console.warn('không tải được preview cho link', url, err);
        toPlainText();
    });
}

// `.conv-log { overflow-anchor: none }` (CSS) tắt cơ chế tự bù của trình duyệt nên JS phải tự bù -- chỉ bù khi bubble nằm hẳn phía trên đỉnh khung nhìn (nội dung đang đọc bị đẩy trôi), bubble đang hiện trong khung nhìn thì để tự "nở".
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

// Media thật (ảnh gốc / <video controls autoplay>) chỉ dựng lúc THẬT SỰ mở popup (xem openMediaLightbox) -- không nhúng <video> ngay trong bong bóng vì bé, dễ bấm nhầm giữa cuộn trang/mở popup.
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
    // Chỉ bấm đúng nền tối mới đóng -- bấm trúng video (có controls riêng) không được vô tình đóng popup.
    lightbox.onclick = function (e) { if (e.target === lightbox) closeMediaLightbox(); };
    document.body.appendChild(lightbox);
    return lightbox;
}

// File của tin đang mở trong lightbox + vị trí hiện tại, cho phép bấm mũi tên/phím ← → lướt qua các file cùng tin.
var lightboxFiles = [];
var lightboxIndex = 0;

function openMediaLightbox(files, startIndex) {
    lightboxFiles = files;
    lightboxIndex = startIndex || 0;
    ensureMediaLightbox().classList.add('show');
    document.body.style.overflow = 'hidden';
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
    // Chừa sẵn khung đúng pixel trước khi tải xong (xem fitWithinBox) -- 92vw/88vh đọc trực tiếp từ viewport vì giới hạn CSS .lightboxMedia là theo % viewport, không phải hằng số cố định như trong bong bóng chat.
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

// Trả pixel tuyệt đối (không CSS aspect-ratio) để gán thẳng style.width/height -- aspect-ratio từng bị báo "nẩy" thật ở máy khách dù không tái hiện được lúc test, nên chuyển hẳn sang cách chắc chắn này.
function fitWithinBox(w, h, maxW, maxH) {
    var scale = Math.min(maxW / w, maxH / h, 1);
    return {width: Math.max(1, Math.round(w * scale)), height: Math.max(1, Math.round(h * scale))};
}
var MSG_MEDIA_MAX_W = 280;
var MSG_MEDIA_MAX_H = 320;

// onMediaReady (tuỳ chọn) gọi lại sau khi ảnh/video tải xong kích thước thật -- cần cho bong bóng DM canh lại nút react (xem buildDmBubbleRow).
function renderMessageContent(el, body, onMediaReady) {
    el.innerHTML = '';
    var files = getMessageFiles(body);
    if (files.length === 1) {
        var f = files[0];
        var isVideo = (f.fileMime || '').indexOf('video/') === 0;
        // Video trong bong bóng chỉ hiện ảnh đại diện (poster, xem get-thumbnail.lua) + nút ▶ nổi giữa, không nhúng <video> thật -- nhờ vậy dùng lại nguyên logic tải ảnh (onload luôn chắc chắn fire).
        var posterUrl = posterUrlFor(f);
        var wrap = document.createElement('div');
        wrap.className = 'msg-media-wrap';
        var thumb = document.createElement('img');
        thumb.className = 'msg-media';
        // Gán thẳng width/height pixel (xem fitWithinBox) ngay lập tức -- trước đây <img> chưa tải xong cao 0px khiến bong bóng xẹp lại và nút ▶ trồi ra ngoài lơ lửng (bug đã gặp).
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
        wrap.onclick = function (e) {
            e.stopPropagation();
            openMediaLightbox(files, 0);
        };
        el.appendChild(wrap);
        // media-only: bỏ nền/đệm của bubble để ảnh/video tự nó là "bong bóng", kiểu Messenger/Zalo.
        el.classList.toggle('media-only', !body.message);
        if (body.message) {
            var caption = document.createElement('div');
            caption.className = 'msg-caption';
            renderMessageText(caption, body.message);
            el.appendChild(caption);
        }
    } else if (files.length > 1) {
        // Lưới "album" kiểu Telegram/Messenger: tối đa 4 ô, dư ra gộp vào chữ "+N" đè lên ô cuối (xem CSS .msg-media-grid-more).
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
                // Lẻ số ô + không overflow -- ô cuối dãn hết hàng cho đỡ trống 1 nửa.
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
            el.classList.add('media-only');
            // Width cố định cho card preview được gán ở positionMsgActions -- lúc này row còn CHƯA gắn vào DOM nên .bubble-col chưa có containing block thật để đo.
            var stored = body && body.preview;
            if (stored && (stored.title || stored.description || stored.image)) {
                renderLinkPreviewSync(el, soleUrl, stored, onMediaReady);
            } else {
                renderLinkPreview(el, soleUrl, onMediaReady);
            }
        } else {
            el.classList.remove('media-only');
            renderMessageText(el, messageBodyText(body));
        }
    }
}
