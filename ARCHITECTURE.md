# Kiến trúc hệ thống Pingo Chat

Tài liệu này mô tả tổng thể kiến trúc, luồng dữ liệu, và các quyết định thiết kế của hệ thống chat
gồm 4 module: `discovery`, `beacon`, `harbor`, `colony`.

## 1. Tổng quan

- Toàn bộ service viết trên **Vert.x**, giao tiếp nội bộ qua **EventBus** đã clustering bằng
  **Hazelcast** (mọi node trong cùng 1 cluster thấy chung 1 EventBus logic).
- Routing tin nhắn tới đúng backend node dùng **consistent hashing (thuật toán Maglev)** dựa trên
  `conversationId` (KHÔNG phải `userId`) — 1-1 (DM) và group chat dùng chung 1 model: DM chỉ là 1
  conversation 2 thành viên, `conversationId` tính tất định từ cặp userId (xem mục 3, 11). Khi số
  lượng pod colony thay đổi, chỉ một phần nhỏ conversation bị "route lại", không phải toàn bộ.
- `beacon` là control-plane: theo dõi pod colony nào đang sống, phát (gossip) danh sách đó
  cho `harbor` và `colony`.
- `harbor` là cửa ngõ public (client kết nối vào qua SockJS/WebSocket), không giữ trạng
  thái chat — chỉ relay. Với mỗi pod colony, giữ **N link song song (shard) dùng chung cho MỌI
  client session trên node harbor này** (không phải 1 link/session, cũng không phải 1 link/pod —
  xem mục 12) — chọn shard theo hash ổn định của session id, nên 1 session luôn rơi vào đúng 1 shard
  trong suốt vòng đời của nó.
- `colony` là nơi thật sự giữ subscriber (không phải "session theo user") của từng conversation và
  deliver tin nhắn cho đúng subscriber cục bộ, hoặc forward sang đúng pod đang sở hữu conversation đó.
  Từ mục 12, 1 subscriber ứng với 1 harbor session cụ thể chứ không còn ứng với 1 connection WebSocket
  vật lý — nhiều subscriber có thể cùng dùng chung 1 connection.
- `discovery` là thư viện dùng chung (routing, versioning, connector) — không phải service độc lập,
  không có `main()`.

```mermaid
graph TB
    subgraph client["Client"]
        Browser["Browser / app<br/>(SockJS/WebSocket client)"]
    end

    subgraph gw["harbor (public, nhiều pod)"]
        GW1["Pod gateway-1<br/>:8888 public"]
    end

    subgraph chat["colony (backend, nhiều pod)"]
        MC1["Pod chat-1<br/>:9999 backend WS"]
        MC2["Pod chat-2<br/>:9999 backend WS"]
    end

    subgraph sig["beacon (control-plane, 1 pod)"]
        SIG["BeaconBoot"]
    end

    K8S["k8s API<br/>(watch pod colony)"]

    Browser -- "1 WebSocket/SockJS<br/>AUTH, SUBSCRIBE, MESSAGE, PING" --> GW1
    GW1 -- "N shard link/pod<br/>(dùng chung cho MỌI session trên harbor này)" --> MC1
    GW1 -.->|"nếu conversation khác hash ra pod khác"| MC2

    SIG -- "watch pods" --> K8S
    SIG -- "EventBus: beacon_init (request/reply)<br/>beacon (publish khi có thay đổi)" --> GW1
    SIG -- "EventBus: beacon_init, beacon" --> MC1
    SIG -- "EventBus: beacon_init, beacon" --> MC2

    MC1 -- "EventBus point-to-point<br/>tới địa chỉ = tên pod nhận<br/>(cross-node message forward, route theo conversationId)" --> MC2
```

## 2. Vai trò từng module

| Module | Có `main()`? | Vai trò |
|---|---|---|
| `discovery` | Không (thư viện) | `PingoConnector`/`VersionVector`/`Router` (Maglev) — client-side: tra routing table biết `conversationId` thuộc pod nào, gửi tin nhắn cross-node qua EventBus. `Router`/`Maglev`/`VersionVector` hoàn toàn generic (route theo `RoutingKey.hash()`, không biết gì về userId/conversationId) — sự khác biệt nằm ở `RouteByUserIdRequest`/`RouteByConversationIdRequest`, 2 implementation cụ thể của `RoutingKey`. Định nghĩa chung `SocketFrame`-adjacent DTO cho gossip (`Payload`, `SignalingResponse`), `Destination`, `Keeper` (snapshot). |
| `beacon` | Có | Control-plane duy nhất nói chuyện trực tiếp với **k8s API** (`K8SKeeper`, watch pod colony theo label `app=colony`). Gossip danh sách destination qua EventBus (`RoutingGossipPublisher`). Không đụng gì tới việc đổi routing key sang `conversationId` — chỉ gossip topology pod (ADD/REMOVE), không biết gì về conversation. Chạy local (không k8s) thì dùng `LocalKeeper` với 1 destination cố định. |
| `harbor` | Có | Public-facing. Nhận SockJS/WebSocket từ client thật, xác thực (AUTH, chỉ xử lý cục bộ) rồi, khi client SUBSCRIBE 1 `conversationId`, mở (hoặc dùng lại) 1 gRPC stream xuống đúng pod colony sở hữu conversation đó (1 stream riêng cho mỗi cặp (session, pod), xem mục 12), relay frame 2 chiều. `BackendLinkGateway` chỉ còn giữ CHUNG 1 `GrpcClient` (= 1 pool connection HTTP/2 vật lý)/pod colony — HTTP/2 tự multiplex mọi stream của mọi session trên đó, không còn cần tự chia shard. |
| `colony` | Có | Nhận MESSAGE, deliver thẳng nếu conversation có subscriber cục bộ, hoặc forward qua EventBus sang đúng pod đang sở hữu conversation đó (theo Maglev hash của `conversationId`). **Không kết nối trực tiếp với browser** — nhận kết nối gRPC (`Link.Stream`) từ harbor, 1 `ChatSession` = 1 gRPC stream = 1 harbor session, port 9999 không public ra ngoài (xem mục 12). |

