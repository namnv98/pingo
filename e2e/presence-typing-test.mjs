// Verify presence (online/offline broadcast + GET /presence snapshot) va typing indicator (fan-out
// qua colony, khong persist).

import { uuid, connect, waitFor, registerUser, createConversation, DEFAULT_API_BASE } from "./lib.mjs";

const WS_URL = "ws://localhost:31003/connect";
const HERALD_API = "http://localhost:31007";

async function authSession(label, account) {
  const s = await connect(WS_URL, label);
  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);
  s.userId = account.id;
  s.token = account.token;
  return s;
}

async function getPresence(token, userIds) {
  const res = await fetch(`${HERALD_API}/presence?userIds=${userIds.join(",")}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`GET /presence failed: HTTP ${res.status}`);
  return res.json();
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function main() {
  console.log("== dang ky A, B ==");
  const accountA = await registerUser(DEFAULT_API_BASE);
  const accountB = await registerUser(DEFAULT_API_BASE);

  console.log("\n== truoc khi connect: ca 2 phai la offline qua GET /presence ==");
  const beforeConnect = await getPresence(accountA.token, [accountA.id, accountB.id]);
  console.log(JSON.stringify(beforeConnect));

  const A = await authSession("A", accountA);

  console.log("\n== A vua AUTH -- doi 1 chut de PRESENCE broadcast lan toa, kiem tra qua GET /presence ==");
  await sleep(500);
  const afterAConnect = await getPresence(accountA.token, [accountA.id, accountB.id]);
  console.log(JSON.stringify(afterAConnect));

  const B = await authSession("B", accountB);
  console.log("\n== B vua AUTH -- A phai nhan duoc frame PRESENCE (online=true) qua WS cho userId cua B ==");
  const presenceFrame = await waitFor(A.received, (f) => f.type === "PRESENCE" && f.fromUserId === accountB.id, 4000);
  console.log(`A nhan PRESENCE cho B: online=${presenceFrame.body.online}`);

  console.log("\n== tao conversation chung, test TYPING: A go, B phai nhan duoc frame TYPING tu A ==");
  const created = await createConversation(DEFAULT_API_BASE, accountA.token, [accountB.id]);
  const conversationId = created.conversationId;
  await waitFor(A.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);
  await waitFor(B.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === conversationId);

  const typingId = uuid();
  A.ws.send(JSON.stringify({ type: "TYPING", id: typingId, conversationId }));
  const typingFrame = await waitFor(B.received, (f) => f.type === "TYPING" && f.fromUserId === accountA.id && f.conversationId === conversationId, 4000);
  console.log(`B nhan duoc TYPING tu A: ${!!typingFrame}`);

  console.log("\n== dong B, doi B chuyen offline, A phai nhan PRESENCE online=false cho B ==");
  B.ws.close();
  const offlineFrame = await waitFor(A.received, (f) => f.type === "PRESENCE" && f.fromUserId === accountB.id && f.body && f.body.online === false, 5000);
  console.log(`A nhan PRESENCE offline cho B: ${!!offlineFrame}`);

  const afterBClose = await getPresence(accountA.token, [accountB.id]);
  console.log(`GET /presence sau khi B dong: ${JSON.stringify(afterBClose)}`);

  const pass =
    beforeConnect.every((p) => p.online === false) &&
    !!presenceFrame && presenceFrame.body.online === true &&
    !!typingFrame &&
    !!offlineFrame &&
    afterBClose[0].online === false;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;

  A.ws.close();
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
