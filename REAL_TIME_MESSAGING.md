# Real-Time Messaging trong Pingo

Pingo là hệ thống chat real-time chúng tôi xây trên nền Vert.x, thiết kế để chuyển tin nhắn giữa
hàng nghìn người dùng đang online cùng lúc với độ trễ tính bằng mili-giây. Bài viết này kể lại cách
4 thành phần chính — `beacon`, `harbor`, `colony`, `discovery` — phối hợp với nhau để làm được việc
đó, cơ chế hash/routing đứng sau, vì sao chúng tôi không chọn Kafka, và hệ thống scale ra sao khi
traffic tăng.

## Bài toán cốt lõi: 2 ràng buộc

Trước khi đi vào từng thành phần, đáng nói rõ bài toán mà toàn bộ thiết kế dưới đây nhằm giải quyết
— vì nó không chỉ là "chuyển tiếp tin nhắn". Chạy nhiều instance của một server giữ WebSocket (kết
nối bền, không giống HTTP request ngắn hạn) đặt ra 2 ràng buộc tách biệt, và dễ nhầm lẫn nếu gộp
chung làm một:

1. **Đúng đích**: người gửi và người nhận hiếm khi cùng nối vào 1 pod. Hệ thống phải biết chính xác
   ai đang ở pod nào để chuyển tin đúng chỗ, không đoán, không phát tràn.
2. **Cân bằng khi scale**: khi thêm/bớt pod, tải (số connection đang mở) phải tự cân bằng lại — nhưng
   không giống HTTP, một load balancer thông thường không thể "chuyển" một WebSocket đang mở sang
   pod khác chỉ bằng cách đổi đích cho request tiếp theo, vì làm gì có request tiếp theo — kết nối
   đã mở sẵn và cứ thế tồn tại cho tới khi 1 trong 2 đầu chủ động đóng nó.

Ràng buộc 1 pingo giải bằng consistent hashing (chi tiết ở mục dưới). Ràng buộc 2 mới là phần khó
thật sự — và là lý do `harbor` không đơn thuần là 1 reverse proxy mỏng, mà phải tự mang theo logic
"biết khi nào cần tự tay di chuyển 1 kết nối sang pod khác", vì không có k8s Service hay load
balancer có sẵn nào làm việc đó thay cho nó.

## Các thành phần chính

Pingo chạy 3 service Java độc lập, cùng 1 thư viện dùng chung:

**`colony`** là nơi thật sự giữ trạng thái của từng cuộc trò chuyện (conversation) — có thể là chat
1-1 hay group. Mỗi conversation được gán cố định cho đúng 1 pod `colony` thông qua consistent hashing
(thuật toán Maglev), tính theo `conversationId`. Một pod `colony` không "biết" gì về browser cả — nó
chỉ giữ danh sách các subscriber đang quan tâm tới từng conversation, mỗi subscriber thực chất là 1
kết nối WebSocket do `harbor` mở tới nó.

**`harbor`** là cổng vào công khai — nơi duy nhất browser thực sự kết nối tới, qua SockJS/WebSocket.
`harbor` không giữ trạng thái chat: với mỗi pod `colony` đang sở hữu ít nhất 1 conversation mà client
của nó quan tâm, nó mở (hoặc dùng lại) đúng 1 kết nối backend, dùng chung cho mọi conversation nào
tình cờ rơi vào cùng pod đó.

**`beacon`** là control-plane duy nhất nói chuyện trực tiếp với Kubernetes API. Nó theo dõi pod
`colony` nào đang sống, và ngay khi phát hiện 1 pod biến mất, phát tin đó ra cho toàn hệ thống —
không đợi gộp batch, vì mỗi giây chậm trễ là một khoảng thời gian tin nhắn có thể bị route nhầm vào
một pod không còn tồn tại.

**`discovery`** không phải một service — nó là thư viện dùng chung chứa cỗ máy routing, sẽ mô tả kỹ
hơn ở phần dưới.

## Hash và routing table hoạt động ra sao

