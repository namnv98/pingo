// Helper dung chung cho cac script trong thu muc nay (khong phai unit test Maven, day la
// script Node chay tay/CI ngoai, verify hanh vi thuc te qua WebSocket that + kubectl that).
// Chay bang Node >= 22 (can global WebSocket va crypto.randomUUID co san, khong can cai them
// package nao ca): node e2e/<script>.mjs

export function uuid() {
  return crypto.randomUUID();
}

// NodePort cua colony (khong phai harbor) cho REST API -- xem colony/helm/templates/services.yaml,
// khop voi HISTORY_API_BASE trong demo.html.
export const DEFAULT_API_BASE = "http://localhost:31002";

/** Dang ky 1 tai khoan that qua REST API cua colony (POST /register) -- AUTH gio doi hoi token JWT
 * thay vi tu khai fromUserId (xem ARCHITECTURE.md muc 8, SockjsSocketManager#handleAuth). username
 * mac dinh la 1 uuid() de dam bao unique (UNIQUE constraint) giua cac lan chay test. */
export async function registerUser(apiBase, username = uuid()) {
  const res = await fetch(`${apiBase}/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password: "e2e-test-password" }),
  });
  const body = await res.json();
  if (!res.ok) {
    throw new Error(`register failed for ${username}: HTTP ${res.status} ${JSON.stringify(body)}`);
  }
  return body; // { id, username, token }
}

/** Mo 1 session moi toi harbor, luu MOI frame nhan duoc (khong loc type) vao mang `received` —
 * listener duoc attach NGAY luc connect, truoc ca khi gui bat ky frame nao, de tranh race condition
 * mat frame tra ve qua nhanh (xem ghi chu trong resilience-test.mjs ve bug tung gap phai). */
export function connect(url, label) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const received = [];
    ws.addEventListener("message", (ev) => received.push(JSON.parse(ev.data)));
    ws.addEventListener("open", () => resolve({ label, ws, received }));
    ws.addEventListener("error", reject);
    ws.addEventListener("close", () => console.log(`[${label}] socket closed`));
  });
}

/** Poll mang `received` (append-only) cho tan khi tim thay frame khop `predicate`, hoac timeout. */
export function waitFor(received, predicate, timeoutMs = 6000) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const check = () => {
      const found = received.find(predicate);
      if (found) return resolve(found);
      if (Date.now() - start > timeoutMs) return reject(new Error("timeout waiting for frame"));
      setTimeout(check, 30);
    };
    check();
  });
}

/** Dang ky 1 tai khoan that + AUTH bang token do cap + SUBSCRIBE 1 conversation moi (chi minh
 * session nay la member) — dung chung cho ca 2 script. */
export async function setupSession(url, label, apiBase = DEFAULT_API_BASE) {
  const account = await registerUser(apiBase);
  const conversationId = uuid();
  const s = await connect(url, label);

  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);

  const subId = uuid();
  s.ws.send(JSON.stringify({ type: "SUBSCRIBE", id: subId, conversationId, memberUserIds: [account.id] }));
  const subResult = await waitFor(s.received, (f) => f.id === subId && (f.type === "SUBSCRIBE_OK" || f.type === "SUBSCRIBE_ERROR"));
  if (subResult.type === "SUBSCRIBE_ERROR") throw new Error(`${label} subscribe error: ${subResult.reason}`);

  s.userId = account.id;
  s.conversationId = conversationId;
  return s;
}
