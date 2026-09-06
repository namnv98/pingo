// Verify fix: nguoi GUI tu dong "doc" luon chinh tin minh vua gui (tien con tro da doc cua HO, xem
// ChatSessionManager#persistMessage) -- truoc khi sua, gui 1 loat tin lien tiep ma ben kia chua kip
// doc gi them se khien con tro da doc CUA CHINH NGUOI GUI ket lai o tin CUOI CUNG ben kia tung gui,
// khien demo.html hieu lam toan bo tin minh vua gui la "chua doc" moi lan mo lai (bao loi: "nó luôn
// ghim lại cái tin cũ... jump lại cái chỗ tin cuối người kia gửi").

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

  console.log("== B gui 1 tin, A doc no (READ) -- con tro cua A tien toi tin do ==");
  const bMsgId = uuid();
  B.ws.send(JSON.stringify({ type: "MESSAGE", id: bMsgId, conversationId, body: { message: "chao A" } }));
  await waitFor(A.received, (f) => f.type === "MESSAGE" && f.id === bMsgId);
  A.ws.send(JSON.stringify({ type: "READ", id: bMsgId, conversationId }));
  await waitFor(B.received, (f) => f.type === "SEEN" && f.id === bMsgId);
  await sleep(300);

  console.log("\n== A tu gui LIEN TIEP 5 tin cua chinh minh (B khong doc gi them) ==");
  let lastAMsgId;
  for (let i = 0; i < 5; i++) {
    const id = uuid();
    lastAMsgId = id;
    A.ws.send(JSON.stringify({ type: "MESSAGE", id, conversationId, body: { message: `tin cua A so ${i}` } }));
    await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === id);
    await sleep(50);
  }
  await sleep(300);

  console.log("\n== GET /read-cursor cua A phai la tin CUOI CUNG A vua gui (khong con ket o tin cua B) ==");
  const cursorA = await getJson(`/read-cursor?conversationId=${conversationId}`, accountA.token);
  const pass = cursorA.data && cursorA.data.lastReadMessageId === lastAMsgId;
  console.log(`cursor cua A: ${JSON.stringify(cursorA)} -> ky vong lastReadMessageId = ${lastAMsgId} -> ${pass ? "OK" : "FAIL"}`);

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
