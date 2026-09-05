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

/** Goi POST /conversations (hall) that -- conversation gio PHAI duoc tao qua day truoc (khong con
 * lazy-create qua SUBSCRIBE nua, xem ARCHITECTURE.md muc 12). memberUserIds co the rong (conversation
 * chi minh nguoi goi la member, dung cho cac test do round-trip doc lap tung session). */
export async function createConversation(apiBase, token, memberUserIds = []) {
  const res = await fetch(`${apiBase}/conversations`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ memberUserIds }),
  });
  const body = await res.json();
  if (!res.ok) {
    throw new Error(`create conversation failed: HTTP ${res.status} ${JSON.stringify(body)}`);
  }
  return body; // { conversationId, memberUserIds }
}

/** Dang ky 1 tai khoan that + AUTH bang token do cap + tao 1 conversation moi (chi minh session nay
 * la member, qua POST /conversations) — dung chung cho ca 2 script. PHAI AUTH truoc roi moi tao
 * conversation (khong phai nguoc lai): broadcast membership-changed luc tao chi bao duoc session
 * DANG SONG va DA AUTH tai thoi diem no ban ra (xem RoutingVersionSync#onMembershipChanged ben
 * harbor) — tao truoc khi AUTH se lam broadcast bay qua trong luc chua co ai lang nghe, mat vinh
 * vien. Khong con can tu gui SUBSCRIBE nua: harbor tu wake-subscribe nho broadcast do. */
export async function setupSession(url, label, apiBase = DEFAULT_API_BASE) {
  const account = await registerUser(apiBase);
  const s = await connect(url, label);

  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);

  const created = await createConversation(apiBase, account.token);
  await waitFor(s.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === created.conversationId);

  s.userId = account.id;
  s.conversationId = created.conversationId;
  return s;
}
