// Verify ten conversation luu server (khong con localStorage): POST /conversations voi name, GET
// /conversations tra ve dung name, PUT /conversations doi/xoa ten, va nguoi khong phai thanh vien
// khong doi ten duoc.

import { uuid, registerUser, DEFAULT_API_BASE } from "./lib.mjs";

async function createConversation(token, memberUserIds, name) {
  const res = await fetch(`${DEFAULT_API_BASE}/conversations`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ memberUserIds, name }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(`create failed: HTTP ${res.status} ${JSON.stringify(data)}`);
  return data;
}

async function main() {
  console.log("== dang ky A, B, C ==");
  const A = await registerUser(DEFAULT_API_BASE);
  const B = await registerUser(DEFAULT_API_BASE);
  const C = await registerUser(DEFAULT_API_BASE);

  console.log("\n== A tao group voi B, dat ten 'Team Test' ==");
  const created = await createConversation(A.token, [B.id], "Team Test");
  console.log(`conversationId = ${created.conversationId}, name tra ve luc tao = ${created.name}`);

  console.log("\n== B (thanh vien khac) doc GET /conversations, ky vong thay dung name ==");
  const bList = await (await fetch(`${DEFAULT_API_BASE}/conversations`, { headers: { Authorization: `Bearer ${B.token}` } })).json();
  const bView = bList.find((c) => c.conversationId === created.conversationId);
  console.log(`B thay name = ${bView ? bView.name : "(khong thay conversation)"}`);

  console.log("\n== C (khong phai thanh vien) thu doi ten -- ky vong 404 ==");
  const cResp = await fetch(`${DEFAULT_API_BASE}/conversations?conversationId=${created.conversationId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${C.token}` },
    body: JSON.stringify({ name: "hack" }),
  });
  console.log(`C doi ten: HTTP ${cResp.status} (ky vong 404)`);

  console.log("\n== B (thanh vien that) doi ten thanh 'Renamed By B' ==");
  const renameResp = await fetch(`${DEFAULT_API_BASE}/conversations?conversationId=${created.conversationId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${B.token}` },
    body: JSON.stringify({ name: "Renamed By B" }),
  });
  const renameData = await renameResp.json();
  console.log(`B doi ten: HTTP ${renameResp.status}, name moi = ${renameData.name}`);

  console.log("\n== A doc lai, ky vong thay ten B vua doi (dung chung cho moi thanh vien) ==");
  const aListAfter = await (await fetch(`${DEFAULT_API_BASE}/conversations`, { headers: { Authorization: `Bearer ${A.token}` } })).json();
  const aViewAfter = aListAfter.find((c) => c.conversationId === created.conversationId);
  console.log(`A thay name = ${aViewAfter ? aViewAfter.name : "(khong thay conversation)"}`);

  console.log("\n== A xoa ten (name rong) -- ky vong quay lai null (tu suy label o client) ==");
  const clearResp = await fetch(`${DEFAULT_API_BASE}/conversations?conversationId=${created.conversationId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${A.token}` },
    body: JSON.stringify({ name: "" }),
  });
  const clearData = await clearResp.json();
  console.log(`A xoa ten: HTTP ${clearResp.status}, name = ${clearData.name}`);

  const pass =
    created.name === "Team Test" &&
    bView && bView.name === "Team Test" &&
    cResp.status === 404 &&
    renameResp.status === 200 && renameData.name === "Renamed By B" &&
    aViewAfter && aViewAfter.name === "Renamed By B" &&
    clearResp.status === 200 && clearData.name === null;

  console.log(pass ? "\n=== PASS ===" : "\n=== FAIL ===");
  if (!pass) process.exitCode = 1;
}

main().catch((err) => {
  console.error("TEST ERROR:", err);
  process.exitCode = 1;
});
