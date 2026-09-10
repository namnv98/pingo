// Verify tab Links: GET /links tra dung URL trich tu body.message (ke ca link xen giua chu khac,
// khong chi tin CHI la 1 link), tin khong co link khong tao dong nao, tin bi xoa mem thi link cua
// no bien mat khoi GET /links (loc qua JOIN messages WHERE deleted_at IS NULL).

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

async function getLinks(conversationId) {
  const res = await fetch(`${DEFAULT_API_BASE}/links?conversationId=${conversationId}&limit=100`);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  console.log("== dang ky A, tao conversation ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const A = await authSession("A", accountA);
  const created = await createConversation(DEFAULT_API_BASE, accountA.token);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  console.log("\n== gui tin co link xen giua chu khac ==");
  const msgWithLink = uuid();
  A.ws.send(JSON.stringify({
    type: "MESSAGE",
    id: msgWithLink,
    conversationId,
    body: { message: "xem cai nay https://example.com/path?x=1 nhe, hay lam" },
  }));
  await waitFor(A.received, (f) => f.type === "MESSAGE" && f.id === msgWithLink);

  console.log("\n== gui tin KHONG co link ==");
  const msgNoLink = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgNoLink, conversationId, body: { message: "khong co gi ca" } }));
  await waitFor(A.received, (f) => f.type === "MESSAGE" && f.id === msgNoLink);

  await sleep(1000);
  const linksAfterSend = await getLinks(conversationId);
  console.log(`GET /links tra ve: ${JSON.stringify(linksAfterSend.map((l) => l.url))}`);
  const found = linksAfterSend.find((l) => l.url === "https://example.com/path?x=1");
  const noExtraFromPlainMessage = linksAfterSend.length === 1;

  console.log("\n== xoa tin co link -- link phai bien mat khoi GET /links ==");
  A.ws.send(JSON.stringify({ type: "DELETE", id: msgWithLink, conversationId }));
  await sleep(1000);
  const linksAfterDelete = await getLinks(conversationId);
  console.log(`GET /links sau khi xoa tin: ${JSON.stringify(linksAfterDelete.map((l) => l.url))}`);
  const goneAfterDelete = !linksAfterDelete.find((l) => l.url === "https://example.com/path?x=1");

  const pass = !!found && noExtraFromPlainMessage && goneAfterDelete;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
