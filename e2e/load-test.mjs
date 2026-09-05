// Test tai thuan tuy (throughput/TPS) -- KHONG kill pod gi ca (khac resilience-test.mjs, vốn tra
// loi cau hoi "co mat tin khi chaos khong", khong phai "toi da gui duoc bao nhieu tin/s"). Script
// nay tra loi dung 1 cau hoi: voi N session dong thoi, moi session gui lien tuc voi chu ky
// SEND_INTERVAL_MS, he thong THUC SU xu ly duoc bao nhieu tin nhan/giay (TPS), va do tre (latency,
// tinh tu luc client gui MESSAGE toi luc nhan lai echo) la bao nhieu.
//
// Cach do TPS: KHONG dem so lan goi ws.send() (do la toc do client gui, khong phai toc do he thong
// xu ly xong) -- dem so MESSAGE echo THUC SU nhan lai duoc trong khoang thoi gian do, chia cho thoi
// gian do (wall-clock) -- day moi la thong luong THUC SU he thong dat duoc.
//
// Cach chay (co the chinh qua env var, khong can sua code):
//   node e2e/load-test.mjs
//   PINGO_NUM_SESSIONS=100 PINGO_SEND_INTERVAL_MS=10 PINGO_TOTAL_DURATION_MS=30000 node e2e/load-test.mjs
//   PINGO_API_URL=http://localhost:8085 PINGO_WS_URL=ws://localhost:8888/connect node e2e/load-test.mjs   # local dev, khong qua k3s

import { uuid, connect, waitFor, registerUser, createConversation, DEFAULT_API_BASE } from "./lib.mjs";

const URL = process.env.PINGO_WS_URL ?? "ws://localhost:31003/connect";
const API_URL = process.env.PINGO_API_URL ?? DEFAULT_API_BASE;
const NUM_SESSIONS = Number(process.env.PINGO_NUM_SESSIONS ?? 50);
const SEND_INTERVAL_MS = Number(process.env.PINGO_SEND_INTERVAL_MS ?? 20);
const TOTAL_DURATION_MS = Number(process.env.PINGO_TOTAL_DURATION_MS ?? 20000);
const ACK_TIMEOUT_MS = 8000; // qua han nay ma khong thay MESSAGE/ERROR echo cho 1 id -> tinh la "silent"

function percentile(sortedArr, p) {
  if (sortedArr.length === 0) return 0;
  const idx = Math.min(sortedArr.length - 1, Math.ceil((p / 100) * sortedArr.length) - 1);
  return sortedArr[idx];
}

/** Dang ky + AUTH (token that) + tao 1 conversation rieng qua POST /conversations, giong
 * setupSession trong lib.mjs nhung gan them 1 listener rieng (khong dua vao mang s.received chung)
 * de theo doi latency theo thoi gian thuc thay vi phai quet lai toan bo mang moi lan -- quan trong
 * khi tong so tin co the len toi hang chuc nghin trong 1 lan chay tai. `stats` la object dung
 * chung giua moi session. Conversation phai tao qua REST truoc (khong con lazy-create qua
 * SUBSCRIBE nua, xem ARCHITECTURE.md muc 12); khong con tu SUBSCRIBE -- harbor tu wake-subscribe
 * nho broadcast membership-changed luc tao. */
async function setupLoadSession(label, stats) {
  const account = await registerUser(API_URL);
  const s = await connect(URL, label);

  const authId = uuid();
  s.ws.send(JSON.stringify({ type: "AUTH", id: authId, token: account.token }));
  await waitFor(s.received, (f) => f.type === "AUTH_OK" && f.id === authId);

  const created = await createConversation(API_URL, account.token);
  await waitFor(s.received, (f) => f.type === "CONVERSATION_ADDED" && f.conversationId === created.conversationId);

  s.conversationId = created.conversationId;
  s.pending = new Map(); // id (tin da gui, chua co phan hoi) -> timestamp luc gui (performance.now(), ms)
  s.ws.addEventListener("message", (ev) => {
    const f = JSON.parse(ev.data);
    if (f.type !== "MESSAGE" && f.type !== "ERROR") return;
    const sentAt = s.pending.get(f.id);
    if (sentAt === undefined) return; // khong phai phan hoi cho 1 tin session nay dang doi (vd frame khac)
    s.pending.delete(f.id);
    if (f.type === "MESSAGE") {
      stats.latencies.push(performance.now() - sentAt);
      stats.delivered++;
    } else {
      stats.errored++;
    }
  });
  return s;
}