Lõi routing của Pingo xoay quanh 1 interface rất mỏng, `RoutingKey`, với đúng 1 phương thức:
`int hash()`. Bảng routing (`ConsistentRouter`, dựng trên thuật toán **Maglev** — cùng thuật toán
Google dùng cho load balancer nội bộ của họ) hoàn toàn không quan tâm giá trị đó là hash của cái gì —
nó chỉ cần 1 số nguyên. Điều này cho phép chúng tôi đổi hẳn đơn vị routing (từ `userId` sang
`conversationId`, khi thêm group chat) mà không sửa một dòng nào trong lõi router — chỉ cần thêm 1
implementation mới của `RoutingKey` (`RouteByConversationIdRequest`, hash bằng cách băm UUID của
conversation).

Vì sao chọn Maglev thay vì modulo hash (`hash(key) % N`) đơn giản hơn nhiều? Với modulo hash, chỉ cần
đổi `N` (thêm/bớt 1 pod `colony`) là gần như **toàn bộ** key bị tính lại đích khác — với modulo, thêm
1 node vào N node làm khoảng N/(N+1) tổng số key đổi chỗ. Maglev dựng sẵn 1 bảng lookup lớn (kích
thước cấu hình được qua `lookupTableSize`) bằng cách cho mỗi destination (pod) tự sinh 1 hoán vị
(permutation) riêng từ 2 hàm hash độc lập trên tên pod, rồi lấp đầy bảng theo vòng round-robin qua
các hoán vị đó. Kết quả: khi thêm/bớt 1 pod trong N pod, chỉ khoảng **1/N** số key trong bảng đổi
đích — phần còn lại giữ nguyên. Với chat, điều đó nghĩa là scale/restart 1 pod `colony` trong cụm 20
pod chỉ làm ~5% conversation phải "chuyển nhà", không phải toàn bộ.

Maglev không phải lựa chọn duy nhất trong họ "hash-based deterministic routing" — **rendezvous
hashing** (còn gọi HRW) là 1 thuật toán khác cùng họ, cũng cho tính chất ~1/N remap khi N đổi, nhưng
tính trực tiếp (so hash của key với từng destination, chọn destination cho giá trị lớn nhất) thay vì
dựng sẵn 1 bảng lookup. Rendezvous cài đặt đơn giản hơn và có xu hướng cân bằng tải đều hơn giữa các
destination, đổi lại mỗi lần lookup tốn O(N) thay vì O(1) như Maglev sau khi đã dựng bảng — với số
lượng pod `colony` còn nhỏ (vài chục), khác biệt hiệu năng này không đáng kể; đây là hướng đáng cân
nhắc nếu sau này quan sát thấy tải lệch hẳn giữa các pod `colony`.

Bên cạnh bảng routing, còn 1 lớp nữa là `VersionVector` — nó không đánh version cho từng
conversation, mà đánh version cho *toàn bộ topology pod* mỗi khi có thay đổi (pod thêm/bớt). Điểm
khác biệt: `VersionVector` **giữ lại tối đa 50 phiên bản gần nhất** của bảng routing, không chỉ bản
mới nhất. Lý do: `colony` cập nhật bảng routing gần như tức thời (chỉ là ghi vào 1 hashtable), nhưng
`harbor` cần thời gian thật (network round-trip) để mở lại kết nối sang pod mới cho từng conversation
đang mở. Giữ lại các version cũ cho phép hệ thống, trong lúc `harbor` đang "chuyển nhà", vẫn tính
được đích theo *cả* version cũ lẫn version mới, và gửi dự phòng sang cả hai nếu chúng khác pod nhau —
không phải chỉ tin vào bản mới nhất rồi hy vọng mọi thứ đã kịp đồng bộ.

## Luồng kết nối của client

Khi mở app, client kết nối 1 WebSocket duy nhất tới `harbor` và gửi khung `AUTH` để xác định danh
tính. Bước này hoàn toàn cục bộ — `harbor` không đụng gì tới `colony` cả, chỉ ghi nhớ user id cho
phiên kết nối đó.

