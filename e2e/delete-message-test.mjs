// Verify xoa MEM tin nhan: (1) chinh nguoi gui xoa duoc, fan-out DELETE cho subscriber khac, GET
// /messages sau do tra deleted:true + body:null (con qua reload); (2) NGUOI KHAC (khong phai chu
// tin) KHONG xoa duoc tin cua A -- server tu kiem tra lai from_user_id that trong DB, khong tin
// fromUserId client tu khai.

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
  console.log("== dang ky A (gui+xoa), B (chi doc, thu xoa tin cua A -- phai bi tu choi) ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);
  const A = await authSession("A", accountA);
  const B = await authSession("B", accountB);

  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  console.log("\n== A gui tin ==");
  const msgId = uuid();
  A.ws.send(JSON.stringify({ type: "MESSAGE", id: msgId, conversationId, body: { message: "hello, xoa minh di" } }));
  await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgId);

  console.log("\n== B (KHONG phai chu tin) thu xoa tin cua A -- phai bi im lang tu choi ==");
  B.ws.send(JSON.stringify({ type: "DELETE", id: msgId, conversationId }));
  await sleep(800);
  const afterBTry = (await getMessages(conversationId)).find((m) => m.id === msgId);
  const bDeniedCorrectly = !!afterBTry && afterBTry.deleted !== true && afterBTry.body != null;
  console.log(`sau khi B thu xoa: deleted=${afterBTry && afterBTry.deleted}, body=${JSON.stringify(afterBTry && afterBTry.body)}`);

  console.log("\n== A (chu tin) xoa that -- B phai nhan duoc frame DELETE ==");
  A.ws.send(JSON.stringify({ type: "DELETE", id: msgId, conversationId }));
  const deleteFrame = await waitFor(B.received, (f) => f.type === "DELETE" && f.id === msgId);
  console.log(`B nhan frame DELETE: id=${deleteFrame.id}, fromUserId=${deleteFrame.fromUserId}`);

  await sleep(500);
  console.log("\n== 'reload' (GET /messages tu dau) -- kiem tra deleted=true, body=null con qua reload ==");
  const afterReload = (await getMessages(conversationId)).find((m) => m.id === msgId);
  console.log(`sau reload: deleted=${afterReload && afterReload.deleted}, body=${JSON.stringify(afterReload && afterReload.body)}, reactions=${JSON.stringify(afterReload && afterReload.reactions)}`);

  const pass =
    bDeniedCorrectly &&
    !!deleteFrame && deleteFrame.fromUserId === accountA.id &&
    !!afterReload && afterReload.deleted === true && afterReload.body == null;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
  B.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