## 3. Giao thức `SocketFrame` (chặng client↔harbor)

**Chỉ mô tả chặng client↔harbor** — chặng harbor↔colony dùng 1 giao thức khác hẳn (protobuf/gRPC,
xem mục 12) kể từ khi chuyển sang gRPC; trước đó 2 chặng dùng chung 1 envelope JSON, không còn nữa.

Client↔harbor dùng 1 envelope JSON:

```json
{
  "type": "AUTH | AUTH_OK | AUTH_ERROR | SUBSCRIBE | SUBSCRIBE_OK | SUBSCRIBE_ERROR | MESSAGE | ACK | ERROR | PING | PONG",
  "id": "correlation id — client tự sinh, server echo lại trong ACK/ERROR/SUBSCRIBE_OK/SUBSCRIBE_ERROR",
  "fromUserId": "sender — server tự gán khi xử lý MESSAGE, không tin giá trị client gửi lên",
  "toUserId": "recipient — CHỈ còn mang tính tiện lợi cho client (DM), KHÔNG dùng để route",
  "conversationId": "id conversation (DM hoặc group) — routing key thật sự, bắt buộc với MESSAGE/SUBSCRIBE",
  "memberUserIds": "chỉ có ý nghĩa với SUBSCRIBE — danh sách userId cần union vào membership (lazy-create/join)",
  "body": "payload tuỳ ý",
  "reason": "lý do lỗi — chỉ có ở ERROR/AUTH_ERROR/SUBSCRIBE_ERROR",
  "ts": "epoch millis, server đóng dấu"
}
```

Gửi dạng **TEXT frame** (không phải binary) — bắt buộc, vì browser/JS `WebSocket` mặc định trả
`event.data` là `Blob` cho binary frame, `JSON.parse()` sẽ lỗi ngay (xem
`harbor/.../session/SockjsSocket.java`).

**`AUTH` tách hẳn khỏi việc mở kết nối backend** (khác thiết kế ban đầu): giờ chỉ xác định danh
tính (`userId`) cho session, xử lý 100% cục bộ tại `harbor`, không đụng gì tới `colony`. `SUBSCRIBE`
mới là handshake thật sự mở/dùng lại backend link — xem mục 4, 11.

Với DM, client vẫn có thể chỉ gửi `toUserId` (không tự tính `conversationId`) — `harbor` tự suy ra
bằng hàm dùng chung `ConversationIds.dmId(fromUserId, toUserId)` (`core/commons-lang`, XOR 2 nửa
UUID, đối xứng — `dmId(A,B) == dmId(B,A)`).

## 4. Luồng AUTH + SUBSCRIBE + gửi tin nhắn (2 user khác pod colony)

**Lưu ý quan trọng**: `colony` không bao giờ giữ kết nối trực tiếp tới browser. Client luôn
chỉ nói chuyện với `harbor` qua SockJS; 1 "subscriber" mà `colony` giữ cho 1 conversation chính là
link WebSocket từ gateway (đăng ký lúc SUBSCRIBE). Vì vậy khi B nhận được tin nhắn, đường đi luôn
phải vòng lại qua đúng gateway đang giữ kết nối SockJS thật của B — dưới đây giả định A và B đang ở
cùng 1 gateway để sơ đồ gọn, nhưng về nguyên tắc mỗi client có thể ở 1 pod gateway khác nhau (gateway
không giữ trạng thái nên không quan trọng client nối vào pod gateway nào).

**Client PHẢI chủ động SUBSCRIBE trước khi có thể NHẬN tin nhắn của 1 conversation** — kể cả DM.
Đây là khác biệt hành vi lớn nhất so với thiết kế cũ (trước đây AUTH xong là tự động "online" nhận
được mọi tin gửi tới `toUserId` mình): giờ mỗi session phải đăng ký quan tâm (subscribe) rõ ràng
từng conversation, giống mô hình "join channel" hơn là "always-on theo userId".

```mermaid
sequenceDiagram
    participant A as Client A
    participant GW as harbor
    participant MC_A as colony (pod sở hữu conv(A,B))
    participant MC_B as colony (pod khác, giữ session B tới GW)
    participant B as Client B

    A->>GW: AUTH {fromUserId: A}
    GW-->>A: AUTH_OK (cục bộ, chưa đụng backend)

    B->>GW: AUTH {fromUserId: B}
    GW-->>B: AUTH_OK (cục bộ)

    A->>GW: SUBSCRIBE {conversationId: dmId(A,B), memberUserIds: [A,B]}
    GW->>GW: routing(dmId(A,B)) qua PingoConnector (Maglev, key = conversationId)
    GW->>MC_A: mở/dùng lại backend link theo POD + SUBSCRIBE {fromUserId:A, conversationId, memberUserIds}
    MC_A->>MC_A: union memberUserIds vào ChannelMembershipRegistry, check isMember(A)
    MC_A-->>GW: SUBSCRIBE_OK
    GW-->>A: SUBSCRIBE_OK

    B->>GW: SUBSCRIBE {conversationId: dmId(A,B)} (không cần gửi lại memberUserIds — đã union sẵn)
    GW->>MC_A: (routing trùng pod A) SUBSCRIBE {fromUserId:B, conversationId}
    MC_A-->>GW: SUBSCRIBE_OK
    GW-->>B: SUBSCRIBE_OK

    A->>GW: MESSAGE {id, toUserId: B, body}
    GW->>GW: suy ra conversationId = dmId(A,B) (frame không tự gửi thì tự tính)
    GW->>MC_A: forward MESSAGE (gán fromUserId=A, conversationId) trên backend link
    MC_A->>MC_A: deliverLocally(conversationId) → có 2 subscriber cục bộ (link A, link B)
    MC_A-->>GW: push MESSAGE trên cả 2 link (kể cả link A — không suppress self-echo, xem mục 8)
    GW-->>A: relay MESSAGE
    GW-->>B: relay MESSAGE
    MC_A-->>GW: ACK {id} trên link A
    GW-->>A: ACK {id}
```

