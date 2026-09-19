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
// vẽ -- không cần tự cài thêm thuật toán treemath.parent() (đi ngược lên, phức tạp hơn hẳn left/right)
// vì map cha->con đã có sẵn "miễn phí" từ chính lượt duyệt xuôi này (xem parentOf trong
// mlsTreeComputeLayout), dùng để highlight direct path của leaf-của-bạn lên gốc. =====
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
var MLS_TREE_LEAF_SPACING = 168;
var MLS_TREE_LEVEL_HEIGHT = 108;
var MLS_TREE_LEAF_BOX_W = 148;
var MLS_TREE_LEAF_BOX_H = 92;
var MLS_TREE_PARENT_BOX_W = 118;
var MLS_TREE_PARENT_BOX_H = 40;
// HTML chi tiết đầy đủ (khoá KHÔNG rút gọn) cho từng node, đánh index -- dựng lại mỗi lần
// mlsTreeRenderSvg chạy, đọc bởi listener click trên #mlsTreeCanvasWrap (xem ensureMlsTreeModal) để đổ
// vào #mlsTreeDetailPanel khi bấm vào 1 node. Vân tay rút gọn trên chính ô vẽ chỉ đủ NHÌN LƯỚT phân biệt
// -- panel chi tiết mới là nơi xem/copy được nguyên khoá.
var mlsTreeLastDetails = {};
function mlsTreeComputeLayout(ratchetTreeLength) {
    var leafCount = (ratchetTreeLength + 1) / 2;
    var rootIdx = mlsTreeRoot(leafCount);
    var positions = {}; // nodeIndex -> {x, level}
    // parentOf[con] = cha -- dựng SẴN trong lúc duyệt đệ quy từ gốc xuống lá (chỉ thêm 2 dòng vào vòng
    // đệ quy đã có), để sau này highlight direct path (mlsTreeRenderSvg) chỉ cần đi ngược map này thay vì
    // phải tự cài lại thuật toán treemath.parent() (phức tạp hơn hẳn left/right, không cần thiết).
    var parentOf = {};
    function computeX(nodeIndex) {
        var cached = positions[nodeIndex];
        if (cached) return cached.x;
        var lvl = mlsTreeLevel(nodeIndex);
        var x;
        if (lvl === 0) {
            x = (nodeIndex / 2) * MLS_TREE_LEAF_SPACING + MLS_TREE_LEAF_SPACING / 2;
        } else {
            var l = mlsTreeLeft(nodeIndex), r = mlsTreeRight(nodeIndex);
            parentOf[l] = nodeIndex;
            parentOf[r] = nodeIndex;
            x = (computeX(l) + computeX(r)) / 2;
        }
        positions[nodeIndex] = {x: x, level: lvl};
        return x;
    }
    computeX(rootIdx);
    return {positions: positions, parentOf: parentOf, leafCount: leafCount, rootIdx: rootIdx, maxLevel: mlsTreeLevel(rootIdx)};
}

// Vùng "che/lộ" dùng chung cho MỌI loại secret nhạy cảm trên modal này (private key riêng lẫn secret
// dùng chung cả nhóm) -- MẶC ĐỊNH ẨN (chỉ hiện chuỗi che ••••), bấm nút mới lộ giá trị thật. Event
// delegation gắn 1 lần duy nhất trên toàn overlay (xem ensureMlsTreeModal) nên tái dùng ở bất cứ đâu
// trong modal (info grid lẫn detail panel) đều tự động bấm được, không cần đăng ký thêm listener.
// variant "shared" đổi màu cam (--warn-*, KHÁC màu đỏ của private-key-riêng) để phân biệt trực quan:
// đỏ = chỉ bạn có, cam = cả nhóm dùng chung.
function mlsTreeMaskedValue(hex, variant) {
    var cls = variant ? ' ' + variant : '';
    return '<span class="mlsTreePrivRow' + cls + '">' +
        '<span class="mlsTreePrivMasked">•••••••••••••••••••••• (đang ẩn)</span>' +
        '<code class="mlsTreePrivValue' + cls + '">' + hex + '</code>' +
        '<button type="button" class="mlsTreePrivEye' + cls + '">Hiện</button>' +
        '</span>';
}

// 1 dòng "private key" trong panel chi tiết node -- chỉ được gọi cho đúng private key CỦA CHÍNH thiết
// bị bạn đang dùng (xem mlsTreeRenderNodeBox/mlsTreeRenderSvg) -- không thiết bị nào khác có private key
// để mà hiện ra đây cả.
function mlsTreePrivRow(label, hex) {
    return '<div class="mlsTreeDetailRow">' +
        '<span class="mlsTreeDetailLabel">' + label + '</span>' +
        mlsTreeMaskedValue(hex) +
        '</div>';
}

