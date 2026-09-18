// ===== Popup debug "Cây MLS" (nút "Xem sơ đồ" ở panel Info, xem infoE2eTreeRow trong index.html +
// javadoc updateInfoPanel trong sidebar-conversations.js) =====
// CHỈ ĐỌC state MLS cục bộ đã có sẵn (e2eLoadGroup, mls-crypto.js) rồi tự vẽ lại thành sơ đồ cây nhị
// phân bằng SVG -- không tự join/tạo/sửa gì cả, an toàn để mở bất cứ lúc nào (kể cả khi E2E đang lỗi,
// đúng lúc cần soi để debug). Chỉ hiện thông tin CÔNG KHAI vốn đã nằm trong ratchet tree (mọi thành viên
// trong nhóm đều thấy được các trường này qua GroupInfo/Welcome bình thường -- signaturePublicKey/
// hpkePublicKey của leaf, parentHash của node nhánh): KHÔNG có khoá riêng (private key) nào bị lộ ra đây.

// ===== Tree math -- CHÉP NGUYÊN thuật toán "flat array binary tree" của ts-mls (vendor/mls.js, hàm nội
// bộ không export ra API công khai nên phải tự chép lại, xem package ts-mls@1.6.4 dist/src/treemath.js)
// để tự suy layout (node nào là con trái/phải của node nào) từ CHÍNH mảng ratchetTree phẳng, không cần
// gọi lại thư viện. Chỉ cần đúng 4 hàm log2/level/nodeWidth/root/left/right để DUYỆT TỪ GỐC XUỐNG LÁ khi
// vẽ -- không cần hàm parent() (duyệt ngược lên) vì không dùng tới ở đây. =====
function mlsTreeLog2(x) {
    if (x === 0) return 0;
    var k = 0;
    while ((x >> k) > 0) k++;
    return k - 1;
}
function mlsTreeLevel(nodeIndex) {
    if ((nodeIndex & 0x01) === 0) return 0;
    var k = 0;
    while (((nodeIndex >> k) & 0x01) === 1) k++;
    return k;
}
function mlsTreeNodeWidth(leafWidth) { return leafWidth === 0 ? 0 : 2 * (leafWidth - 1) + 1; }
function mlsTreeRoot(leafWidth) { var w = mlsTreeNodeWidth(leafWidth); return (1 << mlsTreeLog2(w)) - 1; }
function mlsTreeLeft(nodeIndex) { var k = mlsTreeLevel(nodeIndex); return nodeIndex ^ (0x01 << (k - 1)); }
function mlsTreeRight(nodeIndex) { var k = mlsTreeLevel(nodeIndex); return nodeIndex ^ (0x03 << (k - 1)); }

function mlsTreeEscapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
        return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
    });
}
function mlsTreeHex(bytes) {
    if (!bytes || !bytes.length) return '';
    return Array.from(bytes).map(function (b) { return b.toString(16).padStart(2, '0'); }).join('');
}
// Vân tay ngắn (4 byte đầu) đủ để PHÂN BIỆT bằng mắt giữa các khoá khác nhau lúc debug, không cần in
// nguyên khoá (32+ byte) choán hết ô -- xem đủ khi hover (title="..." trên cả node, thêm ở renderNodeBox).
function mlsTreeFingerprint(bytes) {
    if (!bytes || !bytes.length) return '?';
    return mlsTreeHex(bytes.slice(0, 4)) + '…';
}