**Điểm mấu chốt cho cross-node delivery đúng**: mỗi pod colony lắng nghe EventBus trên
địa chỉ = **chính định danh của nó** (`serverId` — tên pod k8s, hoặc giá trị fallback khi chạy local
dev), KHÔNG dùng chung 1 địa chỉ tĩnh cho mọi pod. Nếu dùng chung địa chỉ, EventBus sẽ round-robin
tin nhắn tới bất kỳ pod nào đang lắng nghe chứ không phải đúng pod sở hữu conversation.

## 5. Luồng đồng bộ routing table (beacon → gateway/chat)

```mermaid
sequenceDiagram
    participant K8s as k8s API
    participant SIG as beacon (K8SKeeper)
    participant EB as EventBus (clustered)
    participant Node as harbor / colony

    Node->>EB: request("beacon_init") — lúc khởi động
    EB->>SIG: forward request
    SIG-->>Node: reply SignalingResponse {version, destinations[]}
    Node->>Node: PingoConnector.add(version, destinations) → build VersionVector + Maglev router

    K8s--)SIG: pod ADD/MODIFIED/DELETED event (watch stream)
    SIG->>SIG: gom event 5s (queue), version++
    SIG->>EB: publish("beacon", Payload{version, changeEvents[]})
    EB--)Node: mọi node đang subscribe "beacon" đều nhận
    Node->>Node: addDestinationChangeEvent() → router tự cập nhật<br/>(gateway còn tự re-subscribe từng conversation đang mở sang pod mới nếu pod sở hữu đổi)
```

`beacon` tự chọn giữa 2 chế độ lúc khởi động (`BeaconAppModule`):
- Có biến môi trường `KUBERNETES_SERVICE_HOST` (kubelet luôn tự set khi chạy trong pod k8s) →
  dùng `K8SKeeper`, watch thật qua k8s API.
- Không có → dùng `LocalKeeper`, 1 destination cố định `eb_gossip_adm @ 127.0.0.1` (chạy local dev,
  không cần k8s).

`beacon` hoàn toàn không đổi bởi việc chuyển routing key sang `conversationId` — nó chỉ gossip
topology pod, không biết gì về conversation/user.

## 6. Cấu trúc package `ws/` (harbor & colony)

Cả 2 module tổ chức package theo cùng 1 quy ước để đọc là hiểu ngay vai trò:

```
harbor/ws/              colony/ws/
  dto/                              (không còn dto/ — colony không có wire format
    MessageType                      riêng nào nữa, chặng harbor↔colony dùng thẳng
    SocketFrame                      Frame/FrameType sinh từ discovery/*.proto)
    SocketFrames   (frame builder)
  session/                          session/
    SockjsSocket   (1 client conn,     ChatSession    (1 gRPC stream = 1 harbor
     giữ backendStreams: Map<pod,       session, giữ userId/conversationIds
     BackendStream>)                    riêng, send() = response.write(Frame))
    BackendStream  (1 gRPC stream      SessionRegistry (1 tầng: tra theo
     = 1 cặp (session,pod))              userId VÀ conversationId)
    MessageSocket  (interface)
  backend/                          delivery/
    BackendLinkGateway                 MessageDelivery
    (1 GrpcClient/pod dùng CHUNG        (deliver local / forward EventBus,
     cho mọi session — HTTP/2 tự        payload giờ là Frame.toByteArray(),
     multiplex; single-flight           không phải JSON — xem mục 12)
     connect, evict-on-proven-       membership/
     failure — xem mục 12)              ChannelMembershipRegistry
  routing/                              (Postgres, ai thuộc conversation nào)
    RoutingVersionSync                routing/
    (đồng bộ version + re-subscribe    RoutingVersionSync
     từng (session,conversationId)     (đồng bộ version, không cần di chuyển
     đang mở khi pod sở hữu đổi)        gì — chat không chủ động dial ra)
  SockjsSocketManager (cửa trước)   ChatSessionManager (cửa trước)
  SockjsSocketServer  (verticle)    ChatSocketServer   (verticle, mount GrpcServer
                                     lên HttpServer HTTP/2 thuần — xem mục 12)
```

- **`dto`** (chỉ còn ở harbor) — định dạng frame trên dây cho chặng client↔harbor (JSON). Chặng
  harbor↔colony không còn dto riêng — dùng thẳng `Frame`/`FrameType` sinh từ
  `discovery/src/main/proto/link.proto` (1 schema dùng chung, compile 1 lần — xem mục 12).
- **`session`** — biểu diễn 1 connection/stream đang sống + tra cứu. HTTP/2 tự multiplex nhiều
  `BackendStream`/`ChatSession` trên CHUNG 1 connection vật lý, nên không còn cần tách connection vật
  lý khỏi subscriber logic như thời N-shard-link nữa (xem mục 12).
- **`backend`/`delivery`** — quyết định tin nhắn phải đi đâu và đưa nó đi.
- **`membership`** (chỉ colony) — ai thuộc conversation nào, lưu bền trong Postgres (xem mục 8).
- **`routing`** — biết pod nào đang sở hữu conversation nào (dựa trên gossip từ beacon).
- File ở gốc package (`*Manager`, `*Server`) — nơi ráp mọi thứ lại, là "cửa trước" nhận connection
  và dispatch protocol.

## 7. Chạy local (không cần k8s)

Cả 3 service (`beacon`, `colony`, `harbor`) chạy độc lập trên cùng máy, tự ghép
cụm Hazelcast qua multicast (không cần cấu hình thêm):

```bash
mvn -pl beacon exec:java -Dexec.mainClass=com.lego.beacon.BeaconBoot
mvn -pl colony exec:java -Dexec.mainClass=com.lego.colony.ColonyBoot
mvn -pl harbor exec:java -Dexec.mainClass=com.lego.harbor.HarborBoot
```