// Khối "secret tree" (ratchet mã hoá NỘI DUNG tin nhắn) của ĐÚNG leaf vừa bấm -- đã soi trực tiếp cấu
// trúc thật qua e2eLoadGroup trong lúc phát triển: state.secretTree là mảng cùng độ dài ratchetTree,
// mỗi phần tử {handshake:{generation,secret}, application:{generation,secret}}. Chỉ hiện ở LEAF vì đây
// là nơi RFC 9420 thật sự dùng ratchet này để mã hoá/giải mã (node cha không có ý nghĩa sử dụng, dù cấu
// trúc dữ liệu có phần tử ở mọi index cho đồng nhất). Màu CAM (--warn-*, giống senderDataSecret) chứ
// KHÔNG phải đỏ như private key -- vì secret NÀY không phải "chỉ riêng bạn": mọi thiết bị đã xử lý cùng
// số tin nhắn của leaf này đều tính ra giá trị GIỐNG HỆT (cùng dẫn xuất từ encryptionSecret chung).
function mlsTreeSecretTreeExplainHtml(entry) {
    if (!entry) return '';
    var appHex = entry.application && entry.application.secret ? mlsTreeHex(entry.application.secret) : null;
    var hsHex = entry.handshake && entry.handshake.secret ? mlsTreeHex(entry.handshake.secret) : null;
    if (!appHex && !hsHex) return '';
    var rows = '';
    if (appHex) {
        rows += '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">Application (gen ' + entry.application.generation + ')</span>' + mlsTreeMaskedValue(appHex, 'shared') + '</div>';
    }
    if (hsHex) {
        rows += '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">Handshake (gen ' + entry.handshake.generation + ')</span>' + mlsTreeMaskedValue(hsHex, 'shared') + '</div>';
    }
    return '<div class="mlsTreeSecretTreeBox">' +
        '<div class="mlsTreeSecretTreeTitle">Secret tree -- ratchet mã hoá nội dung</div>' +
        rows +
        '<div class="mlsTreeExplainText" style="margin-top:6px">Khác <code>senderDataSecret</code> (chỉ giải mã phần "ai gửi") -- đây là ratchet dùng để mã hoá/giải mã <b>nội dung thật</b> của tin nhắn từ leaf này (2 chuỗi riêng: <code>application</code> cho tin nhắn thường, <code>handshake</code> cho Proposal/Commit). Mỗi tin gửi đi, generation +1 và secret CŨ bị xoá ngay (forward secrecy) -- giá trị hiện tại chỉ giải mã được tin TỪ ĐÂY TRỞ ĐI, không lùi lại quá khứ được. Mọi thiết bị đã xử lý cùng số tin nhắn của leaf này sẽ có cùng giá trị -- không phải bí mật riêng của chủ leaf.</div>' +
        '</div>';
}

// true nếu node cha này đã từng có 1 UpdatePath đi qua (parentHash không rỗng) -- xem mục "parentHash"
// trong tài liệu "Diffie-Hellman Cơ chế trao đổi khóa chung.md" để hiểu rõ tại sao 1 node có key vẫn có
// thể "chưa có hash" (khác hẳn node blank hoàn toàn -- 2 trạng thái này TRƯỚC ĐÂY trông giống hệt nhau).
function mlsTreeHasParentHash(node) {
    return !!(node.parent.parentHash && node.parent.parentHash.length > 0);
}

// Công thức parentHash CHUẨN RFC 9420 (mục "Parent Hash"), điền vào đúng giá trị THẬT của node cha ngay
// phía trên (aboveInfo, tính sẵn ở mlsTreeRenderSvg từ layout.parentOf) -- KHÔNG tự tính lại/verify hash
// (không có hàm hash công khai nào để gọi lại từ đây), chỉ trình bày công thức + trỏ đúng vào giá trị đã
// hiển thị ở nơi khác trong modal để người xem tự đối chiếu bằng mắt.
function mlsTreeParentHashFormula(aboveInfo) {
    if (!aboveInfo || aboveInfo.blank) {
        return '<div class="mlsTreeExplainFormula">parentHash = Hash(\n' +
            '  hpkePublicKey(cha)                = (node cha hiện đang TRỐNG)\n' +
            '  parentHash(cha)                   = ?\n' +
            '  original_child_resolution(anh em) = (không hiện được -- cần đúng trạng thái cây TẠI THỜI ĐIỂM Commit đó)\n' +
            ')</div><div class="mlsTreeExplainText" style="margin-top:6px">Lưu ý: node cha ngay phía trên hiện đang <b>trống</b> -- nghĩa là cây đã <b>mở rộng thêm</b> sau lần Commit tính ra hash hiện tại của node này (chèn 1 node cha mới ở giữa). Giá trị hash đang hiển thị vẫn đúng với cấu trúc CŨ, sẽ được tính lại khi có Commit tiếp theo đi đúng qua nhánh mới này.</div>';
    }
    return '<div class="mlsTreeExplainFormula">parentHash = Hash(\n' +
        '  hpkePublicKey(cha)                = ' + aboveInfo.pubFp + '\n' +
        '  parentHash(cha)                   = ' + (aboveInfo.hasHash ? aboveInfo.parentHashFp : '(rỗng)') + '\n' +
        '  original_child_resolution(anh em) = (không hiện được -- cần đúng trạng thái cây TẠI THỜI ĐIỂM Commit đó, không suy ra được từ cây hiện tại)\n' +
        ')</div>';
}

