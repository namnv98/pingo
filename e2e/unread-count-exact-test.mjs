// Verify fix: chấm đỏ phải hiện ĐÚNG tổng số tin chưa đọc, kể cả khi vượt quá 1 trang lazy-load (30
// tin/lần) -- truoc khi sua, GET /read-cursor khong tra unreadCount, client tu dem theo so phan tu da
// lazy-load duoc nen bi chan o dung 30 ("sao con so hien thi tren cham max la 30 a??").

import { uuid, connect, waitFor, registerUser, createConversation, DEFAULT_API_BASE } from "./lib.mjs";

const WS_URL = "ws://localhost:31003/connect";
const TOTAL_UNREAD = 45; // > HISTORY_PAGE_SIZE (30) ben demo.html -- dung de chung minh khong bi chan o 1 trang

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

  console.log("== A gui 1 tin, B doc no (thiet lap con tro) ==");
  const firstId = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: firstId, conversationId, body: { message: "mo dau" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === firstId);
  B.ws.send(JSON.stringify({ type: "READ", id: firstId, conversationId }));
  await waitFor(A.received, (f) => f.type === "SEEN" && f.id === firstId);
  await sleep(300);

  console.log(`\n== A gui lien tiep ${TOTAL_UNREAD} tin, B KHONG doc gi them ==`);
  for (let i = 0; i < TOTAL_UNREAD; i++) {
    const id = uuid();
    A.ws.send(JSON.stringify({ type: "MESSAGE", id, conversationId, body: { message: `tin chua doc so ${i}` } }));
    await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === id);
  }
  await sleep(300);

  console.log("\n== GET /read-cursor cua B phai tra unreadCount DUNG BANG " + TOTAL_UNREAD + " (khong bi chan o 30) ==");
  const cursorB = await getJson(`/read-cursor?conversationId=${conversationId}`, accountB.token);
  const pass = cursorB.data && cursorB.data.unreadCount === TOTAL_UNREAD;
  console.log(`cursor cua B: ${JSON.stringify(cursorB)} -> ky vong unreadCount=${TOTAL_UNREAD} -> ${pass ? "OK" : "FAIL"}`);

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
