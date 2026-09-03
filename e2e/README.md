# e2e — script test tay qua WebSocket + kubectl thật

Không phải unit test Maven (chạy độc lập bằng `mvn test`) — đây là script Node chạy tay/CI ngoài,
verify hành vi thật của `harbor`/`colony` qua WebSocket thật (không mock) và (với `resilience-test.mjs`)
qua `kubectl` thật trên cụm k3s đang chạy. Bắt nguồn từ việc test đổi shard-link harbor↔colony
(xem `ARCHITECTURE.md` mục 12), giữ lại để tái sử dụng cho các lần đổi sau ở tầng `ws/`.

Yêu cầu: Node.js >= 22 (dùng `WebSocket`/`crypto.randomUUID()` toàn cục có sẵn, không cần cài thêm
package nào — không có `package.json` trong thư mục này, cố tình để vậy cho gọn).

## `demux-test.mjs` — verify nhanh, không cần k8s

2 session độc lập AUTH + SUBSCRIBE 2 conversation khác nhau, gửi tin đồng thời, kiểm tra:
- không cross-talk giữa 2 session (demux đúng theo `harborSessionId` trên shard link dùng chung),
- `harborSessionId` không lộ ra client (phải bị strip trước khi relay, xem `SockjsSocketManager.sendToClient`),
- đóng 1 session không làm gián đoạn session còn lại đang dùng chung shard link.

Chạy được với bất kỳ `harbor` nào đang sống — local dev (`mvn exec:java`, xem `ARCHITECTURE.md` mục 7)
hay k3s — chỉ cần 1 pod/instance `colony` là đủ, không cần nhiều pod.

```bash
node e2e/demux-test.mjs                                                          # mặc định trỏ k3s NodePort 31003
PINGO_WS_URL=ws://localhost:8888/connect/websocket node e2e/demux-test.mjs       # local dev, không qua k3s
```

## `resilience-test.mjs` — test tải + kill pod thật trên k3s

N session gửi tin liên tục, giữa chừng **kill lần lượt TOÀN BỘ pod `colony` gốc** (chốt danh sách
trước khi gửi tin, không phải chọn ngẫu nhiên 1 pod — chọn ngẫu nhiên có thể "trúng" đúng pod không
ai dùng, test pass giả tạo, không chứng minh được gì — bài học rút ra từ chính lần chạy đầu). Verify
0% mất tin dù nhiều session đang dùng chung 1 shard link bị cắt cùng lúc.

**Trước khi chạy**, cần đã `../deploy-k3s.sh` xong và scale `colony` lên đủ số pod (mặc định script
kill 3 lần, nên cần >= 3 pod):

```bash
export KUBECONFIG=~/.kube/k3s.yaml   # hoặc bất kỳ đâu trỏ đúng cụm k3s của bạn
kubectl scale deployment colony -n default --replicas=3
kubectl rollout status deployment/colony -n default

node e2e/resilience-test.mjs

# xong nhớ scale lại như cũ:
kubectl scale deployment colony -n default --replicas=1
```

Tuỳ chỉnh qua biến môi trường (không cần sửa code):

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `PINGO_WS_URL` | `ws://localhost:31003/connect/websocket` | endpoint harbor |
| `PINGO_NAMESPACE` | `default` | namespace k8s |
| `PINGO_COLONY_LABEL` | `app=colony` | label selector để tìm pod colony |
| `PINGO_NUM_SESSIONS` | `50` | số session đồng thời |
| `PINGO_SEND_INTERVAL_MS` | `100` | chu kỳ gửi tin/session |
| `PINGO_TOTAL_DURATION_MS` | `60000` | tổng thời gian gửi tin |
| `PINGO_KILL_TIMES_MS` | `10000,30000,50000` | mốc thời gian (ms, tính từ lúc bắt đầu gửi) kill từng pod — số phần tử = số pod cần có sẵn |

Ví dụ chạy nhanh, tải nhẹ (như lần test 15-session ban đầu):

```bash
kubectl scale deployment colony -n default --replicas=3 && kubectl rollout status deployment/colony -n default
PINGO_NUM_SESSIONS=15 PINGO_TOTAL_DURATION_MS=32000 PINGO_KILL_TIMES_MS=5000,15000,25000 node e2e/resilience-test.mjs
kubectl scale deployment colony -n default --replicas=1
```

**Lưu ý quan trọng nếu sửa script này**: mọi lệnh `kubectl` gọi giữa lúc đang gửi tin (đặc biệt lúc
kill pod) PHẢI bất đồng bộ (`child_process.exec`, không phải `execSync`) — `execSync` block toàn bộ
Node.js event loop đang chạy N WebSocket client cùng lúc, gây "mất tin" giả (thực chất là script tự
đứng hình chứ không phải hệ thống đang test có lỗi) — đã gặp bug này thật khi tăng tải lên 50 session,
mất y hệt số lượng tin ở mọi session (dấu hiệu rõ ràng của lỗi harness, không phải lỗi hệ thống thật).
