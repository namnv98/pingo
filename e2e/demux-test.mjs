// Verify nhanh: N-shard-link (harbor<->colony, xem ARCHITECTURE.md muc 12) khong lam lo tin nhau
// giua cac session khac nhau (demux dung theo harborSessionId), va dong 1 session khong lam
// gian doan session khac dang dung chung shard link.
//
// Chay duoc voi bat ky harbor dang song nao — local dev (mvn exec:java, xem ARCHITECTURE.md muc 7)
// hoac k3s (NodePort, xem deploy-k3s.sh) — chi can 1 pod/instance colony la du, khong can nhieu pod.
//
// Cach chay:
//   node e2e/demux-test.mjs
//   PINGO_WS_URL=ws://localhost:8888/connect/websocket node e2e/demux-test.mjs   # local dev, khong qua k3s

import { uuid, connect, waitFor } from "./lib.mjs";

const URL = process.env.PINGO_WS_URL ?? "ws://localhost:31003/connect/websocket";

function send(ws, label, frame) {
  console.log(`[${label}] ->`, JSON.stringify(frame));
  ws.send(JSON.stringify(frame));
}

async function main() {
  const userA = uuid();
  const userB = uuid();
  const convA = uuid();
  const convB = uuid();

  console.log(`== connecting A and B toi ${URL} ==`);
  const A = await connect(URL, "A");
  const B = await connect(URL, "B");

  send(A.ws, "A", { type: "AUTH", id: uuid(), fromUserId: userA });
  send(B.ws, "B", { type: "AUTH", id: uuid(), fromUserId: userB });
  await waitFor(A.received, (f) => f.type === "AUTH_OK");
  await waitFor(B.received, (f) => f.type === "AUTH_OK");

  const subA = uuid();
  const subB = uuid();
  send(A.ws, "A", { type: "SUBSCRIBE", id: subA, conversationId: convA, memberUserIds: [userA] });
  send(B.ws, "B", { type: "SUBSCRIBE", id: subB, conversationId: convB, memberUserIds: [userB] });
  await waitFor(A.received, (f) => f.type === "SUBSCRIBE_OK" && f.id === subA);
  await waitFor(B.received, (f) => f.type === "SUBSCRIBE_OK" && f.id === subB);
  console.log("== SUBSCRIBE_OK ca 2 (2 session khac nhau, nhieu kha nang cung 1 shard link) ==");

  const msgA1 = uuid();
  const msgB1 = uuid();
  send(A.ws, "A", { type: "MESSAGE", id: msgA1, conversationId: convA, body: "hello-from-A-1" });
  send(B.ws, "B", { type: "MESSAGE", id: msgB1, conversationId: convB, body: "hello-from-B-1" });

  const deliveredToA1 = await waitFor(A.received, (f) => f.type === "MESSAGE" && f.id === msgA1);
  const deliveredToB1 = await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgB1);
  const crossTalkA = A.received.some((f) => f.type === "MESSAGE" && f.body === "hello-from-B-1");
  const crossTalkB = B.received.some((f) => f.type === "MESSAGE" && f.body === "hello-from-A-1");
  const leaksHarborSessionIdToClient = [...A.received, ...B.received].some((f) => f.harborSessionId != null);

  console.log("== KET QUA VONG 1: demux dung theo harborSessionId ==");
  console.log("A nhan dung message cua minh:", deliveredToA1.body === "hello-from-A-1");
  console.log("B nhan dung message cua minh:", deliveredToB1.body === "hello-from-B-1");
  console.log("A KHONG nhan nham message cua B:", !crossTalkA);
  console.log("B KHONG nhan nham message cua A:", !crossTalkB);
  console.log("harborSessionId KHONG lo ra client (phai la null moi frame client thay):", !leaksHarborSessionIdToClient);

  console.log("== dong session A (mo phong client tat app) ==");
  A.ws.close();
  await new Promise((r) => setTimeout(r, 1500));

  console.log("== B gui tiep 1 tin sau khi A da dong ==");
  const msgB2 = uuid();
  send(B.ws, "B", { type: "MESSAGE", id: msgB2, conversationId: convB, body: "hello-from-B-2-after-A-closed" });
  const deliveredToB2 = await waitFor(B.received, (f) => f.type === "MESSAGE" && f.id === msgB2, 8000);
  console.log("B van nhan duoc tin sau khi A dong (shard link dung chung khong bi giet theo A):", deliveredToB2.body === "hello-from-B-2-after-A-closed");

  B.ws.close();

  const pass =
    deliveredToA1.body === "hello-from-A-1" &&
    deliveredToB1.body === "hello-from-B-1" &&
    !crossTalkA &&
    !crossTalkB &&
    !leaksHarborSessionIdToClient &&
    deliveredToB2.body === "hello-from-B-2-after-A-closed";

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  process.exitCode = pass ? 0 : 1;
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 2;
});
