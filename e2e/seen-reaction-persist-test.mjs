// Verify SEEN va REACTION PERSIST vao DB (khac ban truoc chi la tin hieu tam thoi qua WS, mat khi
// reload): A gui tin, B doc (SEEN) + tha reaction -- GET /messages phai tra ve dung seen=true va
// reactions dung, ke ca sau khi "reload" (goi lai GET /messages tu dau, mo phong F5).

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

async function getMessages(conversationId) {
  const res = await fetch(`${DEFAULT_API_BASE}/messages?conversationId=${conversationId}&limit=10`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  console.log("== dang ky A (gui), B (doc + react) ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);
  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  console.log("\n== A gui tin, B nhan roi gui READ + REACTION (thumbs up) ==");
  const msgId = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId, conversationId, body: { message: "hello" } }));
  const received = await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId);
  B.ws.send(JSON.stringify({ type: "READ", id: received.id, conversationId }));
  B.ws.send(JSON.stringify({ type: "REACTION", id: msgId, conversationId, body: { emoji: "👍" } }));

  // doi server xu ly xong (persist la async, cho tho mai)
  await sleep(1500);

  console.log("\n== 'reload' (goi lai GET /messages tu dau) -- kiem tra seen + reaction con nguyen ==");
  const messagesAfterReload = await getMessages(conversationId);
  const msg = messagesAfterReload.find((m) => m.id === msgId);
  console.log(`tim thay message: ${!!msg}`);
  console.log(`seen: ${msg && msg.seen}`);
  console.log(`reactions: ${msg && JSON.stringify(msg.reactions)}`);

  console.log("\n== B doi reaction sang emoji khac (thay the, khong cong don) ==");
  B.ws.send(JSON.stringify({ type: "REACTION", id: msgId, conversationId, body: { emoji: "❤️" } }));
  await sleep(1000);
  const afterChange = await getMessages(conversationId);
  const msgAfterChange = afterChange.find((m) => m.id === msgId);
  console.log(`reactions sau khi doi: ${JSON.stringify(msgAfterChange.reactions)}`);

  console.log("\n== B huy reaction ==");
  B.ws.send(JSON.stringify({ type: "REACTION", id: msgId, conversationId, body: {} }));
  await sleep(1000);
  const afterRemove = await getMessages(conversationId);
  const msgAfterRemove = afterRemove.find((m) => m.id === msgId);
  console.log(`reactions sau khi huy: ${JSON.stringify(msgAfterRemove.reactions)}`);

  const pass =
    !!msg && msg.seen === true &&
    msg.reactions.length === 1 && msg.reactions[0].emoji === "👍" && msg.reactions[0].userId === accountB.id &&
    msgAfterChange.reactions.length === 1 && msgAfterChange.reactions[0].emoji === "❤️" &&
    msgAfterRemove.reactions.length === 0;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
