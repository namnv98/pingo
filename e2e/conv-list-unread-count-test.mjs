// Verify GET /conversations tra dung unreadCount CHINH XAC cho tung conversation (dung cho badge
// sidebar, xem demo.html buildConvListItem) -- ton tai qua reload (khac unreadConversationIds cu,
// chi la co tam trong bo nho trinh duyet, mat sach sau reload du tin van chua doc that).

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

async function getConversations(token) {
  const res = await fetch(`${DEFAULT_API_BASE}/conversations`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
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

  console.log("== A gui 3 tin, B khong doc gi ==");
  let lastId;
  for (let i = 0; i < 3; i++) {
    const id = uuid();
    lastId = id;
    A.ws.send(JSON.stringify({ type: "MESSAGE", id, conversationId, body: { message: `tin ${i}` } }));
    await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === id);
  }
  await sleep(300);

  console.log("\n== GET /conversations cua B phai co unreadCount=3 cho conversation nay ==");
  const listB1 = await getConversations(accountB.token);
  const convB1 = listB1.find((c) => c.conversationId === conversationId);
  const pass1 = convB1 && convB1.unreadCount === 3;
  console.log(`unreadCount cua B: ${convB1 && convB1.unreadCount} -> ${pass1 ? "OK" : "FAIL"}`);

  console.log("\n== B doc tin cuoi cung -- unreadCount phai ve 0 ('reload' van dung, khong phai co tam) ==");
  B.ws.send(JSON.stringify({ type: "READ", id: lastId, conversationId }));
  await waitFor(A.received, (f) => f.type === "SEEN" && f.id === lastId);
  await sleep(300);
  const listB2 = await getConversations(accountB.token);
  const convB2 = listB2.find((c) => c.conversationId === conversationId);
  const pass2 = convB2 && convB2.unreadCount === 0;
  console.log(`unreadCount cua B sau khi doc: ${convB2 && convB2.unreadCount} -> ${pass2 ? "OK" : "FAIL"}`);

  console.log("\n== GET /conversations cua A (nguoi gui) phai luon la 0 -- tu 'doc' luon tin minh gui ==");
  const listA = await getConversations(accountA.token);
  const convA = listA.find((c) => c.conversationId === conversationId);
  const pass3 = convA && convA.unreadCount === 0;
  console.log(`unreadCount cua A: ${convA && convA.unreadCount} -> ${pass3 ? "OK" : "FAIL"}`);

  const pass = pass1 && pass2 && pass3;
  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