function sumPending(sessions) {
  return sessions.reduce((sum, s) => sum + s.pending.size, 0);
}

async function main() {
  const stats = { delivered: 0, errored: 0, latencies: [] };

  const targetTps = ((NUM_SESSIONS * 1000) / SEND_INTERVAL_MS).toFixed(0);
  console.log(`== dang ky + connect ${NUM_SESSIONS} session toi ${URL} (target ~${targetTps} msg/s neu khong nghen) ==`);

  const sessions = [];
  for (let i = 0; i < NUM_SESSIONS; i++) {
    sessions.push(await setupLoadSession(`S${i}`, stats));
  }
  console.log(`== ${NUM_SESSIONS} session da subscribe xong, bat dau gui lien tuc trong ${TOTAL_DURATION_MS}ms ==`);

  let totalSent = 0;
  const startedAt = performance.now();

  const sendTimer = setInterval(() => {
    for (const s of sessions) {
      if (s.ws.readyState !== WebSocket.OPEN) continue;
      const id = uuid();
      s.pending.set(id, performance.now());
      s.ws.send(JSON.stringify({ type: "MESSAGE", id, conversationId: s.conversationId, body: `load-${totalSent}` }));
      totalSent++;
    }
  }, SEND_INTERVAL_MS);

  // Log tien do dinh ky (~moi 2s) de theo doi truc tiep trong luc chay, khong phai doi het moi thay gi.
  const progressTimer = setInterval(() => {
    const elapsedSec = (performance.now() - startedAt) / 1000;
    const liveTps = (stats.delivered / elapsedSec).toFixed(0);
    console.log(`  [t=${elapsedSec.toFixed(1)}s] sent=${totalSent} delivered=${stats.delivered} errored=${stats.errored} pending=${sumPending(sessions)} running_tps=~${liveTps}/s`);
  }, 2000);

  await new Promise((r) => setTimeout(r, TOTAL_DURATION_MS));
  clearInterval(sendTimer);
  const sendPhaseEndedAt = performance.now();
  console.log(`== het thoi gian gui (${totalSent} tin da gui) -- doi toi ${ACK_TIMEOUT_MS}ms cho cac phan hoi con tre ==`);

  // Doi them cho cac tin gui cuoi cung con dang bay tren duong ve kip toi, thay vi tinh nham la silent.
  await new Promise((r) => setTimeout(r, ACK_TIMEOUT_MS));
  clearInterval(progressTimer);

  const totalSilent = sumPending(sessions);
  const measuredDurationSec = (sendPhaseEndedAt - startedAt) / 1000;
  const tps = (stats.delivered / measuredDurationSec).toFixed(1);
  const latencies = stats.latencies.slice().sort((a, b) => a - b);

  for (const s of sessions) s.ws.close();

  console.log(`\n=== KET QUA TAI (${NUM_SESSIONS} session, chu ky gui ${SEND_INTERVAL_MS}ms/session, ${measuredDurationSec.toFixed(1)}s) ===`);
  console.log(`Tong gui:      ${totalSent}`);
  console.log(`Delivered:     ${stats.delivered} (${((stats.delivered / totalSent) * 100).toFixed(2)}%)`);
  console.log(`Errored:       ${stats.errored} (server tra loi ERROR ro rang, khong phai im lang)`);
  console.log(`Silent:        ${totalSilent} (khong nhan duoc gi ca trong ${ACK_TIMEOUT_MS}ms - mat tin thuc su)`);
  console.log(`\nThong luong THUC DAT DUOC: ~${tps} tin/s (dua tren so delivered / thoi gian gui thuc te ${measuredDurationSec.toFixed(1)}s)`);
  if (latencies.length > 0) {
    console.log(`\nDo tre round-trip (client gui -> nhan lai echo), ms:`);
    console.log(`  min=${latencies[0].toFixed(0)}  p50=${percentile(latencies, 50).toFixed(0)}  p95=${percentile(latencies, 95).toFixed(0)}  p99=${percentile(latencies, 99).toFixed(0)}  max=${latencies[latencies.length - 1].toFixed(0)}`);
  }
  console.log(
    `\nGoi y: neu p95/p99 tang manh hoac errored/silent > 0 o muc tai nay, day la dau hieu da vuot` +
      ` nguong xu ly thoai mai cua he thong -- thu giam PINGO_NUM_SESSIONS/tang PINGO_SEND_INTERVAL_MS` +
      ` de tim nguong on dinh, hoac nguoc lai de tim gioi han tren.`
  );

  process.exitCode = totalSilent === 0 ? 0 : 1;
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 2;
});
