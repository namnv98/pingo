// Verify PIN: ghim CHUNG fan-out qua WS cho moi thanh vien + GET /pins ca 2 phia deu thay; ghim
// RIENG chi persist (KHONG fan-out gi ca) + GET /pins chi nguoi ghim thay, nguoi con lai KHONG thay.

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

async function getPins(conversationId, token) {
  const res = await fetch(`${DEFAULT_API_BASE}/pins?conversationId=${conversationId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  console.log("== dang ky A, B, tao conversation chung ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);
  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  console.log("\n== A gui 2 tin (msg1: se ghim chung, msg2: se ghim rieng) ==");
  const msg1 = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msg1, conversationId, body: { message: "pin me shared" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msg1);
  const msg2 = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msg2, conversationId, body: { message: "pin me private" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msg2);

  console.log("\n== A ghim CHUNG msg1 -- B phai nhan duoc frame PIN ==");
  A.ws.send(JSON.stringify({ type: "PIN", id: msg1, conversationId, body: { scope: "shared", pinned: true } }));
  const pinFrame = await waitFor(B.received, (f) => f.type === "PIN" && f.id === msg1);
  console.log(`B nhan frame PIN: scope=${pinFrame.body && pinFrame.body.scope}, pinned=${pinFrame.body && pinFrame.body.pinned}`);
  await sleep(800);

  const pinsA1 = await getPins(conversationId, accountA.token);
  const pinsB1 = await getPins(conversationId, accountB.token);
  const sharedInA = pinsA1.find((p) => p.messageId === msg1 && p.scope === "shared");
  const sharedInB = pinsB1.find((p) => p.messageId === msg1 && p.scope === "shared");
  console.log(`GET /pins A thay msg1 (shared): ${!!sharedInA}`);
  console.log(`GET /pins B thay msg1 (shared): ${!!sharedInB}`);

  console.log("\n== A ghim RIENG msg2 -- B KHONG duoc nhan frame PIN nao cho msg2 ==");
  const beforePrivatePinCount = B.received.length;
  A.ws.send(JSON.stringify({ type: "PIN", id: msg2, conversationId, body: { scope: "private", pinned: true } }));
  await sleep(1200);
  const leakedToB = B.received.slice(beforePrivatePinCount).some((f) => f.type === "PIN" && f.id === msg2);
  console.log(`B co nhan leak frame PIN cho msg2 (phai la false): ${leakedToB}`);

  const pinsA2 = await getPins(conversationId, accountA.token);
  const pinsB2 = await getPins(conversationId, accountB.token);
  const privateInA = pinsA2.find((p) => p.messageId === msg2 && p.scope === "private");
  const privateLeakedInB = pinsB2.find((p) => p.messageId === msg2);
  console.log(`GET /pins A thay msg2 (private): ${!!privateInA}`);
  console.log(`GET /pins B KHONG thay msg2 (phai la true): ${!privateLeakedInB}`);

  console.log("\n== A bo ghim CHUNG msg1 -- B phai nhan frame PIN pinned=false, GET /pins het thay ==");
  A.ws.send(JSON.stringify({ type: "PIN", id: msg1, conversationId, body: { scope: "shared", pinned: false } }));
  const unpinFrame = await waitFor(B.received, (f) => f.type === "PIN" && f.id === msg1 && f.body && f.body.pinned === false);
  console.log(`B nhan frame bo ghim: ${!!unpinFrame}`);
  await sleep(800);
  const pinsA3 = await getPins(conversationId, accountA.token);
  const stillPinned = pinsA3.find((p) => p.messageId === msg1 && p.scope === "shared");
  console.log(`GET /pins A het thay msg1 sau khi bo ghim (phai la true): ${!stillPinned}`);

  const pass =
    !!sharedInA && !!sharedInB &&
    !leakedToB &&
    !!privateInA && !privateLeakedInB &&
    !!unpinFrame && !stillPinned;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
