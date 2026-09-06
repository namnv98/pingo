// Verify DELETE /conversations: xoá hẳn conversation cho MOI thanh vien (messages/notifications/
// membership trong DB), va broadcast CONVERSATION_DELETED cho session dang song cua thanh vien khac.
//
// Chay: node e2e/delete-conversation-test.mjs (can harbor NodePort 31003 + hall NodePort 31002).

import { uuid, connect, waitFor, registerUser, createConversation, DEFAULT_API_BASE } from "./lib.mjs";

const WS_URL = "ws://localhost:31003/connect";

async function authSession(label, account) {
  const s = await connect(WS_URL, label);
  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);
  s.userId = account.id;
  s.token = account.token;
  return s;
}

async function main() {
  console.log("== dang ky A (se xoa), B (thanh vien khac, dang online), C (khong lien quan) ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);
  const accountC = await registerUser(DEFAULT_API_BASE);

  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  console.log(`conversationId = ${conversationId}`);

  console.log("\n== A gui 1 tin de co du lieu trong bang messages ==");
  const msgId = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId, conversationId, body: { message: "hello-truoc-khi-xoa" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId);

  const beforeMessages = await (await fetch(`${DEFAULT_API_BASE}/messages?conversationId=${conversationId}&limit=10`)).json();
  console.log(`messages truoc khi xoa: ${beforeMessages.length} (ky vong >=1)`);

  console.log("\n== C (khong phai thanh vien) thu xoa -- ky vong bi tu choi ==");
  const cResp = await fetch(`${DEFAULT_API_BASE}/conversations?conversationId=${conversationId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${accountC.token}` },
  });
  console.log(`C xoa: HTTP ${cResp.status} (ky vong 404)`);

  console.log("\n== A (thanh vien that) xoa han conversation ==");
  const aResp = await fetch(`${DEFAULT_API_BASE}/conversations?conversationId=${conversationId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${accountA.token}` },
  });
  console.log(`A xoa: HTTP ${aResp.status} (ky vong 200)`);

  console.log("\n== doi B (dang online, da subscribe) nhan frame CONVERSATION_DELETED qua WS ==");
  const deletedFrame = await waitFor(B.received, (f) => f.type === "CONVERSATION_DELETED" && f.conversationId === conversationId, 4000);
  console.log(`B nhan duoc CONVERSATION_DELETED: ${!!deletedFrame}`);

  console.log("\n== kiem tra DB da xoa sach ==");
  const afterMessages = await (await fetch(`${DEFAULT_API_BASE}/messages?conversationId=${conversationId}&limit=10`)).json();
  console.log(`messages sau khi xoa: ${afterMessages.length} (ky vong 0)`);

  const aConvList = await (await fetch(`${DEFAULT_API_BASE}/conversations`, { headers: { Authorization: `Bearer ${accountA.token}` } })).json();
  const stillListed = aConvList.some((c) => c.conversationId === conversationId);
  console.log(`conversation con trong GET /conversations cua A: ${stillListed} (ky vong false)`);

  console.log("\n== xoa lai lan 2 (da xoa roi) -- ky vong 404 (khong con la member) ==");
  const secondDeleteResp = await fetch(`${DEFAULT_API_BASE}/conversations?conversationId=${conversationId}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${accountA.token}` },
  });
  console.log(`xoa lan 2: HTTP ${secondDeleteResp.status} (ky vong 404)`);

  const pass =
    beforeMessages.length >= 1 &&
    cResp.status === 404 &&
    aResp.status === 200 &&
    !!deletedFrame &&
    afterMessages.length === 0 &&
    !stillListed &&
    secondDeleteResp.status === 404;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