// Tự suy toạ độ x/y cho MỌI node (kể cả node blank/undefined -- vẫn cần vẽ ô "trống" đúng chỗ trong
// cây) bằng cách duyệt ĐỆ QUY từ gốc xuống lá: x của node nhánh = trung điểm x(trái)/x(phải), x của lá
// = thứ tự lá * khoảng cách cố định. Duyệt từ gốc đảm bảo phủ ĐÚNG mọi node thật sự thuộc cây (mảng
// ratchetTree có thể dài hơn nodeWidth thật 1-2 ô rỗng cuối do cách thư viện cấp phát -- KHÔNG duyệt hết
// theo length mà chỉ theo cấu trúc cây thật từ root() tính từ leafCount).
var MLS_TREE_LEAF_SPACING = 156;
var MLS_TREE_LEVEL_HEIGHT = 100;
var MLS_TREE_LEAF_BOX_W = 138;
var MLS_TREE_LEAF_BOX_H = 78;
var MLS_TREE_PARENT_BOX_W = 96;
var MLS_TREE_PARENT_BOX_H = 36;
function mlsTreeComputeLayout(ratchetTreeLength) {
    var leafCount = (ratchetTreeLength + 1) / 2;
    var rootIdx = mlsTreeRoot(leafCount);
    var positions = {}; // nodeIndex -> {x, level}
    function computeX(nodeIndex) {
        var cached = positions[nodeIndex];
        if (cached) return cached.x;
        var lvl = mlsTreeLevel(nodeIndex);
        var x;
        if (lvl === 0) {
            x = (nodeIndex / 2) * MLS_TREE_LEAF_SPACING + MLS_TREE_LEAF_SPACING / 2;
        } else {
            x = (computeX(mlsTreeLeft(nodeIndex)) + computeX(mlsTreeRight(nodeIndex))) / 2;
        }
        positions[nodeIndex] = {x: x, level: lvl};
        return x;
    }
    computeX(rootIdx);
    return {positions: positions, leafCount: leafCount, rootIdx: rootIdx, maxLevel: mlsTreeLevel(rootIdx)};
}

// 1 ô trong sơ đồ -- <foreignObject> chứa HTML thường (tự xuống dòng/co giãn theo nội dung) thay vì tự
// đo độ rộng từng dòng chữ bằng canvas.measureText, đơn giản hơn nhiều cho 1 popup debug không cần in ấn.
function mlsTreeRenderNodeBox(cx, cy, isLeaf, node, isMine) {
    var w = isLeaf ? MLS_TREE_LEAF_BOX_W : MLS_TREE_PARENT_BOX_W;
    var h = isLeaf ? MLS_TREE_LEAF_BOX_H : MLS_TREE_PARENT_BOX_H;
    var x = cx - w / 2, y = cy - h / 2;
    var blank = !node;
    var fill = blank ? 'transparent' : (isLeaf ? 'var(--surface)' : 'var(--accent-soft)');
    var stroke = isMine ? 'var(--accent)' : 'var(--line)';
    var dashAttr = blank ? ' stroke-dasharray="4 3"' : '';
    var shadowAttr = blank ? '' : ' filter="url(#mlsTreeShadow)"';
    var titleParts = [];
    var contentHtml;
    if (blank) {
        contentHtml = '<div class="mlsTreeNodeBox mlsTreeMuted" style="display:flex;align-items:center;justify-content:center;height:100%">' + (isLeaf ? 'trống' : '—') + '</div>';
    } else if (isLeaf) {
        var uid = e2eLeafUserId(node);
        var did = e2eLeafDeviceId(node);
        var name = uid ? mlsTreeEscapeHtml(typeof displayName === 'function' ? displayName(uid) : uid) : mlsTreeEscapeHtml(e2eLeafIdentity(node) || '?');
        var sigFp = mlsTreeFingerprint(node.leaf.signaturePublicKey);
        var hpkeFp = mlsTreeFingerprint(node.leaf.hpkePublicKey);
        titleParts.push('sig: ' + mlsTreeHex(node.leaf.signaturePublicKey));
        titleParts.push('hpke: ' + mlsTreeHex(node.leaf.hpkePublicKey));
        contentHtml = '<div class="mlsTreeNodeBox' + (isMine ? ' mine' : '') + '">' +
            '<div class="mlsTreeNodeName"><b>' + name + '</b>' + (isMine ? ' <span class="mlsTreeStar">★</span>' : '') + '</div>' +
            '<div class="mlsTreeMuted">@' + mlsTreeEscapeHtml(did || '?') + '</div>' +
            '<div class="mlsTreeMuted">sig ' + sigFp + '</div>' +
            '<div class="mlsTreeMuted">hpke ' + hpkeFp + '</div>' +
            '</div>';
    } else {
        var hasHash = node.parent.parentHash && node.parent.parentHash.length > 0;
        var phLabel = hasHash ? ('ph ' + mlsTreeFingerprint(node.parent.parentHash)) : '(chưa có hash)';
        titleParts.push('parentHash: ' + (hasHash ? mlsTreeHex(node.parent.parentHash) : '(rỗng -- chưa có UpdatePath nào đi qua node này)'));
        titleParts.push('unmergedLeaves: ' + ((node.parent.unmergedLeaves || []).length ? node.parent.unmergedLeaves.join(', ') : '(không có)'));
        contentHtml = '<div class="mlsTreeNodeBox mlsTreeMuted" style="display:flex;align-items:center;justify-content:center;height:100%;text-align:center">' + phLabel + '</div>';
    }
    var titleEl = titleParts.length ? '<title>' + mlsTreeEscapeHtml(titleParts.join('\n')) + '</title>' : '';
    return '<g>' + titleEl +
        '<rect x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '" rx="9" fill="' + fill + '" stroke="' + stroke + '" stroke-width="' + (isMine ? 2 : 1.2) + '"' + dashAttr + shadowAttr + ' />' +
        '<foreignObject x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '">' +
        '<div xmlns="http://www.w3.org/1999/xhtml" style="width:100%;height:100%;overflow:hidden">' + contentHtml + '</div>' +
        '</foreignObject>' +
        '</g>';
}