Muốn nhận (hoặc gửi) tin của một cuộc trò chuyện cụ thể, client phải chủ động gửi tiếp khung
`SUBSCRIBE`. Đây là lúc `harbor` mới thật sự vào cuộc: nó tính `conversationId` thuộc pod `colony`
nào (qua bảng consistent-hashing ở trên), rồi mở hoặc tái sử dụng kết nối backend tới đúng pod đó,
gửi tiếp `SUBSCRIBE` xuống dưới. Nếu đây là lần đầu ai đó nhắc tới conversation này, khung `SUBSCRIBE`
còn mang theo danh sách thành viên — `colony` dùng nó để lập tức "tạo" luôn conversation, không cần
một bước "tạo group" riêng biệt ở đâu cả.

Một kết nối backend duy nhất phục vụ *mọi* conversation nào rơi vào cùng 1 pod `colony` — client có
thể đang tham gia hàng chục cuộc trò chuyện khác nhau, nhưng số kết nối vật lý `harbor` phải giữ chỉ
phụ thuộc vào số pod `colony` khác nhau đang giữ chúng, không phải số cuộc trò chuyện.

Đây chính là lời giải cho "ràng buộc cân bằng khi scale" nói ở trên: mỗi session giữ **2 chặng kết
nối tách biệt** — chặng browser↔`harbor` (không bao giờ bị đụng tới sau khi mở) và chặng
`harbor`↔`colony` theo từng pod (có thể mở/đóng/thay đổi bất cứ lúc nào). Khi `beacon` báo 1 pod
`colony` vừa thêm/bớt, `harbor` chỉ việc tính lại đích cho từng conversation đang mở và, nếu đích đổi
pod, âm thầm mở 1 chặng backend mới rồi đóng chặng cũ — **client hoàn toàn không hay biết gì**, không
bị ngắt kết nối, không cần logic reconnect phía client. Tách 2 chặng như vậy là việc phải tự làm bằng
tay ở tầng ứng dụng — không k8s Service, Ingress hay load balancer thông thường nào tự động "di
chuyển" một WebSocket đang mở sang pod khác giúp mình cả.

Ngược lại, khi chính `harbor` (chứ không phải `colony`) chuẩn bị tắt — scale-down hoặc rolling
update — pingo lại chọn cách đơn giản hơn cho đúng chặng đó: gửi khung `GOAWAY` để chủ động báo
trước cho client, để client tự đóng và reconnect sang pod `harbor` khác, thay vì cố "di chuyển" cả
phiên browser đang mở. 2 chặng, 2 chiến lược khác nhau: chặng `harbor`↔`colony` cần trong suốt tuyệt
đối nên tự làm remap; chặng browser↔`harbor` vốn dĩ client đã phải biết reconnect (qua mạng chập
chờn, rớt sóng...), nên tận dụng luôn cơ chế đó thay vì xây thêm 1 tầng phức tạp không cần thiết.

## Định tuyến tin nhắn

Một tin nhắn đi theo đường: **Client → `harbor` → pod `colony` sở hữu conversation → (nếu cần)
forward sang pod `colony` khác qua EventBus → phát cho mọi subscriber cục bộ**.

Forward cross-node dùng EventBus **point-to-point**, không phải publish/subscribe: mỗi pod `colony`
lắng nghe trên 1 địa chỉ EventBus đúng bằng tên pod của chính nó, và bên gửi tính sẵn đích (`Destination.name()`
từ bảng Maglev) rồi gửi thẳng vào đúng địa chỉ đó — không round-robin, không phát tràn.

Bước cuối là điểm mấu chốt: khi một pod `colony` nhận được tin cho 1 conversation, nó không cần biết
người nhận là ai hay đang ở đâu — nó chỉ đơn giản duyệt qua danh sách subscriber cục bộ đang đăng ký
conversation đó (mỗi subscriber là 1 kết nối từ 1 pod `harbor` nào đó, có thể khác nhau) và đẩy tin
tới từng người. 1 tin nhắn ghi vào EventBus đúng 1 lần, rồi tự "toả" ra cho tất cả những ai đang quan
tâm — dù họ đang gõ chat 1-1 hay ngồi trong cùng 1 group.

## Vì sao không route tin nhắn qua Kafka