Client test thủ công: mở `demo.html` (ở root repo) trong browser (SockJS raw-websocket transport
tới `ws://localhost:8888/connect/websocket`, hoặc `ws://localhost:31003/connect/websocket` khi test
qua NodePort của cụm k3s local). Có sẵn UI cho cả 3 bước AUTH → SUBSCRIBE (DM/group) → MESSAGE.

**Lưu ý môi trường**: Hazelcast local mặc định dùng multicast + tên cluster `"dev"` (không cách ly)
— nếu chạy nhiều lần/nhiều máy trên cùng mạng có thể thấy member lạ join rồi rớt ngay trong log,
không ảnh hưởng chức năng nhưng dễ gây nhầm khi debug.

## 8. Các quyết định/giới hạn đáng lưu ý

- **Auth chưa có credential thật** — AUTH hiện chỉ tin `fromUserId` client tự khai, chưa verify bằng
  token/JWT. Phù hợp cho prototype, cần bổ sung nếu lên production thật.
- **ACK chỉ ở mức transport** — `ACK` nghĩa là "gateway/chat đã nhận và chuyển tiếp thành công",
  KHÔNG đảm bảo mọi subscriber thực sự nhận được (không có ack 2 chiều xuyên node).
- **Không suppress self-echo** — `MessageDelivery.deliverLocally` fan-out cho MỌI subscriber cục bộ
  của 1 conversation, kể cả chính người gửi nếu họ cũng đang subscribe (điều này LUÔN đúng với DM,
  vì cả 2 phía đều phải subscribe). Cố tình để vậy — client tự dedupe qua `id` nếu cần, tránh thêm
  1 tầng phức tạp cho lợi ích nhỏ.
- **Không có persistence lịch sử tin nhắn** — chỉ tin nhắn "đang bay" được deliver real-time, không
  lưu lại đâu để replay/xem lịch sử. `ChannelMembershipRegistry` (ai thuộc conversation nào) **đã**
  chuyển sang lưu bền (persistent) trong Postgres — mọi pod colony đều đọc/ghi thẳng bảng
  `conversation_members`, không còn in-memory-theo-pod như thiết kế ban đầu ở mục 11 nữa. `harbor`
  vẫn giữ cơ chế "nhớ" lại `memberUserIds` (`SockjsSocket.membersByConversation`) và gửi kèm mỗi lần
  SUBSCRIBE/reconnect như một lớp phòng hộ thêm (không còn bắt buộc để khôi phục membership sau khi
  pod restart như trước, vì DB đã giữ), nhưng vẫn hữu ích để giảm 1 vòng query cho lần subscribe lại.
- **Heartbeat**: gateway↔client và gateway↔backend đều có PING/PONG riêng. Phía backend, từ khi link
  được sharded và dùng chung cho nhiều session (xem mục 12), heartbeat theo từng **shard link**
  (không phải theo session, và không còn theo từng session như bản trước đó của tài liệu này) — 1
  shard link không phản hồi trong ~60s bị coi là chết và dọn dẹp, kéo theo mọi subscriber logic đang
  "cưỡi" trên nó bị colony tự gỡ khi nó phát hiện TCP đóng.
- **DTO lặp có chủ đích**: `MessageType`/`SocketFrame` (envelope tầng ws) cố tình duplicate giữa
  `colony` và `harbor` thay vì gộp lên `discovery` — giữ 2 module độc lập nhau ở tầng
  giao thức chat, dù cùng chung định dạng. Ngược lại, `Payload`/`SignalingResponse`/`Destination`/...
  (tầng gossip/routing) đã được gộp về `discovery`, dùng chung thật sự. **Ngoại lệ duy nhất**:
  `ConversationIds.dmId(...)` (`core/commons-lang`) dùng chung thật giữa harbor và (gián tiếp) mọi
  nơi cần tính `conversationId` của DM — vì đây là 1 thuật toán 2 phía bắt buộc phải khớp
  bit-for-bit, không phải 1 DTO tầng wire.

## 9. Vì sao không route tin nhắn qua Kafka

Một lựa chọn thay thế "hiển nhiên" cho việc route tin nhắn cross-node là dùng Kafka: mỗi conversation
(hoặc mỗi pod colony) là 1 partition/topic, `harbor` publish MESSAGE vào topic, pod colony đang sở
hữu conversation đó consume rồi đẩy tiếp. Hệ thống hiện tại **cố tình không đi hướng đó** — dùng
consistent hashing (Maglev) để tự tính toán đúng pod cần gửi tới, rồi gửi thẳng qua EventBus
point-to-point (`connector.send(version, request)` — xem `MessageDelivery.send`). Lý do và đánh đổi:

| | **EventBus + consistent hashing (đang dùng)** | **Kafka (partition theo conversation/pod)** |
|---|---|---|
| Độ trễ | 1 hop trực tiếp tới đúng node sở hữu conversation (EventBus clustered qua Hazelcast, cùng datacenter) — cỡ ms | Thêm 1 vòng publish → broker ghi log → consumer poll — thường chậm hơn hẳn, dù vẫn nhanh so với batch xử lý thông thường |
| Hạ tầng vận hành | Không thêm service nào — EventBus có sẵn trong Vert.x, chỉ cần Hazelcast (đã dùng để cluster) | Phải tự vận hành thêm 1 cụm Kafka (+ZooKeeper/KRaft) — chi phí ops không nhỏ cho 1 hệ thống chat |
| Rebalance khi scale | Maglev: đổi node chỉ khiến ~1/N conversation bị route lại (`VersionVector`/`Router`), mượt, không cần điều phối trung tâm | Kafka partition reassignment tương đối nặng, số partition thường là hằng số cấu hình sẵn — khó co giãn mượt theo số pod colony |
| Durability / replay | Không — nếu node sở hữu chết đúng lúc message đang bay thì mất (không có log, không retry lại được); hiện KHÔNG có bất kỳ lưu trữ nào khác bù lại (xem mục 8) | Có — Kafka giữ log, consumer group mới có thể replay lại toàn bộ lịch sử |
| Fan-out cho consumer khác | Không hỗ trợ — chỉ đúng 1 pod nhận đúng 1 message (rồi tự fan-out cho subscriber cục bộ của nó) | Nhiều consumer group độc lập cùng đọc được 1 topic (vd thêm service notification/analytics sau này rất dễ) |
| Back-pressure | Không có buffer tự nhiên — 1 node bị dồn tin nhắn dồn dập thì dồn thẳng vào EventBus, dễ nghẽn nếu node đó chậm | Partition tự nhiên đóng vai trò buffer, consumer đọc theo tốc độ riêng của nó |