// Giải thích NGẮN GỌN "giá trị của node này tính từ đâu" -- mô tả công thức/nguồn gốc theo đúng RFC 9420
// đã ghi trong tài liệu "Diffie-Hellman Cơ chế trao đổi khóa chung.md", không tự tính lại gì. isRoot/
// aboveInfo lấy từ mlsTreeRenderSvg (nơi có sẵn layout.parentOf để biết CHÍNH XÁC node cha ngay phía trên).
function mlsTreeExplainHtml(isLeaf, blank, isMineNode, isRoot, hasHash, aboveInfo) {
    var body;
    if (blank) {
        body = 'Node này <b>chưa từng được tính</b> -- chưa có Commit nào (Add/Remove/Update) có direct path đi đúng qua vị trí này, nên chưa có key/hash gì cả.';
    } else if (isLeaf) {
        body = isMineNode
            ? 'Cặp khoá lá này do <b>chính thiết bị bạn</b> tự sinh (lúc tạo KeyPackage, hoặc lúc bạn tự Commit/UpdatePath để đổi khoá): <code>public key = DeriveKeyPair(path_secret)</code>. Private key tương ứng bạn đang giữ, xem khối "Private key" ở trên.'
            : 'Cặp khoá lá này do <b>chính thiết bị của họ</b> tự sinh -- bạn chỉ nhận được public key qua KeyPackage/Welcome/Commit, không thể tự tính lại hay biết private key của họ.';
    } else if (isRoot) {
        body = 'Root <b>không có node cha nào ở trên</b> để mà hash vào -- nên <code>parentHash</code> của root luôn luôn rỗng theo định nghĩa (RFC 9420), bất kể lịch sử Commit nào.';
    } else if (!hasHash) {
        body = 'Node đã có key (<code>hpkePublicKey</code> ở trên) nhưng <code>parentHash</code> còn rỗng -- công thức bên dưới sẽ được áp dụng ngay khi có Commit tiếp theo mà direct path đi đúng qua đây:' + mlsTreeParentHashFormula(aboveInfo);
    } else {
        body = '<code>parentHash</code> của node này được tính khi có Commit đi qua, theo công thức:' + mlsTreeParentHashFormula(aboveInfo);
    }
    return '<div class="mlsTreeExplainBox"><div class="mlsTreeExplainTitle">Cách tính</div><div class="mlsTreeExplainText">' + body + '</div></div>';
}

