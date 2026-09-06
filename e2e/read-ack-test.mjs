// Verify luong "online nhung khong thuc su xem" (xem MessageType#READ, herald NotificationConsumer):
// A gui tin cho B trong 1 conversation dung chung. B luon "online" (socket dang mo) ca 2 lan gui,
// nhung chi lan 1 la B chu dong gui READ ack (mo phong dang nhin man hinh) -- lan 2 B KHONG gui READ
// (mo phong may khoa man hinh/tab nen, socket van song nhung khong ai xem). Ky vong: lan 1 KHONG co
// notification nao duoc luu cho B (da huy nho ACK that); lan 2 CO, du B dang "online" luc gui tin.
//
// Chay: node e2e/read-ack-test.mjs (can harbor NodePort 31003 + colony NodePort 31002 +
// herald NodePort 31007 dang chay qua k3s, xem deploy-k3s.sh).

import { uuid, connect, waitFor, registerUser, createConversation, DEFAULT_API_BASE } from "./lib.mjs";

const WS_URL = "ws://localhost:31003/connect";
const HERALD_API = "http://localhost:31007";
const GRACE_MS = 8_000;
const SAFETY_MARGIN_MS = 4_000; // biên an toàn cộng thêm sau GRACE_MS trước khi query notifications

async function authSession(label, account) {
  const s = await connect(WS_URL, label);
  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);
  s.userId = account.id;
  s.token = account.token;
  return s;
}

async function getNotifications(userId, token) {
  const res = await fetch(`${HERALD_API}/notifications?limit=200`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`GET /notifications failed: HTTP ${res.status}`);
  return res.json();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  console.log("== dang ky A (nguoi gui) va B (nguoi nhan, luon 'online') ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);

  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  console.log("== A tao 1 conversation chung voi B qua POST /conversations ==");
  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  console.log(`conversationId = ${conversationId}`);

  // ---- Lan 1: B CO gui READ ngay sau khi nhan (mo phong dang thuc su nhin man hinh) ----
  console.log("\n== LAN 1: A gui tin, B gui READ ngay (mo phong dang xem) ==");
  const msgId1 = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId1, conversationId, body: { message: "hello-1-B-dang-xem" } }));
  const received1 = await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId1);
  B.ws.send(JSON.stringify({ type: "READ", id: received1.id, conversationId }));
  console.log("[B] da gui READ ack cho msgId1 =", msgId1);

  // ---- Lan 2: B KHONG gui READ (mo phong may khoa man hinh, socket van song) ----
  console.log("\n== LAN 2: A gui tin, B KHONG gui READ (mo phong khong ai xem, socket van mo) ==");
  const msgId2 = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId2, conversationId, body: { message: "hello-2-B-khong-xem" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId2);
  console.log("[B] da nhan msgId2 qua WS nhung CO Y khong gui READ");

  console.log(`\n== doi ${GRACE_MS + SAFETY_MARGIN_MS}ms (het GRACE_MS ben herald + bien an toan) roi kiem tra /notifications cua B ==`);
  await sleep(GRACE_MS + SAFETY_MARGIN_MS);

  const notifications = await getNotifications(accountB.id, accountB.token);
  const notiForMsg1 = notifications.filter((n) => n.conversationId === conversationId && n.bodyPreview && n.bodyPreview.includes("hello-1-B-dang-xem"));
  const notiForMsg2 = notifications.filter((n) => n.conversationId === conversationId && n.bodyPreview && n.bodyPreview.includes("hello-2-B-khong-xem"));

  console.log(`\nnotifications cho msgId1 (B DA gui READ, ky vong 0): ${notiForMsg1.length}`);
  console.log(`notifications cho msgId2 (B KHONG gui READ, ky vong >=1): ${notiForMsg2.length}`);

  const pass1 = notiForMsg1.length === 0;
  const pass2 = notiForMsg2.length >= 1;

  console.log(`\nB gui READ dung luc => KHONG bi luu noti: ${pass1}`);
  console.log(`B khong gui READ (du dang online) => VAN bi luu noti: ${pass2}`);

  if (pass1 && pass2) {
    console.log("\n=== PASS ===");
  } else {
    console.log("\n=== FAIL ===");
    process.exitCode = 1;
  }

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