Lựa chọn thay thế "hiển nhiên" cho việc route tin nhắn cross-node là Kafka: mỗi conversation là 1
partition/topic, `harbor` publish MESSAGE vào topic, pod `colony` đang sở hữu conversation đó
consume rồi đẩy tiếp. Chúng tôi cân nhắc rồi cố tình không đi hướng đó, vì bài toán của phần này là
đường đi **real-time, độ trễ thấp** cho tin nhắn đang bay giữa những người online cùng lúc — không
phải là kho lưu trữ lâu dài.

Điều này cũng loại luôn 1 phương án khác tưởng chừng đơn giản hơn cả 2: phát tin lên 1 kênh
pub/sub dùng chung, để mọi pod tự đọc rồi lọc lấy phần của mình. Với N pod, cách đó khiến tổng số
lượt đọc tăng theo N² (mỗi tin bị N pod đọc, phần lớn rồi vứt đi vì không phải của mình) — chi phí
tăng nhanh hơn hẳn traffic thật, dù không cần tự vận hành gì thêm. Route thẳng theo địa chỉ (tên pod)
tính sẵn từ Maglev tránh được khoản lãng phí đó hoàn toàn: mỗi tin chỉ đi tới đúng 1 nơi cần nó.

| | EventBus + consistent hashing (đang dùng) | Kafka (partition theo conversation/pod) |
|---|---|---|
| Độ trễ | 1 hop trực tiếp tới đúng pod sở hữu, cùng cụm Hazelcast — cỡ mili-giây | Thêm 1 vòng publish → broker ghi log → consumer poll — chậm hơn hẳn |
| Hạ tầng vận hành | Không thêm service nào — EventBus có sẵn trong Vert.x | Phải tự vận hành thêm 1 cụm Kafka (+ZooKeeper/KRaft) |
| Rebalance khi scale | Maglev: chỉ ~1/N conversation bị route lại, không cần điều phối trung tâm | Partition reassignment nặng hơn, số partition thường cố định, khó co giãn mượt |
| Durability / replay | Không — pod chết đúng lúc tin đang bay thì mất, không có log | Có — giữ log, consumer group mới replay lại được toàn bộ lịch sử |
| Fan-out cho nhiều consumer | Không — chỉ đúng 1 pod nhận đúng 1 message | Nhiều consumer group độc lập cùng đọc 1 topic — dễ thêm service mới (notification, audit log...) |
| Back-pressure | Không có buffer tự nhiên — dồn thẳng vào EventBus | Partition tự nhiên là buffer, consumer đọc theo tốc độ riêng |

Cái giá phải trả cho việc không dùng Kafka: hoàn toàn không có persistence ở tầng transport này — một
tin nhắn có thể mất nếu đúng lúc pod đích chết, và `ACK` hiện chỉ xác nhận "đã chuyển tiếp thành
công", không xác nhận người nhận thực sự đã nhận được. Nếu sau này cần thêm nhiều consumer độc lập
hoặc cần đảm bảo delivery mạnh hơn (at-least-once, có replay), đó sẽ là lúc đáng cân nhắc thêm Kafka
cho riêng luồng đó — không nhất thiết phải thay cả hệ thống.

## Scale như thế nào

**Theo chiều ngang, độc lập từng lớp.** `harbor` và `colony` scale hoàn toàn tách biệt — thêm pod
`harbor` khi số connection từ client tăng, thêm pod `colony` khi số conversation/traffic tăng, không
phụ thuộc lẫn nhau. `beacon` là ngoại lệ duy nhất: chạy đúng 1 pod, vì vai trò của nó (watch k8s API,
gossip topology) không cần và không nên có nhiều bản sao tranh nhau — nếu `beacon` chết, hệ thống vẫn
chạy bình thường với bảng routing hiện có, chỉ là không cập nhật thêm cho tới khi nó sống lại
(single point of control-plane, không phải single point of data-plane).

**Chi phí scale `colony` là hằng số nhỏ, không phải toàn cục.** Nhờ Maglev, thêm/bớt 1 pod trong N
pod chỉ khiến ~1/N conversation phải chuyển pod — và với mỗi conversation bị ảnh hưởng, cái phải làm
lại chỉ là `harbor` mở 1 kết nối WebSocket mới sang pod mới rồi gửi lại `SUBSCRIBE`, không phải
migrate dữ liệu gì (vì bản thân `colony` không lưu gì bền vững, xem phần dưới).

