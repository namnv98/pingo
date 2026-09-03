package com.lego.namnv.discovery.versionvector;

import static java.util.Objects.nonNull;

import com.lego.namnv.discovery.keeper.DestinationChangeEvent;
import com.lego.namnv.discovery.keeper.Keeper;
import com.lego.namnv.discovery.router.Destination;
import com.lego.namnv.discovery.router.Router;
import java.util.List;
import java.util.TreeMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class VersionVector {

  /**
   * Số version gần nhất được giữ lại trong {@link #versions}. Trước đây mỗi lần
   * {@link #increment}/{@link #incrementAll} thêm 1 {@link DestinationVersion} (chứa cả Router +
   * Maglev lookup table) vào map mà KHÔNG BAO GIỜ xoá — chạy đủ lâu, đổi routing table đủ nhiều
   * lần là rò rỉ bộ nhớ vô hạn. Giữ 1 khoảng đệm (không chỉ đúng bản mới nhất) chứ không xoá ngay,
   * để session/node nào đang migrate chậm (xem harbor's BackendLinkGateway.reconnectToVersion)
   * vẫn tra cứu được version cũ nó đang đứng, thay vì bị evict ngay lập tức.
   */
  private static final int MAX_RETAINED_VERSIONS = 50;

  private final TreeMap<Long, DestinationVersion> versions;

  private long currentVersion;

  public VersionVector() {
    this(new TreeMap<>());
  }

  public VersionVector(TreeMap<Long, DestinationVersion> versions) {
    this.versions = versions;
  }

  public VersionVector incrementAll(long version, List<Destination> destinations) {
    var keeper = Keeper.snapshotKeeper();
    var consistentRouter = Router.newConsistent(keeper);
    keeper.addDestinations(destinations);
    currentVersion = version;
    put(version, new DestinationVersion(consistentRouter, keeper));
    return this;
  }

  public VersionVector increment(
      long version, List<DestinationChangeEvent> destinationChangeEvents) {
    var keeper = Keeper.snapshotKeeper();
    // ConsistentRouter subscribes to `keeper` right here, in Router.newConsistent(): every
    // ADD/REMOVE dispatched below is picked up immediately and folds into Maglev's lookup table
    // on its own (Maglev.addDestination()/removeDestination() each already repopulate it). So
    // there is nothing left to flush afterwards — an explicit populateLookupTable() call used to
    // sit here doing that same work a second time for no reason, and has been removed along with
    // the now-pointless Router.populateLookupTable()/ConsistentRouter override that only existed
    // to serve that one redundant call.
    var consistentRouter = Router.newConsistent(keeper);
    var podVersion = versions.get(currentVersion);
    // Truoc day co them dieu kien "version > 1" o day, dua tren gia tri TUYET DOI cua version
    // MOI nhan tu beacon -- sai: beacon la 1 JVM rieng, version cua no reset ve 0 moi lan beacon
    // restart (RoutingGossipPublisher.version, chi song trong memory, khong persist dau ca), nen
    // sau moi lan beacon restart, gossip incremental DAU TIEN luon mang version=1 -- dung luc do
    // "version > 1" fail, lam rot mat toan bo destination da tich luy truoc do (podVersion khong
    // ROI la null, chi la bi dieu kien nay chan khong cho copy sang) -- day chinh la nguyen nhan
    // "No route to host" lap lai moi khi beacon restart giua luc colony/harbor van dang song.
    // nonNull(podVersion) tu no da du de phan biet dung "lan dau tien thuc su" (chua tung co
    // podVersion nao, vd truoc ca beacon_init) voi "beacon vua restart nhung ben nay van con nho
    // state cu" -- khong can them dieu kien nao ve gia tri cua version nua.
    if (nonNull(podVersion)) {
      keeper.addDestinations(podVersion.getKeeper().listingDestinations());
    }
    keeper.addDestinationChangeEvent(destinationChangeEvents);
    currentVersion = version;
    put(version, new DestinationVersion(consistentRouter, keeper));
    return this;
  }

  private void put(long version, DestinationVersion destinationVersion) {
    versions.put(version, destinationVersion);
    while (versions.size() > MAX_RETAINED_VERSIONS) {
      var evicted = versions.pollFirstEntry();
      log.debug("evicted routing table version {} (retention cap {})", evicted.getKey(), MAX_RETAINED_VERSIONS);
    }
  }

  public boolean happenedBefore(long other) {
    return currentVersion < other;
  }

  public DestinationVersion get() {
    return versions.get(currentVersion);
  }

  /**
   * Tra cứu đúng version yêu cầu; nếu version đó đã bị evict (quá cũ, xem
   * {@link #MAX_RETAINED_VERSIONS}) hoặc chưa từng tồn tại, fallback về version hiện tại thay vì
   * trả {@code null} — an toàn hơn cho caller (vd session đứng ở version quá cũ, lâu ngày chưa
   * migrate) so với để NPE lan ra tận nơi gọi.
   */
  public DestinationVersion get(long version) {
    var found = versions.get(version);
    if (found != null) {
      return found;
    }
    log.warn(
        "routing table version {} not found (evicted or unknown) — falling back to current version {}",
        version,
        currentVersion);
    return get();
  }
}