function mlsTreeRenderSvg(ratchetTree, myLeafNodeIndex) {
    var layout = mlsTreeComputeLayout(ratchetTree.length);
    var padX = 24;
    // padY PHẢI >= nửa chiều cao hộp lá (BUG THẬT đã tự thấy: để 30 -- nhỏ hơn nửa hộp lá 78/2=39 -- hộp
    // lá ở hàng dưới cùng bị chính viewBox của <svg> CẮT MẤT phần đáy, vì <svg> mặc định overflow:hidden
    // với nội dung tràn ra ngoài kích thước đã khai -- không phải lỗi layout modal/flex bên ngoài, tự đo
    // getBoundingClientRect() xác nhận modal/wrap đã đủ chỗ, chỉ riêng SVG tự khai chiều cao hụt). Dư thêm
    // vài px cho viền/bóng đổ (filter="url(#mlsTreeShadow)", xem defs bên dưới) không bị mất nét.
    var padY = MLS_TREE_LEAF_BOX_H / 2 + 8;
    var svgWidth = layout.leafCount * MLS_TREE_LEAF_SPACING + padX * 2;
    var svgHeight = (layout.maxLevel + 1) * MLS_TREE_LEVEL_HEIGHT + padY * 2;
    function yFor(level) { return svgHeight - padY - level * MLS_TREE_LEVEL_HEIGHT; }
    var lines = [];
    var boxes = [];
    // Duyệt lại TOÀN BỘ node đã có toạ độ (chính là toàn bộ node THẬT của cây, xem javadoc
    // mlsTreeComputeLayout) để vẽ -- không lặp theo ratchetTree.length vì mảng gốc có thể dư vài ô rỗng
    // cuối không thuộc cây thật.
    Object.keys(layout.positions).forEach(function (key) {
        var i = Number(key);
        var pos = layout.positions[i];
        var x = pos.x + padX, y = yFor(pos.level);
        var isLeaf = pos.level === 0;
        if (!isLeaf) {
            var l = mlsTreeLeft(i), r = mlsTreeRight(i);
            var lPos = layout.positions[l], rPos = layout.positions[r];
            lines.push('<line x1="' + x + '" y1="' + y + '" x2="' + (lPos.x + padX) + '" y2="' + yFor(lPos.level) + '" stroke="var(--line)" stroke-width="1.5" />');
            lines.push('<line x1="' + x + '" y1="' + y + '" x2="' + (rPos.x + padX) + '" y2="' + yFor(rPos.level) + '" stroke="var(--line)" stroke-width="1.5" />');
        }
        var node = ratchetTree[i];
        boxes.push(mlsTreeRenderNodeBox(x, y, isLeaf, node, i === myLeafNodeIndex));
    });
    var defs = '<defs><filter id="mlsTreeShadow" x="-40%" y="-40%" width="180%" height="180%">' +
        '<feDropShadow dx="0" dy="1.5" stdDeviation="2" flood-color="#000" flood-opacity=".18" />' +
        '</filter></defs>';
    return '<svg width="' + svgWidth + '" height="' + svgHeight + '" viewBox="0 0 ' + svgWidth + ' ' + svgHeight + '" style="display:block">' +
        defs + lines.join('') + boxes.join('') + '</svg>';
}