// 1 ô trong sơ đồ -- <foreignObject> chứa HTML thường (tự xuống dòng/co giãn theo nội dung) thay vì tự
// đo độ rộng từng dòng chữ bằng canvas.measureText, đơn giản hơn nhiều cho 1 popup debug không cần in ấn.
// onPath: node này có nằm trên direct path (leaf của bạn -> gốc) không -- viền tô đậm màu accent để tự
// soi "mình cần đúng những public key/node nào" (đúng khái niệm copath/direct path trong tài liệu DH),
// tách biệt với "isMine" (chỉ đúng cho riêng leaf của bạn).
// Trả về {html, detail}: html là <g> vẽ trong SVG (kèm data-node-idx để bắt click), detail là HTML đầy
// đủ (khoá KHÔNG rút gọn) hiển thị trong #mlsTreeDetailPanel khi bấm vào ô này.
// privHex: hex private key HPKE của CHÍNH node này nếu thiết bị bạn đang giữ (từ state.privatePath.privateKeys[nodeIndex]
// -- luôn có cho leaf của bạn, và có thêm cho các node cha dọc direct path NẾU bạn từng tự Commit qua đó).
// mySigHex: hex signature private key của bạn, chỉ truyền khi đây đúng là leaf của bạn.
// secretTreeEntry: {handshake:{generation,secret}, application:{generation,secret}} của ĐÚNG node này,
// chỉ có ý nghĩa/được dùng khi isLeaf -- xem mlsTreeSecretTreeExplainHtml.
function mlsTreeRenderNodeBox(cx, cy, isLeaf, node, isMine, onPath, nodeIndex, privHex, mySigHex, secretTreeEntry) {
    var w = isLeaf ? MLS_TREE_LEAF_BOX_W : MLS_TREE_PARENT_BOX_W;
    var h = isLeaf ? MLS_TREE_LEAF_BOX_H : MLS_TREE_PARENT_BOX_H;
    var x = cx - w / 2, y = cy - h / 2;
    var blank = !node;
    var rx = isLeaf ? 14 : 12;
    var hasHash = !blank && !isLeaf && mlsTreeHasParentHash(node);
    // fill của ô trống = ĐÚNG màu nền .mlsTreeCanvasWrap (không phải "transparent") -- để chắn các cạnh
    // (đặc biệt cạnh tô đậm màu accent của direct path) không bị "xuyên qua" nhìn rối mắt khi 1 cạnh tình
    // cờ đi ngang qua toạ độ 1 ô trống nằm ở tầng khác (tự thấy khi soi ảnh chụp thật với path dài).
    var fill = blank ? 'var(--canvas)' : (isLeaf ? 'var(--surface)' : (hasHash ? 'var(--accent-soft)' : 'var(--warn-bg)'));
    var baseStroke = blank ? 'var(--ink-faint)' : (isLeaf ? 'var(--line)' : (hasHash ? 'var(--accent)' : 'var(--warn-border)'));
    var stroke = (isMine || onPath) ? 'var(--accent)' : baseStroke;
    var strokeWidth = isMine ? 2.5 : (onPath ? 2 : 1.2);
    var dashAttr = blank ? ' stroke-dasharray="4 3"' : '';
    var shadowAttr = blank ? '' : ' filter="url(#mlsTreeShadow)"';
    var titleParts = [];
    var contentHtml;
    var detailHtml;
    if (blank) {
        // Trước đây lá trống ghi "trống" còn node nhánh trống ghi "—" (khó hiểu, nhìn như lỗi hiển thị) --
        // giờ dùng chung 1 từ "trống" + 1 icon vòng đứt nét cho cả 2, đúng ý nghĩa thật: ô này CHƯA TỪNG có
        // ai gán key vào, khác hẳn "chưa có hash" (node dưới -- đã có key, chỉ riêng parentHash rỗng).
        contentHtml = '<div class="mlsTreeNodeBox mlsTreeBlank" style="display:flex;align-items:center;justify-content:center;gap:5px;height:100%">' +
            '<span class="mlsTreeDot mlsTreeDotGhost">◌</span>trống</div>';
        detailHtml = '<div class="mlsTreeDetailTitle">Ô trống</div>' +
            '<div class="mlsTreeDetailRow">Vị trí này trong cây <b>chưa từng được gán</b> thiết bị/khoá nào -- không phải lỗi, chỉ là chỗ trống chờ lượt Add tiếp theo.</div>';
    } else if (isLeaf) {
        var uid = e2eLeafUserId(node);
        var did = e2eLeafDeviceId(node);
        var name = uid ? mlsTreeEscapeHtml(typeof displayName === 'function' ? displayName(uid) : uid) : mlsTreeEscapeHtml(e2eLeafIdentity(node) || '?');
        // Avatar tròn tái dùng ĐÚNG hàm màu/chữ cái đã dùng cho avatar ở sidebar hội thoại
        // (sidebar-conversations.js) -- cùng 1 người thì avatar ở đây và ở danh sách chat trông y hệt
        // nhau, không tự vẽ bảng màu riêng cho mỗi chỗ.
        var colorKey = uid || e2eLeafIdentity(node) || name;
        var avColor = (typeof avatarColor === 'function') ? avatarColor(colorKey) : 'var(--accent)';
        var avInitial = (typeof avatarInitial === 'function') ? mlsTreeEscapeHtml(avatarInitial(name)) : '?';
        var sigHexFull = mlsTreeHex(node.leaf.signaturePublicKey);
        var hpkeHexFull = mlsTreeHex(node.leaf.hpkePublicKey);
        var sigFp = mlsTreeFingerprint(node.leaf.signaturePublicKey);
        var hpkeFp = mlsTreeFingerprint(node.leaf.hpkePublicKey);
        titleParts.push('sig: ' + sigHexFull);
        titleParts.push('hpke: ' + hpkeHexFull);
        contentHtml = '<div class="mlsTreeNodeBox mlsTreeLeafBox' + (isMine ? ' mine' : '') + '">' +
            '<div class="mlsTreeLeafHead">' +
            '<span class="mlsTreeAvatar" style="background:' + avColor + '">' + avInitial + '</span>' +
            '<span class="mlsTreeNodeName"><b>' + name + '</b>' + (isMine ? ' <span class="mlsTreeStar">★</span>' : '') + '</span>' +
            '</div>' +
            '<div class="mlsTreeMuted mlsTreeDeviceId">@' + mlsTreeEscapeHtml(did || '?') + '</div>' +
            '<div class="mlsTreeChipRow">' +
            '<span class="mlsTreeChip">sig ' + sigFp + '</span>' +
            '<span class="mlsTreeChip">hpke ' + hpkeFp + '</span>' +
            '</div>' +
            '</div>';
        // Panel chi tiết: NGUYÊN VẸN user id/device id/2 khoá công khai, không rút gọn như ô vẽ trên cây --
        // vẫn chỉ là dữ liệu công khai đã có sẵn trong ratchet tree, không lộ thêm gì (xem javadoc đầu file).
        detailHtml = '<div class="mlsTreeDetailTitle">' + name + (isMine ? ' <span class="mlsTreeStar">★</span> (thiết bị của bạn)' : '') + '</div>' +
            '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">User ID</span><code>' + mlsTreeEscapeHtml(uid || '?') + '</code></div>' +
            '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">Device ID</span><code>' + mlsTreeEscapeHtml(did || '?') + '</code></div>' +
            '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">Signature public key</span><code>' + sigHexFull + '</code></div>' +
            '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">HPKE public key</span><code>' + hpkeHexFull + '</code></div>';
    } else {
        // 2 trạng thái khác màu RÕ RỆT (xem CSS .mlsTreeParentBox.hashed/.pending) thay vì chỉ khác chữ
        // như trước -- "●" (đã có hash, tím) vs "○" (chưa có hash, vàng cảnh báo, tái dùng token --warn-*).
        var parentHashHexFull = hasHash ? mlsTreeHex(node.parent.parentHash) : '';
        var unmergedList = node.parent.unmergedLeaves || [];
        var phLabel = hasHash ? mlsTreeFingerprint(node.parent.parentHash) : 'chưa có hash';
        titleParts.push('parentHash: ' + (hasHash ? parentHashHexFull : '(rỗng -- chưa có UpdatePath nào đi qua node này)'));
        titleParts.push('unmergedLeaves: ' + (unmergedList.length ? unmergedList.join(', ') : '(không có)'));
        contentHtml = '<div class="mlsTreeNodeBox mlsTreeParentBox ' + (hasHash ? 'hashed' : 'pending') + '" style="display:flex;align-items:center;justify-content:center;gap:5px;height:100%;text-align:center">' +
            '<span class="mlsTreeDot">' + (hasHash ? '●' : '○') + '</span>' + (hasHash ? ('ph ' + phLabel) : phLabel) +
            '</div>';
        detailHtml = '<div class="mlsTreeDetailTitle">Node cha' + (hasHash ? '' : ' — chưa có hash') + '</div>' +
            '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">parentHash</span><code>' + (hasHash ? parentHashHexFull : '(rỗng -- chưa có UpdatePath nào đi qua node này)') + '</code></div>' +
            '<div class="mlsTreeDetailRow"><span class="mlsTreeDetailLabel">unmergedLeaves</span><code>' + (unmergedList.length ? unmergedList.join(', ') : '(không có)') + '</code></div>';
    }
    // Khối private key -- CHỈ xuất hiện khi thiết bị bạn thực sự đang giữ private key cho ĐÚNG node này
    // (privHex/mySigHex truyền vào từ mlsTreeRenderSvg, đọc từ state.privatePath/state.signaturePrivateKey
    // -- không có thư viện/tính toán giả nào ở đây). Mặc định ẨN, phải bấm mắt mới lộ -- xem mlsTreePrivRow.
    if (!blank && (privHex || mySigHex)) {
        var privRows = (mySigHex ? mlsTreePrivRow('Signature private key', mySigHex) : '') +
            (privHex ? mlsTreePrivRow(isLeaf ? 'HPKE private key (leaf)' : 'HPKE private key (node cha)', privHex) : '');
        detailHtml += '<div class="mlsTreePrivBox"><div class="mlsTreePrivTitle">⚠ Private key -- chỉ thiết bị này có, TUYỆT ĐỐI không chia sẻ</div>' + privRows + '</div>';
    }
    // Secret tree -- CHỈ hiện ở leaf (node cha có phần tử trong mảng cho đồng nhất cấu trúc, nhưng không
    // có ý nghĩa sử dụng thật, xem javadoc mlsTreeSecretTreeExplainHtml). Không cần điều kiện isMine --
    // giá trị này KHÔNG phải riêng của chủ leaf, mọi thiết bị đã xử lý cùng số tin nhắn đều tính ra giống
    // hệt (xem giải thích trong hàm).
    if (isLeaf && !blank && secretTreeEntry) {
        detailHtml += mlsTreeSecretTreeExplainHtml(secretTreeEntry);
    }
    var titleEl = titleParts.length ? '<title>' + mlsTreeEscapeHtml(titleParts.join('\n')) + '</title>' : '';
    var html = '<g class="mlsTreeNodeG" data-node-idx="' + nodeIndex + '">' + titleEl +
        '<rect x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '" rx="' + rx + '" fill="' + fill + '" stroke="' + stroke + '" stroke-width="' + strokeWidth + '"' + dashAttr + shadowAttr + ' />' +
        '<foreignObject x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '">' +
        '<div xmlns="http://www.w3.org/1999/xhtml" style="width:100%;height:100%;overflow:hidden">' + contentHtml + '</div>' +
        '</foreignObject>' +
        '</g>';
    return {html: html, detail: detailHtml};
}