**Vì sao vẫn chọn EventBus + consistent hashing**: mục tiêu của `colony`/`harbor` là **đường đi
real-time, độ trễ thấp** cho tin nhắn đang "bay" giữa những người dùng online cùng lúc — không phải
là nơi lưu trữ lâu dài. Kafka giải quyết đúng vấn đề "durable log + nhiều consumer group + replay",
nhưng đó không phải bài toán ở đây — dùng Kafka cho phần này là trả thêm chi phí độ trễ + vận hành
cho một khả năng (durability, replay) mà tầng transport-tạm-thời này không cần **nếu** có 1 tầng lưu
trữ khác đảm nhiệm việc đó (hiện CHƯA có — xem mục 8). Đánh đổi phải chấp nhận: **không có
persistence ở tầng này** — một tin nhắn có thể mất nếu đúng lúc node đích chết (hiện `ACK` chỉ xác
nhận "gateway/chat đã chuyển tiếp", không xác nhận subscriber thực sự nhận — xem mục 8), và route
table là *eventually consistent* qua gossip chứ không có 1 broker trung tâm làm trọng tài — đây
chính là nguồn gốc của race condition mô tả ở mục 10 dưới đây. Nếu sau này cần thêm nhiều consumer
độc lập (notification service, audit log...) hoặc cần đảm bảo delivery mạnh hơn (at-least-once có
replay), đó sẽ là lúc đáng cân nhắc thêm Kafka cho riêng luồng đó — không nhất thiết phải thay cả
hệ thống.

## 10. Race condition khi routing table đổi version — vấn đề và cách đã fix

### `VersionVector` tồn tại để làm gì

`VersionVector` (`discovery/.../versionvector/VersionVector.java`) không chỉ là 1 con số tăng dần —
nó giữ **nhiều bản routing table cũ cùng lúc** (mặc định tối đa `MAX_RETAINED_VERSIONS = 50` bản gần
nhất), không chỉ đúng bản mới nhất. Lý do: mỗi khi `beacon` gossip ra 1 thay đổi (pod thêm/bớt),
`colony` cập nhật routing table gần như NGAY (chỉ là update 1 hashtable trong bộ nhớ), nhưng
`harbor` cần **thời gian thật** (network round-trip) để re-subscribe từng conversation đang mở sang
đúng pod colony mới (`BackendLinkGateway.reconnectConversationToVersion`, gọi từ
`RoutingVersionSync.reconnectSessionsToNewVersion` — lặp theo từng cặp `(session, conversationId)`,
không phải theo session, vì 1 session giờ có thể có nhiều conversation nằm trên nhiều pod khác nhau)
— việc này không thể tức thời nếu có hàng nghìn session/conversation. Giữ lại các version cũ cho
phép 1 conversation "đứng" ở version cũ trong lúc đang migrate, thay vì bị mất route ngay khi version
mới nhất xuất hiện.

### Vấn đề cụ thể: bản thân việc giữ nhiều version chưa đủ

Chỉ giữ version cũ không tự động giải quyết vấn đề — vẫn cần *ai đó chủ động dùng* version cũ khi
cần. Nếu code chỉ luôn route theo `currentVersion` mới nhất, xảy ra khoảng hở:

1. `beacon` gossip pod colony X bị xoá, thêm pod Y — routing table nhảy version `N → N+1` gần như
   ngay lập tức trên mọi node.
2. `colony` (nơi tính toán "message này gửi cho conversation thì conversation đó đang ở pod nào") đã
   dùng version `N+1` để tính đích — trỏ sang pod Y.
3. Nhưng `harbor` — nơi thật sự giữ backend link cho conversation đó — CHƯA kịp mở xong link mới sang
   Y (đang giữa quá trình reconnect, mất vài ms tới vài trăm ms tuỳ tải mạng).
4. Message bị forward đúng theo version mới (`N+1`, trỏ sang Y), nhưng Y **chưa có subscriber nào**
   cho conversation đó → tin nhắn bị rơi, chỉ log "no local subscriber on this node"
   (`MessageDelivery.onRoutedMessage`).

Đây chính là câu hỏi gốc đặt ra khi rà lại thiết kế: 2 phía (routing table ở `colony`, subscribe
migration ở `harbor`) đổi trạng thái với **tốc độ khác nhau hẳn** — 1 bên là cập nhật hashtable local
(rất nhanh), 1 bên là I/O mạng thật (chậm hơn nhiều bậc) — và không có gì đồng bộ hoá giữa 2 tốc độ
đó.

### 4 fix đã áp dụng — mỗi fix đóng 1 khe hở khác nhau

1. **Hedge sang version liền trước** (`MessageDelivery.forwardToOwningNode` /
   `hedgeToPreviousVersionIfDifferent`) — khi forward MESSAGE, tính đích theo cả version hiện tại
   **và** version ngay trước đó (dùng `RouteByConversationIdRequest`); nếu 2 pod khác nhau, gửi dự
   phòng sang CẢ HAI. Vế nào không có subscriber cục bộ thì tự bỏ qua (không lỗi), vế còn lại deliver
   bình thường — vá đúng khoảng hở ở bước 3-4 phía trên, ngay tại thời điểm gửi tin.
