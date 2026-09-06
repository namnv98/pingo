// Verify tinh nang lazy-load + "con tro da doc" moi them vao demo.html:
// 1. GET /read-cursor tra null khi chua tung doc tin nao, tra dung lastReadMessageId/lastReadTs sau
//    khi gui READ -- va CHI TIEN VE PHIA TRUOC (xem markRead trong MessageHistoryRegistry).
// 2. GET /messages?after=<ts> tra dung cac tin MOI HON ts, TANG DAN theo thoi gian (nguoc huong voi
//    ?before= mac dinh).
// 3. GET /messages?before= phan trang lui dung (khong lap/thieu tin) qua nhieu trang nho.

import { uuid, connect, waitFor, registerUser, createConversation, DEFAULT_API_BASE } from "./lib.mjs";

const WS_URL = "ws://localhost:31003/connect";

async function authSession(label, account) {
  const s = await connect(WS_URL, label);
  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);
  s.userId = account.id;
  return s;
}

async function getJson(path, token) {
  const res = await fetch(`${DEFAULT_API_BASE}${path}`, token ? { headers: { Authorization: `Bearer ${token}` } } : undefined);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${path}`);
  return res.json();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);
  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  console.log("== chua doc tin nao: GET /read-cursor phai tra data=null ==");
  const cursorBefore = await getJson(`/read-cursor?conversationId=${conversationId}`, accountB.token);
  const passCursorNull = cursorBefore.data === null || cursorBefore.data === undefined;
  console.log(`cursor luc chua doc gi: ${JSON.stringify(cursorBefore)} -> ${passCursorNull ? "OK" : "FAIL"}`);

  console.log("\n== A gui 7 tin, B chi doc (READ) tin thu 5 ==");
  const messageIds = [];
  for (let i = 0; i < 7; i++) {
    const id = uuid();
    messageIds.push(id);
    A.ws.send(JSON.stringify({ type: "MESSAGE", id, conversationId, body: { message: `tin so ${i}` } }));
    await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === id);
    await sleep(30);
  }
  const fifthId = messageIds[4];
  B.ws.send(JSON.stringify({ type: "READ", id: fifthId, conversationId }));
  await waitFor(A.received, (f) => f.type === "SEEN" && f.id === fifthId);
  await sleep(400); // markRead ghi DB best-effort, khong doi ACK -- cho DB ghi xong

  console.log("\n== GET /read-cursor phai tra dung lastReadMessageId = tin thu 5 ==");
  const cursorAfter = await getJson(`/read-cursor?conversationId=${conversationId}`, accountB.token);
  const passCursorSet = cursorAfter.data && cursorAfter.data.lastReadMessageId === fifthId;
  console.log(`cursor sau khi doc tin thu 5: ${JSON.stringify(cursorAfter)} -> ${passCursorSet ? "OK" : "FAIL"}`);

  console.log("\n== B doc LUI lai tin thu 2 (cu hon) -- con tro KHONG duoc lui lai ==");
  B.ws.send(JSON.stringify({ type: "READ", id: messageIds[1], conversationId }));
  await sleep(400);
  const cursorAfterOlder = await getJson(`/read-cursor?conversationId=${conversationId}`, accountB.token);
  const passCursorMonotonic = cursorAfterOlder.data && cursorAfterOlder.data.lastReadMessageId === fifthId;
  console.log(`cursor sau khi doc tin CU hon: ${JSON.stringify(cursorAfterOlder)} -> ${passCursorMonotonic ? "OK" : "FAIL"}`);

  console.log("\n== GET /messages?after=<ts cua tin thu 5> phai tra dung 2 tin sau no (thu 6, 7), TANG DAN ==");
  const afterMessages = await getJson(`/messages?conversationId=${conversationId}&after=${cursorAfter.data.lastReadTs}`);
  const passAfter =
    afterMessages.length === 2 &&
    afterMessages[0].id === messageIds[5] &&
    afterMessages[1].id === messageIds[6] &&
    afterMessages[0].ts <= afterMessages[1].ts;
  console.log(`after=... tra ve ${afterMessages.length} tin: ${JSON.stringify(afterMessages.map((m) => m.id === messageIds[5] ? "tin6" : m.id === messageIds[6] ? "tin7" : "?"))} -> ${passAfter ? "OK" : "FAIL"}`);

  console.log("\n== GET /messages?limit=3&before= phan trang lui: 3 trang lien tiep phai phu het 7 tin, khong trung ==");
  const seenIds = new Set();
  let before = undefined;
  let pages = 0;
  for (let i = 0; i < 10 && seenIds.size < 7; i++) {
    const url = `/messages?conversationId=${conversationId}&limit=3` + (before ? `&before=${before}` : "");
    const page = await getJson(url);
    if (!page.length) break;
    pages++;
    page.forEach((m) => seenIds.add(m.id));
    before = page[page.length - 1].ts; // trang tra DESC -- phan tu CUOI la CU nhat trong trang, dung lam moc trang ke tiep
  }
  const passPagination = seenIds.size === 7 && messageIds.every((id) => seenIds.has(id));
  console.log(`phan trang qua ${pages} trang, thu duoc ${seenIds.size}/7 tin -> ${passPagination ? "OK" : "FAIL"}`);

  const pass = passCursorNull && passCursorSet && passCursorMonotonic && passAfter && passPagination;
  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