// 1 cạnh nối cha-con trong sơ đồ -- tô đậm màu accent + dày hơn khi cạnh này nằm trên direct path của
// leaf-của-bạn lên gốc (onPath truyền từ mlsTreeRenderSvg), còn lại giữ màu trung tính --line như cũ.
function mlsTreeRenderEdge(x1, y1, x2, y2, onPath) {
    var stroke = onPath ? 'var(--accent)' : 'var(--line)';
    var width = onPath ? 2.5 : 1.5;
    return '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" stroke="' + stroke + '" stroke-width="' + width + '" stroke-linecap="round" />';
}

// privateKeys: map nodeIndex -> Uint8Array HPKE private key mà THIẾT BỊ NÀY đang giữ (state.privatePath.privateKeys
// -- ngoài leaf của bạn, có thể có thêm vài node cha dọc direct path nếu bạn từng tự Commit qua đó).
// mySigHex: hex signature private key của bạn (state.signaturePrivateKey), gắn riêng vào đúng leaf của bạn.
// secretTree: state.secretTree thô (mảng cùng độ dài ratchetTree, xem javadoc mlsTreeSecretTreeExplainHtml).
function mlsTreeRenderSvg(ratchetTree, myLeafNodeIndex, privateKeys, mySigHex, secretTree) {
    mlsTreeLastDetails = {}; // reset mỗi lần vẽ lại -- tránh panel chi tiết giữ dữ liệu CŨ (epoch trước)
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
    // Direct path của leaf-của-bạn lên gốc -- đi ngược map parentOf (dựng sẵn trong mlsTreeComputeLayout)
    // từ leaf của bạn cho tới rootIdx, đánh dấu từng node ghé qua để tô đậm riêng (xem mlsTreeRenderEdge/
    // mlsTreeRenderNodeBox) -- đúng khái niệm direct path đã ghi trong tài liệu DH, tự soi trực quan trên
    // sơ đồ thay vì chỉ đọc chữ.
    var pathSet = {};
    if (myLeafNodeIndex != null && myLeafNodeIndex >= 0 && layout.positions[myLeafNodeIndex]) {
        var cur = myLeafNodeIndex;
        pathSet[cur] = true;
        while (cur !== layout.rootIdx && layout.parentOf[cur] !== undefined) {
            cur = layout.parentOf[cur];
            pathSet[cur] = true;
        }
    }
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
            lines.push(mlsTreeRenderEdge(x, y, lPos.x + padX, yFor(lPos.level), pathSet[i] && pathSet[l]));
            lines.push(mlsTreeRenderEdge(x, y, rPos.x + padX, yFor(rPos.level), pathSet[i] && pathSet[r]));
        }
        var node = ratchetTree[i];
        var isMineNode = i === myLeafNodeIndex;
        var privBytes = privateKeys ? privateKeys[i] : undefined;
        var privHex = privBytes ? mlsTreeHex(privBytes) : null;
        var secretTreeEntry = (isLeaf && secretTree) ? secretTree[i] : null;
        var rendered = mlsTreeRenderNodeBox(x, y, isLeaf, node, isMineNode, !!pathSet[i], i, privHex, (isMineNode && isLeaf) ? mySigHex : null, secretTreeEntry);
        // "Cách tính" -- cần biết CHÍNH XÁC node cha thật ngay phía trên (layout.parentOf, không phải suy
        // đoán) để điền công thức parentHash đúng giá trị thật, nên tính ở đây (nơi có layout) rồi nối vào
        // detail thay vì làm trong mlsTreeRenderNodeBox (không có layout).
        var isRootNode = i === layout.rootIdx;
        var hasHashHere = !!node && !isLeaf && mlsTreeHasParentHash(node);
        var aboveInfo = null;
        if (!isLeaf && !isRootNode && node) {
            var aboveIdx = layout.parentOf[i];
            var aboveNode = aboveIdx !== undefined ? ratchetTree[aboveIdx] : undefined;
            if (!aboveNode) {
                aboveInfo = {blank: true};
            } else {
                var aboveHasHash = mlsTreeHasParentHash(aboveNode);
                aboveInfo = {blank: false, pubFp: mlsTreeFingerprint(aboveNode.parent.hpkePublicKey), hasHash: aboveHasHash, parentHashFp: aboveHasHash ? mlsTreeFingerprint(aboveNode.parent.parentHash) : null};
            }
        }
        boxes.push(rendered.html);
        mlsTreeLastDetails[i] = rendered.detail + mlsTreeExplainHtml(isLeaf, !node, isMineNode, isRootNode, hasHashHere, aboveInfo);
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
        '<div class="bgPickerHead"><span>Sơ đồ cây MLS</span>' +
        '<div class="mlsTreeHeadActions">' +
        '<button type="button" id="mlsTreeFullscreenBtn" title="Toàn màn hình">⛶</button>' +
        '<button type="button" id="mlsTreeCloseBtn">✕</button>' +
        '</div></div>' +
        '<div class="bgPickerBody">' +
        '<div class="mlsTreeInfoGrid" id="mlsTreeInfoGrid"></div>' +
        '<div class="mlsTreeLegend">' +
        '<span class="mlsTreeLegendItem"><span class="mlsTreeLegendDot leaf"></span>Thiết bị (lá)</span>' +
        '<span class="mlsTreeLegendItem"><span class="mlsTreeLegendDot hashed"></span>Node cha — đã có hash</span>' +
        '<span class="mlsTreeLegendItem"><span class="mlsTreeLegendDot pending"></span>Node cha — chưa có hash</span>' +
        '<span class="mlsTreeLegendItem"><span class="mlsTreeLegendDot blank"></span>Ô trống</span>' +
        '<span class="mlsTreeLegendItem"><span class="mlsTreeLegendDot mine"></span>Thiết bị của bạn</span>' +
        '<span class="mlsTreeLegendItem"><span class="mlsTreeLegendLine"></span>Đường đi lên gốc của bạn</span>' +
        '</div>' +
        '<div class="mlsTreeCanvasWrap" id="mlsTreeCanvasWrap"></div>' +
        '<div class="mlsTreeDetailPanel" id="mlsTreeDetailPanel">' +
        '<div class="mlsTreeDetailEmpty">Bấm vào 1 node trong sơ đồ để xem đầy đủ thông tin (khoá nguyên vẹn, không rút gọn).</div>' +
        '</div>' +
        '</div></div>';
    document.body.appendChild(overlay);
    overlay.querySelector('#mlsTreeCloseBtn').onclick = function () { overlay.classList.remove('show'); };
    // Toàn màn hình -- chỉ đổi kích thước/bo góc của modal (CSS #mlsTreeOverlay.fullscreen), KHÔNG đụng
    // gì tới cách vẽ SVG bên trong (canvas tự cuộn ngang/dọc theo .mlsTreeCanvasWrap có sẵn) -- hữu ích
    // khi cây nhiều lá, cần nhìn rộng hơn khung 920px mặc định.
    overlay.querySelector('#mlsTreeFullscreenBtn').onclick = function () {
        var isFs = overlay.classList.toggle('fullscreen');
        this.textContent = isFs ? '⤡' : '⛶';
        this.title = isFs ? 'Thu nhỏ' : 'Toàn màn hình';
    };
    // Bấm vào 1 node (leaf/nhánh/trống) trên sơ đồ -- đổ dữ liệu ĐẦY ĐỦ (không rút gọn như ô vẽ) vào
    // panel bên dưới. Event delegation trên chính wrap (không gắn listener riêng cho từng node) vì SVG bị
    // vẽ lại toàn bộ mỗi lần đổi epoch/refresh -- gắn 1 lần duy nhất ở đây là đủ, không cần gỡ/gắn lại.
    overlay.querySelector('#mlsTreeCanvasWrap').addEventListener('click', function (e) {
        var g = e.target.closest('.mlsTreeNodeG');
        if (!g) return;
        var idx = Number(g.getAttribute('data-node-idx'));
        var detail = mlsTreeLastDetails[idx];
        var panel = overlay.querySelector('#mlsTreeDetailPanel');
        panel.innerHTML = detail || '<div class="mlsTreeDetailEmpty">Không có dữ liệu cho node này.</div>';
    });
    // Nút mắt trong bất kỳ khối secret nào (mlsTreeMaskedValue) -- toggle class "revealed" trên đúng dòng
    // đó, CSS lo phần ẩn/hiện (.mlsTreePrivRow.revealed, xem style.css). Gắn trên CẢ overlay (không chỉ
    // detailPanel) vì giờ còn xuất hiện ở info grid (khoá senderDataSecret dùng chung cả nhóm, xem
    // mlsTreeSharedSecretCardHtml) -- 1 listener duy nhất dùng chung cho mọi chỗ. Mặc định LUÔN ẩn mỗi lần
    // đổ HTML mới (bấm node khác, hoặc render lại cây) -- không có state "nhớ đã mở" giữa các lần.
    overlay.addEventListener('click', function (e) {
        var btn = e.target.closest('.mlsTreePrivEye');
        if (!btn) return;
        var row = btn.closest('.mlsTreePrivRow');
        var revealed = row.classList.toggle('revealed');
        btn.textContent = revealed ? 'Ẩn' : 'Hiện';
    });
    // Bấm ra ngoài modal (đúng nền overlay, không phải bấm vào modal rồi nổi bọt lên) thì đóng -- giống
    // mọi overlay khác trong app (xem ensureE2eRecoveryKeyModal).
    overlay.addEventListener('click', function (e) { if (e.target === overlay) overlay.classList.remove('show'); });
    return overlay;
}

