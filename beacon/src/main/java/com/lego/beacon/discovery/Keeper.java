package com.lego.beacon.discovery;

import com.lego.namnv.discovery.k8s.K8sClientConfig;
import com.lego.namnv.discovery.router.Destination;
import com.lego.namnv98.event.EventObservable;

import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Nguồn phát hiện (discover) destination — bên nắm giữ danh sách destination "thật" ở service này.
 * Có 2 cách triển khai: {@link K8SKeeper} (watch pod thật qua k8s API, dùng khi chạy production)
 * và {@link LocalKeeper} (danh sách cố định, dùng cho local dev/test — xem
 * {@code BeaconAppModule} để biết cách chọn giữa 2 cái này).
 *
 * <p>Lưu ý: đừng nhầm với {@code com.lego.namnv.discovery.keeper.Keeper} bên module {@code discovery}
 * — tuy tên giống nhau nhưng vai trò khác hẳn: Keeper ở đó là bên NHẬN cập nhật từ bên ngoài rồi giữ
 * snapshot lại (dùng bởi colony/harbor), còn Keeper ở đây là bên chủ động PHÁT HIỆN
 * destination (dùng bởi chính beacon để biết có những pod colony nào).
 */
public interface Keeper extends EventObservable {

    static Keeper k8sKeeper(K8sClientConfig k8sConfig, Function<String, CompletionStage<Boolean>> healthcheck) {
        return new K8SKeeper(k8sConfig, healthcheck);
    }

    static Keeper localKeeper(List<Destination> destinations) {
        return new LocalKeeper(destinations);
    }

    /** Danh sách destination hiện đang biết — snapshot tại thời điểm gọi. */
    List<Destination> getAll();
}

