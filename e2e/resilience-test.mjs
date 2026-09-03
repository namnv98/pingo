// Test resilience thuc su tren k3s: N session gui tin lien tuc, giua chung kill LAN LUOT toan bo
// pod colony GOC (khong phai chon ngau nhien 1 pod — xem ARCHITECTURE.md muc 10, 12) -> verify
// khong mat tin nao, ke ca khi nhieu session dang dung chung 1 shard link bi cat cung luc.
//
// Vi sao kill "lan luot CA 3 pod" thay vi 1 pod: chi kill 1 pod ngau nhien co the "trung" dung pod
// khong co session nao dang dung -> test PASS gia tao, khong chung minh duoc gi (bai hoc rut ra
// tu chinh lan chay dau). Script nay chot danh sach pod GOC truoc khi gui tin, roi kill dan tung
// pod trong danh sach do (khong phai pod thay the) -> dam bao MOI conversation, du hash vao pod
// nao, deu trai qua dung 1 lan pod chu cua no chet giua luc dang chat.
//
// LUU Y quan trong ve chinh script nay: moi lan goi kubectl PHAI bat dong bo (child_process.exec,
// khong phai execSync) — execSync se block toan bo Node.js event loop dang chay N WebSocket
// client cung luc, gay "mat tin" GIA (thuc chat la khong gui duoc do chinh script bi dung, khong
// phai loi he thong dang test) — da gap bug nay thuc te, xem git log/PR mo ta.
//
// Yeu cau truoc khi chay:
//   - da deploy len k3s qua ../deploy-k3s.sh (harbor lo ra NodePort, mac dinh 31003)
//   - scale colony len >= 3 replica TRUOC khi chay:
//       kubectl scale deployment colony -n default --replicas=3
//       kubectl rollout status deployment/colony -n default
//   - kubectl trong PATH, KUBECONFIG tro dung cum k3s (mac dinh fallback ~/.kube/k3s.yaml neu chua set)
//
// Sau khi chay xong, NHO scale colony ve lai so replica ban dau (mac dinh 1):
//   kubectl scale deployment colony -n default --replicas=1
//
// Cach chay (co the chinh qua env var, khong can sua code):
//   node e2e/resilience-test.mjs
//   PINGO_NUM_SESSIONS=15 PINGO_TOTAL_DURATION_MS=32000 PINGO_KILL_TIMES_MS=5000,15000,25000 node e2e/resilience-test.mjs

import { exec as execCb, execSync } from "child_process";
import { promisify } from "util";
import { homedir } from "os";
import { uuid, connect, waitFor } from "./lib.mjs";

const exec = promisify(execCb);

const URL = process.env.PINGO_WS_URL ?? "ws://localhost:31003/connect/websocket";
const NAMESPACE = process.env.PINGO_NAMESPACE ?? "default";
const COLONY_LABEL = process.env.PINGO_COLONY_LABEL ?? "app=colony";
const NUM_SESSIONS = Number(process.env.PINGO_NUM_SESSIONS ?? 50);
const SEND_INTERVAL_MS = Number(process.env.PINGO_SEND_INTERVAL_MS ?? 100);
const TOTAL_DURATION_MS = Number(process.env.PINGO_TOTAL_DURATION_MS ?? 60000);
const KILL_TIMES_MS = (process.env.PINGO_KILL_TIMES_MS ?? "10000,30000,50000").split(",").map(Number);
const KUBECONFIG = process.env.KUBECONFIG ?? `${homedir()}/.kube/k3s.yaml`;
const KUBECTL = `KUBECONFIG=${KUBECONFIG} kubectl`;

function sh(cmd) {
  return execSync(cmd, { shell: "/bin/bash" }).toString().trim();
}