// "Khoá chung mọi thiết bị phải giống nhau mới giải mã được tin nhắn" -- KHÔNG phải encryptionSecret gốc
// (secret đó bị xoá khỏi bộ nhớ NGAY sau khi dùng để dựng secretTree, đúng nguyên tắc forward-secrecy của
// MLS -- tự thấy trong vendor/mls.js, mọi chỗ tính xong secretTree đều gọi De(...encryptionSecret) ngay
// sau đó, không giữ lại). Giá trị THẬT SỰ còn tồn tại và được dùng lại ở MỌI tin nhắn trong epoch là
// state.keySchedule.senderDataSecret -- dùng để giải mã lớp "sender data" (lộ leaf/generation của người
// gửi) của mọi private message, xem hàm giải mã message trong vendor/mls.js (bl(...,e.keySchedule.
// senderDataSecret,...,e.secretTree,...)). Đây là field DUY NHẤT còn ở dạng 1 giá trị đơn (secretTree là
// cả 1 cây theo leaf/generation, không tiện hiện thành 1 ô).
function mlsTreeSharedSecretCardHtml(state) {
    var sds = state.keySchedule && state.keySchedule.senderDataSecret;
    if (!sds || !sds.length) return '';
    var hex = mlsTreeHex(sds);
    return '<div class="mlsTreeInfoItem mlsTreeInfoItemShared">' +
        '<span class="mlsTreeInfoLabel" title="Dẫn xuất từ key schedule của epoch hiện tại -- dùng để giải mã phần &quot;sender data&quot; (tiết lộ leaf/generation người gửi) của MỌI tin nhắn trong epoch này. Mọi thiết bị trong nhóm phải tính ra ĐÚNG CÙNG giá trị này mới giải mã được -- không phải encryptionSecret gốc (giá trị đó đã bị xoá khỏi bộ nhớ ngay sau khi dựng xong secretTree, xem tài liệu Diffie-Hellman.md).">Khoá chung mọi thiết bị (senderDataSecret)</span>' +
        mlsTreeMaskedValue(hex, 'shared') +
        mlsTreeSenderDataSecretExplainHtml() +
        '</div>';
}