**Số kết nối `harbor` giữ bị chặn trên bởi số pod `colony`, không phải số client hay số
conversation.** Vì 1 kết nối backend dùng chung cho mọi conversation cùng pod, 1 client tham gia 100
cuộc trò chuyện rải trên 20 pod `colony` chỉ tốn tối đa 20 kết nối vật lý cho `harbor`, không phải
100.

**Đã kiểm chứng khả năng chịu lỗi bằng test thật, không chỉ trên giấy.** Chúng tôi xoá thẳng 1 pod
`colony` giữa lúc 2 client đang chat, rồi gửi liên tục tin nhắn suốt 40 giây: toàn bộ tin nhắn đều
tới nơi, không rớt tin nào — kể cả tin gửi ngay sau khi pod biến mất. Có được kết quả đó nhờ 4 lớp
phối hợp: `beacon` gossip pod chết ngay lập tức (không gộp batch); `colony`/`harbor` gửi dự phòng
tin nhắn sang cả routing-version cũ lẫn mới trong lúc đang chuyển tiếp; không đóng kết nối cũ cho tới
khi kết nối mới được xác nhận xong; và `harbor` tự "nhớ" lại danh sách thành viên của từng
conversation để gửi kèm mỗi lần kết nối lại — vì một pod `colony` vừa khởi động lại sẽ không còn nhớ
ai từng ở trong đó (lớp cuối cùng này là phát hiện trong lúc test thật, ban đầu không có, tin nhắn
từng rớt vĩnh viễn sau khi pod restart cho tới khi thêm nó vào).

**Còn 1 bài toán cân bằng tải chưa cần giải: kết nối MỚI vào `harbor`.** Ở trên là chuyện cân bằng
các kết nối ĐANG mở khi `colony` scale. Còn việc phân phối kết nối MỚI của client vào đúng pod
`harbor` nào hiện chỉ dựa vào round-robin mặc định của k8s Service — đơn giản nhưng đủ dùng, vì đây
vốn là bài toán dễ hơn nhiều so với việc "di chuyển" 1 kết nối đã mở. Nếu sau này 1 pod `harbor` bị
quá tải và cần tạm ngừng nhận thêm kết nối mới trong lúc các pod khác đuổi kịp, có thể tái dùng ngay
cơ chế readinessProbe đã có sẵn cho lúc pod tắt (`HealthCheckVerticle`) — đánh dấu tạm "chưa sẵn
sàng" để k8s ngừng route thêm vào — chứ không cần xây mới.

## Những gì chưa có

Pingo hiện chưa lưu lịch sử tin nhắn hay danh sách thành viên một cách bền vững — mọi thứ sống trong
bộ nhớ của từng pod. Cũng chưa có khái niệm nhiều vùng địa lý, và chưa có service theo dõi trạng thái
online/offline riêng. Đây là những phần chúng tôi biết trước sẽ cần khi đưa hệ thống lên sản xuất
thật, nhưng cố tình gác lại cho tới khi bài toán đó thực sự xuất hiện.

## Tham khảo thêm

Cách đặt vấn đề và một phần thiết kế trong bài này được đối chiếu với 2 bài viết kỹ thuật công khai
mô tả những hệ thống tương tự ở quy mô lớn hơn nhiều:

- Slack Engineering — [Real-Time Messaging](https://slack.engineering/real-time-messaging/): kiến
  trúc Channel Server/Gateway Server sharded theo channel bằng consistent hashing.
- Lumen Engineering — [How to implement a distributed and auto-scalable WebSocket server architecture
  on Kubernetes](https://medium.com/lumen-engineering-blog/how-to-implement-a-distributed-and-auto-scalable-websocket-server-architecture-on-kubernetes-4cc32e1dfa45):
  đặt vấn đề rõ ràng thành 2 ràng buộc (đúng đích + cân bằng khi scale), dùng rendezvous hashing, và
  tự viết load balancer riêng để di chuyển kết nối WebSocket khi rescale mà không làm gián đoạn client.