async function main() {
  const originalPods = sh(`${KUBECTL} get pods -n ${NAMESPACE} -l ${COLONY_LABEL} -o jsonpath='{.items[*].metadata.name}'`)
    .split(/\s+/)
    .filter(Boolean);
  console.log(`== ${originalPods.length} pod colony GOC se bi kill lan luot: ${originalPods.join(", ")} ==`);
  if (originalPods.length < KILL_TIMES_MS.length) {
    throw new Error(
      `can >= ${KILL_TIMES_MS.length} pod colony (khop so luong KILL_TIMES_MS), chi thay ${originalPods.length} - ` +
        `chay 'kubectl scale deployment colony -n ${NAMESPACE} --replicas=${KILL_TIMES_MS.length}' truoc`
    );
  }

  console.log(`== setting up ${NUM_SESSIONS} sessions toi ${URL} ==`);
  const sessions = [];
  for (let i = 0; i < NUM_SESSIONS; i++) {
    sessions.push(await connect(URL, `S${i}`).then(async (s) => {
      const authId = uuid();
      s.ws.send(JSON.stringify({ type: "AUTH", id: authId, fromUserId: uuid() }));
      await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);
      const conversationId = uuid();
      const subId = uuid();
      s.ws.send(JSON.stringify({ type: "SUBSCRIBE", id: subId, conversationId, memberUserIds: [uuid()] }));
      const subResult = await waitFor(s.received, (f) => f.id === subId && (f.type === "SUBSCRIBE_OK" || f.type === "SUBSCRIBE_ERROR"));
      if (subResult.type === "SUBSCRIBE_ERROR") throw new Error(`${s.label} subscribe error: ${subResult.reason}`);
      s.conversationId = conversationId;
      return s;
    }));
  }
  console.log(`== all ${NUM_SESSIONS} sessions subscribed, starting continuous send loop (${SEND_INTERVAL_MS}ms) ==`);

  const sentIds = new Map();
  sessions.forEach((s) => sentIds.set(s.label, []));

  const startedAt = Date.now();
  const killed = [];

  const sendTimer = setInterval(() => {
    const elapsed = Date.now() - startedAt;
    if (elapsed > TOTAL_DURATION_MS) {
      clearInterval(sendTimer);
      return;
    }
    for (const s of sessions) {
      if (s.ws.readyState !== WebSocket.OPEN) continue;
      const id = uuid();
      s.ws.send(JSON.stringify({ type: "MESSAGE", id, conversationId: s.conversationId, body: `t${elapsed}` }));
      sentIds.get(s.label).push(id);
    }
  }, SEND_INTERVAL_MS);

  for (let k = 0; k < KILL_TIMES_MS.length; k++) {
    setTimeout(async () => {
      const podName = originalPods[k];
      killed.push(podName);
      console.log(`\n>>> [kill ${k + 1}/${KILL_TIMES_MS.length}] force-deleting pod colony GOC: ${podName} at t=${Date.now() - startedAt}ms <<<\n`);
      try {
        await exec(`${KUBECTL} delete pod ${podName} -n ${NAMESPACE} --grace-period=0 --force`);
      } catch (e) {
        console.error(`kill ${podName} failed:`, e.message);
      }
    }, KILL_TIMES_MS[k]);
  }

  await new Promise((r) => setTimeout(r, TOTAL_DURATION_MS + 5000));

  console.log(`\n== da kill du ${killed.length}/${KILL_TIMES_MS.length} pod colony goc: ${killed.join(", ")} ==`);
  console.log("== tallying results ==");
  let totalSent = 0;
  let totalLost = 0;
  const lostDetail = [];
  for (const s of sessions) {
    const ids = sentIds.get(s.label);
    const receivedIds = new Set(s.received.filter((f) => f.type === "MESSAGE").map((f) => f.id));
    totalSent += ids.length;
    const lost = ids.filter((id) => !receivedIds.has(id));
    totalLost += lost.length;
    if (lost.length > 0) lostDetail.push(`${s.label}: ${lost.length} lost`);
    s.ws.close();
  }

  console.log(`\nTOTAL: sent=${totalSent} lost=${totalLost} (${totalSent ? ((totalLost / totalSent) * 100).toFixed(2) : "0.00"}%)`);
  if (lostDetail.length) console.log("Chi tiet mat tin:", lostDetail.join(", "));
  const pass = totalLost === 0 && killed.length === KILL_TIMES_MS.length;
  console.log(pass ? "=== PASS ===" : "=== FAIL ===");
  console.log(`\nNHO scale colony ve lai replica ban dau: kubectl scale deployment colony -n ${NAMESPACE} --replicas=1`);
  process.exitCode = pass ? 0 : 1;
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 2;
});
