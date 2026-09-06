// Verify read-receipt (SEEN): A gui tin cho B, B gui READ -- A (nguoi GUI, khong phai nguoi doc)
// phai nhan duoc frame SEEN voi id trung id tin nhan de ve dau "da xem".

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

async function main() {
  console.log("== dang ky A (gui), B (doc) ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);
  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  console.log("\n== A gui tin, B nhan duoc roi gui READ ==");
  const msgId = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId, conversationId, body: { message: "hello" } }));
  const received = await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId);
  B.ws.send(JSON.stringify({ type: "READ", id: received.id, conversationId }));

  console.log("\n== A (nguoi GUI) phai nhan duoc frame SEEN cho dung msgId ==");
  const seenFrame = await waitFor(A.received, (f) => f.type === "SEEN" && f.id === msgId && f.fromUserId === accountB.id, 4000);
  console.log(`A nhan duoc SEEN: ${!!seenFrame}, fromUserId=${seenFrame.fromUserId}, conversationId khop=${seenFrame.conversationId === conversationId}`);

  const pass = !!seenFrame && seenFrame.conversationId === conversationId;
  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