function ensureMlsTreeModal() {
    var overlay = document.getElementById('mlsTreeOverlay');
    if (overlay) return overlay;
    overlay = document.createElement('div');
    overlay.id = 'mlsTreeOverlay';
    overlay.innerHTML =
        '<div class="bgPickerModal mlsTreeModal">' +
        '<div class="bgPickerHead"><span>Sơ đồ cây MLS</span><button type="button" id="mlsTreeCloseBtn">✕</button></div>' +
        '<div class="bgPickerBody">' +
        '<div class="mlsTreeInfoGrid" id="mlsTreeInfoGrid"></div>' +
        '<div class="mlsTreeLegend">' +
        '<span><span class="mlsTreeLegendDot" style="background:var(--surface);border:1px solid var(--line)"></span>Thiết bị (lá)</span>' +
        '<span><span class="mlsTreeLegendDot" style="background:var(--accent-soft)"></span>Node nhánh</span>' +
        '<span><span class="mlsTreeLegendDot" style="background:transparent;border:1px dashed var(--ink-faint)"></span>Ô trống</span>' +
        '<span><span class="mlsTreeLegendDot" style="background:var(--surface);border:2px solid var(--accent)"></span>Thiết bị của bạn</span>' +
        '</div>' +
        '<div class="mlsTreeCanvasWrap" id="mlsTreeCanvasWrap"></div>' +
        '</div></div>';
    document.body.appendChild(overlay);
    overlay.querySelector('#mlsTreeCloseBtn').onclick = function () { overlay.classList.remove('show'); };
    // Bấm ra ngoài modal (đúng nền overlay, không phải bấm vào modal rồi nổi bọt lên) thì đóng -- giống
    // mọi overlay khác trong app (xem ensureE2eRecoveryKeyModal).
    overlay.addEventListener('click', function (e) { if (e.target === overlay) overlay.classList.remove('show'); });
    return overlay;
}

function mlsTreeRenderInfoAndCanvas(infoGrid, wrap, state) {
    var gc = state.groupContext;
    var myIdentity = MLS_CRED_PREFIX + myUserId + MLS_CRED_DEVICE_SEP + e2eDeviceId;
    var leaves = e2eLeafNodes(state);
    var myLeaf = leaves.filter(function (x) { return e2eLeafIdentity(x.n) === myIdentity; })[0];
    var leafSlots = (state.ratchetTree.length + 1) / 2;
    var items = [
        ['Epoch', String(gc.epoch)],
        ['Cipher suite', gc.cipherSuite],
        ['Group ID', mlsTreeHex(gc.groupId)],
        ['Tree hash', mlsTreeHex(gc.treeHash)],
        ['Số thiết bị', leaves.length + ' / ' + leafSlots + ' ô'],
        ['Leaf của bạn', myLeaf ? ('#' + (myLeaf.idx / 2)) : 'không tìm thấy (chưa join/đã mất state)']
    ];
    infoGrid.innerHTML = items.map(function (it) {
        var v = mlsTreeEscapeHtml(it[1]);
        return '<div class="mlsTreeInfoItem"><span class="mlsTreeInfoLabel">' + it[0] + '</span><span class="mlsTreeInfoValue" title="' + v + '">' + v + '</span></div>';
    }).join('');
    wrap.innerHTML = mlsTreeRenderSvg(state.ratchetTree, myLeaf ? myLeaf.idx : -1);
}

// Điểm vào DUY NHẤT -- gọi từ nút "Xem sơ đồ" (xem updateInfoPanel, sidebar-conversations.js).
function openMlsTreeModal(conversationId) {
    var overlay = ensureMlsTreeModal();
    var wrap = overlay.querySelector('#mlsTreeCanvasWrap');
    var infoGrid = overlay.querySelector('#mlsTreeInfoGrid');
    infoGrid.innerHTML = '';
    wrap.innerHTML = '<div class="mlsTreeStatus">Đang tải...</div>';
    overlay.classList.add('show');
    if (typeof e2eLoadGroup !== 'function') {
        wrap.innerHTML = '<div class="mlsTreeStatus">E2E chưa sẵn sàng trên thiết bị này.</div>';
        return;
    }
    e2eLoadGroup(conversationId).then(function (state) {
        if (!overlay.classList.contains('show')) return; // đã bấm đóng trong lúc đang tải thì thôi
        if (!state) {
            wrap.innerHTML = '<div class="mlsTreeStatus">Chưa có dữ liệu nhóm MLS cục bộ trên thiết bị này (chưa tham gia xong, hoặc đang chờ welcome).</div>';
            return;
        }
        mlsTreeRenderInfoAndCanvas(infoGrid, wrap, state);
    }).catch(function (err) {
        if (!overlay.classList.contains('show')) return;
        wrap.innerHTML = '<div class="mlsTreeStatus">Lỗi đọc dữ liệu nhóm: ' + mlsTreeEscapeHtml(err.message) + '</div>';
    });
}