// Công thức dẫn xuất senderDataSecret -- CHÉP ĐÚNG chuỗi hàm thật trong vendor/mls.js (Mu -> joiner_secret,
// Gc/jc -> epoch_secret rồi DeriveSecret theo từng label, đã đối chiếu khớp label string "joiner"/"sender
// data"/"encryption"/... trong chính file minified, không suy đoán từ RFC suông) -- KHÔNG có giá trị trung
// gian nào (commit_secret/joiner_secret/epoch_secret) để điền vào đây vì chúng đã bị xoá khỏi state ngay
// sau khi dùng xong (forward secrecy), nên chỉ trình bày công thức, không phải 1 phép tính có thể chạy lại.
function mlsTreeSenderDataSecretExplainHtml() {
    return '<div class="mlsTreeExplainBox"><div class="mlsTreeExplainTitle">Cách tính</div>' +
        '<div class="mlsTreeExplainText">Dẫn xuất từ <b>key schedule</b> của epoch hiện tại -- một chuỗi <code>HKDF</code> bắt đầu từ <code>commit_secret</code> của Commit gần nhất:</div>' +
        '<div class="mlsTreeExplainFormula">commit_secret\n' +
        '  -> Extract(init_secret_cũ, commit_secret)\n' +
        '  -> ExpandWithLabel(..., "joiner", GroupContext, Nh)   = joiner_secret\n' +
        '  -> (+ PSK nếu có) -> ExpandWithLabel(..., "epoch", GroupContext, Nh) = epoch_secret\n' +
        '  -> DeriveSecret(epoch_secret, "sender data")          = senderDataSecret  ← giá trị này\n' +
        '     (epoch_secret còn sinh ra encryptionSecret/exporterSecret/membershipKey/... theo cùng cách,\n' +
        '      mỗi cái 1 label riêng)</div>' +
        '<div class="mlsTreeExplainText" style="margin-top:6px">Vì <code>GroupContext</code> (epoch, tree_hash...) và <code>commit_secret</code> (từ path secret mới nhất) đều được đồng bộ qua đúng 1 Commit, mọi thiết bị xử lý Commit đó tự tính ra ĐÚNG CÙNG giá trị -- không ai cần gửi giá trị này cho ai.</div>' +
        '<div class="mlsTreeExplainText" style="margin-top:6px">⚠ <b>Lưu ý:</b> giá trị này KHÔNG tự giải mã được nội dung tin nhắn -- nó chỉ giải mã phần "ai gửi, thứ mấy" (sender data). Muốn đọc nội dung thật, còn cần thêm secret tương ứng trong <b>secret tree</b> của đúng leaf/generation đó -- bấm vào 1 leaf trong sơ đồ để xem.</div>' +
        '</div>';
}