2. **Gossip REMOVE ngay lập tức, không chờ batch** (`RoutingGossipPublisher.addToQueue`) — trước đây
   mọi thay đổi (cả ADD lẫn REMOVE) đều gộp batch 5 giây rồi mới gossip. Với REMOVE (pod đã chết),
   càng chờ lâu càng nhiều tin nhắn tiếp tục bị route nhầm vào pod không còn tồn tại. Giờ REMOVE
   được publish ngay (`executor.execute`), chỉ ADD (pod mới, chưa ai cần route gấp) mới còn chờ batch
   — thu hẹp cửa sổ mà cả hệ thống còn "chưa biết" pod đã chết.
3. **Không đóng backend link cũ cho tới khi link mới SUBSCRIBE_OK** (`BackendLinkGateway.ensureLinkAndSubscribe`
   / `sendSubscribeAndAwait`) — trước đây (và vẫn giữ nguyên tinh thần sau khi đổi model đa-link) link
   cũ chỉ bị đóng SAU khi nhận được xác nhận từ node mới, KHÔNG phải ngay khi TCP connect() xong.
   Nếu handshake lỗi/timeout, link cũ (nếu có, CÙNG pod) được giữ nguyên hoàn toàn
   (`SockjsSocket.putLink` chỉ đóng link cũ của đúng pod đang thay thế, không đụng link của pod khác).
4. **Harbor "nhớ" lại `memberUserIds` và gửi lại mỗi lần SUBSCRIBE/reconnect** (`SockjsSocket.rememberMembers`/
   `getRememberedMembers`, dùng trong `BackendLinkGateway.reconnectConversationToVersion`) — phát
   hiện qua test thật (không phải suy luận): vì `ChannelMembershipRegistry` chỉ in-memory theo từng
   pod (xem mục 8), khi 1 conversation chuyển sang pod MỚI (pod cũ restart/scale), pod đó hoàn toàn
   không biết membership — nếu harbor không tự gửi lại `memberUserIds` đã biết, SUBSCRIBE sẽ bị từ
   chối ("not a member") **vĩnh viễn**, dù client vẫn hợp lệ. Đã verify bằng test thật: xoá colony
   pod giữa lúc đang chat, gửi 20 tin liên tục trong 40s — trước fix này mất tin từ lúc pod cũ biến
   mất và không bao giờ phục hồi; sau fix, cả 20/20 tin đều tới, kể cả tin gửi ngay sau khi xoá pod.

Cả 4 fix cùng thu hẹp — chứ không loại bỏ tuyệt đối — khoảng hở race condition: (1) và (3) đảm bảo
tại mọi thời điểm luôn có ít nhất 1 phía (version cũ hoặc version mới) có subscriber sống thật sự để
nhận tin; (2) rút ngắn thời gian cả hệ thống "chưa biết" một thay đổi đã xảy ra; (4) đảm bảo việc
re-subscribe sang pod mới không bị chặn bởi thiếu membership. Giới hạn còn lại: môi trường test local
hiện tại khi chạy `LocalKeeper` (1 destination cố định, không qua k8s) không tạo ra ADD/REMOVE thật
nên các nhánh trên chỉ được verify end-to-end bằng test thật khi chạy trên k3s (`K8SKeeper`) — xem
mục 11.

## 11. Chuyển routing từ shard-theo-userId sang shard-theo-conversationId (group chat)

### Vì sao đổi

Thiết kế ban đầu chỉ hỗ trợ chat 1-1: routing (`RouteByUserIdRequest`) và transport (harbor giữ đúng
1 backend link/session) đều theo `userId`. Muốn hỗ trợ group chat mà không tạo 1 code path riêng cho
1-1 vs group, cần hợp nhất cả 2 dưới khái niệm `conversationId` — DM cũng là 1 "conversation" 2
thành viên (so sánh với kiến trúc real-time messaging của Slack: Channel Server sharded theo channel,
Gateway Server subscribe qua đó — DM trong Slack cũng chạy qua đúng hạ tầng channel, không có code
path riêng).

### Quyết định thiết kế

1. **Hash `conversationId` độc lập hoàn toàn** (kể cả DM) — không ghim theo userId của 1 trong 2
   người. Hệ quả: harbor phải hỗ trợ nhiều backend link/session (1 link/pod đích, dùng chung cho N
   conversation cùng pod) thay vì 1 link/session như trước — ảnh hưởng TOÀN BỘ traffic, kể cả DM.
