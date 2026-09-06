// Verify GET /conversations tra dung lastMessageBody/lastMessageFromUserId/lastMessageDeleted --
// dung cho preview tin nhan gan nhat o sidebar (demo.html conversationLastMessagePreview()).

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

  console.log("== A gui tin ==");
  const msgId = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId, conversationId, body: { message: "xin chao ban" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId);
  await sleep(500);

  const convsAfterMsg = await getConversations(accountB.token);
  const convB = convsAfterMsg.find((c) => c.conversationId === conversationId);
  console.log(`sau khi A gui tin: fromUserId=${convB.lastMessageFromUserId}, body=${JSON.stringify(convB.lastMessageBody)}, deleted=${convB.lastMessageDeleted}`);

  console.log("\n== A xoa tin do ==");
  A.ws.send(JSON.stringify({ type: "DELETE", id: msgId, conversationId }));
  await waitFor(B.received, (f) => f.type === "DELETE" && f.id === msgId);
  await sleep(500);

  const convsAfterDelete = await getConversations(accountB.token);
  const convB2 = convsAfterDelete.find((c) => c.conversationId === conversationId);
  console.log(`sau khi xoa: fromUserId=${convB2.lastMessageFromUserId}, body=${JSON.stringify(convB2.lastMessageBody)}, deleted=${convB2.lastMessageDeleted}`);

  const pass =
    convB.lastMessageFromUserId === accountA.id &&
    convB.lastMessageBody && convB.lastMessageBody.message === "xin chao ban" &&
    convB.lastMessageDeleted === false &&
    convB2.lastMessageDeleted === true &&
    convB2.lastMessageBody == null;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
