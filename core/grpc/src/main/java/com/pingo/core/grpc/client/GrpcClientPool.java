package com.pingo.core.grpc.client;

import io.vertx.core.Vertx;
import io.vertx.grpc.client.GrpcClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.NonNull;

/**
 * Cache {@link GrpcClient} (= 1 pool HTTP/2 connection) theo key (thường là địa chỉ/pod đích) —
 * dùng CHUNG cho mọi caller tới cùng 1 đích thay vì mỗi lần mở 1 connection riêng (hiện tại: harbor
 * gọi colony qua chặng gRPC, xem ARCHITECTURE.md mục 12). {@link #evict} khi có bằng chứng cụ thể
 * connection hỏng — không đoán mò theo thời gian rảnh.
 *
 * <p><b>KHÔNG tự set {@code setHttp2KeepAliveTimeout}/{@code setIdleTimeout}</b> trên
 * {@link GrpcClient} sinh ra — 2 bug thật đã gặp lúc migrate ({@code BackendStreamGateway},
 * ARCHITECTURE.md mục 12):
 * <ul>
 *   <li>{@code setHttp2KeepAliveTimeout}: làm {@code request.response()} trễ ĐÚNG BẰNG thời gian
 *   cấu hình dù backend đã xử lý xong gần như ngay lập tức — quirk vertx-grpc-client 4.5.5 với
 *   bidi-streaming, không phải lỗi logic ứng dụng.</li>
 *   <li>{@code setIdleTimeout}: đóng CẢ connection (không phân biệt stream nào còn sống) sau N giây
 *   không traffic — tệ hơn, vì nhiều stream/session dùng chung 1 connection (multiplex HTTP/2), giết
 *   nhầm ảnh hưởng dây chuyền tới mọi stream khác đang sống hợp lệ, chỉ đơn giản đang im lặng.</li>
 * </ul>
 * Dùng option mặc định hoàn toàn — đánh giá connection sống/chết qua bằng chứng tầng ứng dụng
 * (handshake/ACK timeout của caller), không qua transport-level timer.
 */
public class GrpcClientPool {

    private final Vertx vertx;
    private final Map<String, GrpcClient> clients = new ConcurrentHashMap<>();

    public GrpcClientPool(@NonNull Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Lấy {@link GrpcClient} đang cache cho {@code key}, mở mới nếu chưa có.
     */
    public GrpcClient get(String key) {
        return clients.computeIfAbsent(key, k -> GrpcClient.client(vertx));
    }

    /**
     * Bỏ {@link GrpcClient} đang cache cho {@code key} — chỉ gọi khi đã có bằng chứng cụ thể connection hỏng.
     */
    public void evict(String key) {
        clients.remove(key);
    }
}