2. **Membership lưu in-memory** — `colony` chưa có kết nối DB thật nào (xem mục 8). Không đấu nối
   Postgres ở lần đổi này; `ChannelMembershipRegistry` mất khi pod restart, vá tạm bằng cơ chế
   "harbor tự nhớ và gửi lại memberUserIds" (mục 10, fix #4).
3. **DM tự suy `conversationId` từ `toUserId`** (`ConversationIds.dmId`, XOR 2 nửa UUID, đối xứng) —
   client vẫn gửi `toUserId` như cũ, không bắt buộc phải tự tính `conversationId`. Group: client tự
   chọn/hardcode `conversationId` — hệ thống chưa có flow "tạo group" thật, nhất quán với việc chưa
   có AuthN thật (mục 8).
4. **`AUTH` tách khỏi việc mở kết nối backend** — chỉ còn xác định danh tính, xử lý cục bộ ở harbor.
   `SUBSCRIBE`/`SUBSCRIBE_OK`/`SUBSCRIBE_ERROR` (frame type mới) mới là handshake mở/dùng lại backend
   link cho 1 `conversationId` cụ thể, đồng thời làm luôn việc lazy-create/join membership (frame
   `SUBSCRIBE` mang kèm `memberUserIds` optional).

### Vì sao `discovery` không cần sửa

`RoutingKey` là interface `int hash()` hoàn toàn generic — không hardcode String/UUID.
`ConsistentRouter`/`Maglev`/`VersionVector`/`PingoConnector` không tham chiếu `userId` ở đâu cả; chỉ
`RouteByUserIdRequest` (1 implementation cụ thể) mới gắn với userId. Vì vậy chỉ cần THÊM
`RouteByConversationIdRequest` (mirror y hệt, đổi `userId` → `conversationId`, dùng lại
`UUIDsHashHelper.hash(UUID)` có sẵn) — không sửa gì trong lõi `discovery`.

### Kết quả xác nhận (test thật qua WebSocket trên k3s, không phải mock)

- DM 2 chiều, group chat (lazy-join qua SUBSCRIBE), non-member bị từ chối subscribe: đều pass.
- Resilience: xoá colony pod giữa lúc đang chat, gửi liên tục 20 tin trong 40s — 20/20 tin đều tới,
  không rớt tin nào kể cả ngay sau khi pod bị xoá (nhờ đúng 4 fix ở mục 10, đặc biệt fix #4 — phát
  hiện qua chính lần test này, không có nó thì B mất tin vĩnh viễn từ lúc colony pod cũ biến mất).

### Giới hạn còn lại sau khi đổi (so với 1 hệ real-time messaging đầy đủ như Slack)

- **Không có lịch sử tin nhắn** — Slack's Channel Server giữ channel history; `colony` không lưu gì
  cả, subscribe vào 1 group không nhận được tin cũ.
- **Không đa vùng, không Presence Server** — chưa có khái niệm vùng địa lý trong `harbor`, chưa track
  online/offline.

Đây là những khoảng gác lại có chủ đích (xem mục 8, 9) — không phải thiếu sót của lần đổi này, mà là
việc chưa cần làm cho mục tiêu hiện tại (prototype/MVP), biết trước sẽ cần khi lên production thật.
(Membership từng nằm trong danh sách giới hạn ở đây do in-memory-theo-pod — đã chuyển sang Postgres,
xem mục 8.)

## 12. Chặng harbor↔colony chạy gRPC — thay hẳn N-shard-link tự viết

### Bối cảnh: vấn đề connection fan-out, và vì sao không tự viết N-shard-link nữa

Thiết kế cũ nhất (mục 4, 11): mỗi client session giữ 1 backend link WebSocket riêng/pod colony —
connection fan-out scale theo `O(số session đang active × số pod colony)`, không có trần. Thiết kế
kế tiếp (đã XOÁ, từng nằm ở mục này) sửa bằng cách tự viết "N shard link/pod, dùng chung cho mọi
session" (hash `sessionId` để chọn shard) — chặn trần connection ở `O(số pod harbor × số pod colony ×
N)`, đổi lấy phải tự tay giải quyết 1 loạt vấn đề phát sinh: colony phải tách `ChatLink`
(connection vật lý)/`ChatSubscriber` (subscriber logic) làm 2 lớp, mọi frame phải mang kèm
`harborSessionId` để demux đúng subscriber trên link dùng chung, phải tự định nghĩa thêm 1 loại frame
`SESSION_CLOSED` vì TCP close của 1 link dùng chung không còn đồng nghĩa "1 session rời đi" nữa.

Nhận ra: **HTTP/2 (nền tảng của gRPC) đã giải quyết đúng bài toán "nhiều luồng logic dùng chung 1
connection vật lý" này ở tầng transport** — đó chính là stream multiplexing, cơ chế lõi của giao
thức, không phải 1 tính năng phụ. Toàn bộ máy móc N-shard-link (hash shard, `ChatLink`/`ChatSubscriber`
2 tầng, `harborSessionId` demux, `SESSION_CLOSED`) chỉ là tự tay xây lại 1 phần nhỏ, thô hơn, của
đúng thứ mà transport layer đã làm sẵn — đổi transport thay vì tiếp tục vá tự viết.

### Thiết kế mới: 1 gRPC stream/(session,pod), multiplex trên 1 connection HTTP/2/pod

Dùng `io.vertx:vertx-grpc-server` + `io.vertx:vertx-grpc-client` (API `Future`/`ReadStream`/
`WriteStream` thuần Vert.x — KHÔNG dùng `io.vertx:vertx-grpc` cổ điển, vốn bọc `ManagedChannel`/
`ServerBuilder` của grpc-java, đúng kiểu "mỗi call có thể tự mở 1 connection riêng" mà mình đang muốn
tránh). Schema dùng chung, định nghĩa 1 lần ở `discovery/src/main/proto/link.proto` (compile bằng
`protobuf-maven-plugin` + `protoc-gen-grpc-java`, sinh `Frame`/`FrameType`/`LinkGrpc` — dùng
`LinkGrpc.getStreamMethod()` để lấy `MethodDescriptor`, bỏ qua hẳn `LinkImplBase`/stub kiểu
`StreamObserver` sinh kèm, không hợp phong cách `Future`-based của codebase này):

```protobuf
service Link {
  rpc Stream(stream Frame) returns (stream Frame);  // 1 call = 1 (harbor session, colony pod)
}
```

- **1 (session, pod) = 1 `BackendStream`** (harbor)/**`ChatSession`** (colony) — trực tiếp, không
  qua tầng shard trung gian nào. `SockjsSocket.backendStreams: Map<podName, BackendStream>`.
- **1 pod colony = 1 `GrpcClient` dùng CHUNG** (`BackendLinkGateway.clients: Map<podName,
  GrpcClient>`) — đây là nơi DUY NHẤT còn tính chất "chia sẻ connection vật lý", và nó nằm ở tầng
  transport (HTTP/2 tự multiplex), không phải logic tự viết. Không còn `harborSessionId`/
  `SESSION_CLOSED`/`ChatLink`/`ChatSubscriber` — đóng 1 session giờ chỉ là đóng đúng 1 `BackendStream`
  (gRPC stream tự nhiên báo end tới colony, colony dọn `ChatSession` tương ứng qua
  `request.endHandler`), không đụng gì tới stream của session khác dù đang multiplex chung 1
  connection.

### 3 bug thật gặp khi test trên k3s — và bài học

Migrate xong biên dịch sạch không có nghĩa là đúng — cả 3 bug dưới đây CHỈ lộ ra khi chạy thật trên
k3s (`e2e/demux-test.mjs`, `e2e/resilience-test.mjs`, `e2e/load-test.mjs`), không bug nào bắt được
lúc compile hay review code:

**1. Deadlock do đợi `response()` trước khi coi stream "sẵn sàng ghi".** Colony (bidi-streaming
server) chỉ thật sự gửi response headers SAU KHI nhận frame đầu tiên từ client — nếu harbor gate
việc "stream sẵn sàng" trên `request.response()` xong, 2 phía deadlock chờ nhau vĩnh viễn, không
log lỗi gì cả (cả 2 bên đều đang "chờ" hợp lệ). Fix: `BackendLinkGateway.doConnect` resolve NGAY khi
có `request` (đã ghi được), gắn response handler song song không chặn.

**2. `setHttp2KeepAliveTimeout` (client) làm `response()` trễ ĐÚNG BẰNG giá trị cấu hình.** Đặt
option này (kể cả 30s) làm `request.response()` không resolve cho tới ~29s sau, dù colony đã xử lý
và ghi phản hồi trong 148ms — nghi là quirk/bug riêng của vertx-grpc-client 4.5.5 khi kết hợp option
này với bidi-streaming. Fix: không set option này.

**3. Bỏ hẳn keepalive khiến pool connection idle lâu có thể "chết ngầm" (k3s CNI/conntrack rớt kết
nối idle) mà client không biết — request kế tiếp treo vô thời hạn, không exception nào cả.** Thử
`setIdleTimeout` để đóng chủ động connection rảnh — **tệ hơn**: option này đóng CẢ connection dựa
trên traffic tầng transport tổng hợp, không phân biệt được có session nào trên đó vẫn đang sống chỉ
là tạm im lặng (bình thường với chat: user không gõ gì trong >20s là chuyện thường) — đo được thật:
2 SUBSCRIBE thành công (~100ms), rồi ĐÚNG 20000ms sau (khớp giá trị cấu hình) cả 2 phía đồng thời
"Connection was closed", giết 1 session đang sống khoẻ mạnh. Vì 1 connection dùng chung cho NHIỀU
session, giết nhầm kiểu này lây sang mọi session khác đang multiplex chung — tệ hơn hẳn không làm gì.

**Fix cuối cùng, đang dùng:** không cấu hình keepalive/idle-timeout tầng transport ở cả 2 phía.
Thay vào đó, phản ứng dựa trên BẰNG CHỨNG cụ thể thay vì đoán theo thời gian rảnh:
- `client.request(...)` (bước mở connection) được bọc `CONNECT_TIMEOUT_MS=5000` — trước đó KHÔNG có
  timeout nào bảo vệ bước này (`HANDSHAKE_TIMEOUT_MS` chỉ áp dụng SAU khi có `request`); nếu pool
  HTTP/2 duy nhất/pod (`HttpClientOptions.DEFAULT_HTTP2_MAX_POOL_SIZE=1`, hàng chờ không giới hạn —
  `DEFAULT_MAX_WAIT_QUEUE_SIZE=-1`, không bao giờ tự reject) bị kẹt, request kế tiếp xếp hàng vô thời
  hạn không lỗi gì — bắt được qua kịch bản: chạy `load-test.mjs` (50 session) nhiều lần liên tiếp lên
  cùng 1 cặp pod, 1 lần trong 4 lần `client.request()` mất tới 48s mới resolve (không phải chết hẳn,
  chỉ chậm bất thường) — đúng lúc colony đóng hàng loạt session idle cùng lúc trên connection dùng
  chung (tranh chấp tạm thời khi nhiều stream cũ đóng đồng loạt, không phải rò rỉ vĩnh viễn).
- Khi `client.request()` timeout, hoặc 1 stream đã mở chứng minh hỏng thật (SUBSCRIBE không được ack
  trong `HANDSHAKE_TIMEOUT_MS`, hoặc response end/error) → evict `GrpcClient` của pod đó khỏi
  `clients` (`BackendLinkGateway.evictClient`) — lần `ensureStream` kế tiếp tự mở connection MỚI.
  Tự phục hồi trong 1 chu kỳ retry, không cần can thiệp tay.
- `BackendStream.end()` phải dùng `cancel()` (không phải `end()`) cho 1 stream chưa từng `write()`
  lần nào (VD: `client.request()` trả về TRỄ, sau khi đã timeout và bị bỏ) — `end()` trên gRPC
  client-streaming ném `IllegalStateException` nếu chưa gửi message nào, đúng theo semantics gRPC.

Xác nhận bằng test thật: `demux-test.mjs` (đúng đắn/cô lập session) và `resilience-test.mjs` (chaos —
kill cả 3 pod colony giữa lúc có tải) pass sạch nhiều lần; `load-test.mjs` (50 session, ~2.5k msg/s)
pass 100% delivered/0% errored/0% silent lặp lại nhiều lần liên tiếp, kể cả khi 1 lần trong nhiều lần
gặp đúng kịch bản contention ở trên — tự phục hồi ở lần chạy kế tiếp mà không cần deploy lại.

**Giới hạn đã biết, gợi ý cải thiện sau (chưa làm, chưa cần thiết ở quy mô hiện tại):** dưới tải
đồng bộ dồn dập kiểu synthetic (nhiều session mở/đóng hàng loạt trong thời gian ngắn, như
`load-test.mjs` chạy lặp lại liên tiếp), việc chỉ có ĐÚNG 1 connection HTTP/2 vật lý/pod có thể gặp
tranh chấp tạm thời như mô tả ở bug #3 — bị chặn (bounded) bởi `CONNECT_TIMEOUT_MS` và tự phục hồi,
nhưng nếu vấn đề này chứng minh ảnh hưởng thật ở quy mô production, có thể cân nhắc tune thêm
`Http2Settings.setMaxConcurrentStreams` (phía server, `ChatSocketServer`/colony) và
`http2MultiplexingLimit`/`http2MaxPoolSize` (phía client, `BackendLinkGateway`/harbor) — đã thảo luận
trong quá trình làm migration này nhưng chưa implement, chỉ ghi lại làm gợi ý.