function mlsTreeRenderInfoAndCanvas(infoGrid, wrap, state, detailPanel) {
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
    infoGrid.innerHTML = items.map(function (it, idx) {
        var v = mlsTreeEscapeHtml(it[1]);
        // Epoch là con số quan trọng nhất trên toàn modal (đổi mỗi lần Commit, quyết định toàn bộ group
        // secret hiện tại) -- tách riêng thành 1 badge tròn nổi bật thay vì trộn lẫn như các trường tĩnh
        // còn lại (cipher suite, group id...).
        var valueHtml = idx === 0
            ? '<span class="mlsTreeEpochBadge" title="' + v + '">' + v + '</span>'
            : '<span class="mlsTreeInfoValue" title="' + v + '">' + v + '</span>';
        return '<div class="mlsTreeInfoItem"><span class="mlsTreeInfoLabel">' + it[0] + '</span>' + valueHtml + '</div>';
    }).join('') + mlsTreeSharedSecretCardHtml(state);
    // Private key CỦA CHÍNH thiết bị này -- chỉ đọc, không tính toán gì thêm (xem javadoc mlsTreeRenderSvg).
    // Chỉ tồn tại khi state có đủ 2 field này (luôn có khi group state hợp lệ và bạn còn là thành viên).
    var privateKeys = (state.privatePath && state.privatePath.privateKeys) || null;
    var mySigHex = state.signaturePrivateKey ? mlsTreeHex(state.signaturePrivateKey) : null;
    wrap.innerHTML = mlsTreeRenderSvg(state.ratchetTree, myLeaf ? myLeaf.idx : -1, privateKeys, mySigHex, state.secretTree || null);
    // Reset panel chi tiết về placeholder -- mlsTreeLastDetails vừa bị mlsTreeRenderSvg thay mới hoàn
    // toàn, index của lần render trước (nếu đang hiển thị) không còn khớp dữ liệu hiện tại nữa.
    if (detailPanel) {
        detailPanel.innerHTML = '<div class="mlsTreeDetailEmpty">Bấm vào 1 node trong sơ đồ để xem đầy đủ thông tin (khoá nguyên vẹn, không rút gọn).</div>';
    }
}

// Điểm vào DUY NHẤT -- gọi từ nút "Xem sơ đồ" (xem updateInfoPanel, sidebar-conversations.js).
function openMlsTreeModal(conversationId) {
    var overlay = ensureMlsTreeModal();
    var wrap = overlay.querySelector('#mlsTreeCanvasWrap');
    var infoGrid = overlay.querySelector('#mlsTreeInfoGrid');
    var detailPanel = overlay.querySelector('#mlsTreeDetailPanel');
    infoGrid.innerHTML = '';
    wrap.innerHTML = '<div class="mlsTreeStatus">Đang tải...</div>';
    detailPanel.innerHTML = '<div class="mlsTreeDetailEmpty">Bấm vào 1 node trong sơ đồ để xem đầy đủ thông tin (khoá nguyên vẹn, không rút gọn).</div>';
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
        mlsTreeRenderInfoAndCanvas(infoGrid, wrap, state, detailPanel);
    }).catch(function (err) {
        if (!overlay.classList.contains('show')) return;
        wrap.innerHTML = '<div class="mlsTreeStatus">Lỗi đọc dữ liệu nhóm: ' + mlsTreeEscapeHtml(err.message) + '</div>';
    });
}
