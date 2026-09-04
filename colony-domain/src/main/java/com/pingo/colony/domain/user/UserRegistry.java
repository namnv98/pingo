package com.pingo.colony.domain.user;

import com.pingo.core.common.jdbcpool.supplier.JdbcConnectionSupplier;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import lombok.RequiredArgsConstructor;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Đăng ký/xác thực tài khoản (username + password thật, hash bằng bcrypt) — xem
 * {@code HallApiHandlers}'s {@code POST /register}/{@code POST /login}/{@code PUT /users}. Trước
 * đây chỉ là "đăng ký tên hiển thị", không có password/token gì cả — giờ là hệ thống login thật,
 * {@code id} do server tự sinh lúc {@link #registerUser}, không còn do client tự khai như trước.
 */
@RequiredArgsConstructor
public class UserRegistry {

  /** Postgres unique_violation — https://www.postgresql.org/docs/current/errcodes-appendix.html */
  private static final String UNIQUE_VIOLATION_SQLSTATE = "23505";

  private final JdbcConnectionSupplier supplier;

  /** Username đã tồn tại (đăng ký trùng, hoặc đổi tên trùng người khác). */
  public static class UsernameTakenException extends RuntimeException {
    public UsernameTakenException(String username) {
      super("username already taken: " + username);
    }
  }

  /** Đăng ký tài khoản mới — id do server tự sinh, password được hash trước khi lưu, không bao giờ lưu dạng plaintext. */
  public CompletionStage<JsonObject> registerUser(String username, String rawPassword) {
    var id = UUID.randomUUID();
    var passwordHash = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    return supplier
        .execute(conn -> conn.preparedQuery("INSERT INTO users (id, username, password_hash) VALUES ($1, $2, $3)")
            .execute(Tuple.of(id, username, passwordHash))
            .toCompletionStage()
            .thenApply(unused -> new JsonObject().put("id", id.toString()).put("username", username)))
        .exceptionally(ex -> { throw isUniqueViolation(ex) ? new UsernameTakenException(username) : asUnchecked(ex); });
  }

  /** Xác thực username/password — Optional rỗng cho cả 2 trường hợp "không tồn tại" và "sai mật khẩu" (không lộ trường hợp nào). */
  public CompletionStage<Optional<JsonObject>> verifyLogin(String username, String rawPassword) {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT id, username, password_hash FROM users WHERE username = $1")
        .execute(Tuple.of(username))
        .toCompletionStage()
        .thenApply(
            rows -> {
              var row = rows.iterator();
              if (!row.hasNext()) {
                return Optional.<JsonObject>empty();
              }
              var r = row.next();
              if (!BCrypt.checkpw(rawPassword, r.getString("password_hash"))) {
                return Optional.<JsonObject>empty();
              }
              return Optional.of(new JsonObject().put("id", r.getUUID("id").toString()).put("username", r.getString("username")));
            }));
  }

  /** Đổi tên hiển thị của chính mình — id lấy từ token đã verify (xem HallApiHandlers), không còn là upsert (row phải đã tồn tại từ lúc register). */
  public CompletionStage<Void> updateUsername(UUID id, String newUsername) {
    return supplier
        .execute(conn -> conn.preparedQuery("UPDATE users SET username = $2 WHERE id = $1")
            .execute(Tuple.of(id, newUsername))
            .toCompletionStage()
            .thenApply(unused -> (Void) null))
        .exceptionally(ex -> { throw isUniqueViolation(ex) ? new UsernameTakenException(newUsername) : asUnchecked(ex); });
  }

  /** Toàn bộ user đã từng xuất hiện — dùng cho UI hiện danh sách để chọn (DM peer/thành viên group). Không bao giờ trả password_hash. */
  public CompletionStage<JsonArray> listUsers() {
    return supplier.executeReadOnly(conn -> conn.preparedQuery("SELECT id, username, first_seen_at FROM users ORDER BY username, first_seen_at DESC")
        .execute()
        .toCompletionStage()
        .thenApply(
            rows -> {
              var result = new JsonArray();
              for (var row : rows) {
                result.add(
                    new JsonObject()
                        .put("id", row.getUUID("id").toString())
                        .put("username", row.getString("username"))
                        .put("firstSeenAt", row.getOffsetDateTime("first_seen_at").toInstant().toEpochMilli()));
              }
              return result;
            }));
  }

  /**
   * {@code JdbcConnectionSupplier} chạy qua {@code vertx-jdbc-client} (driver JDBC thật), khác
   * {@code vertx-pg-client} cũ (wire-protocol Postgres thuần) — lỗi constraint giờ có thể trồi lên
   * dạng {@link SQLException} chuẩn JDBC thay vì {@link PgException} riêng của pg-client, nên check
   * cả 2 dạng cho chắc.
   */
  private static boolean isUniqueViolation(Throwable ex) {
    var cause = ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    if (cause instanceof PgException pgEx) {
      return UNIQUE_VIOLATION_SQLSTATE.equals(pgEx.getSqlState());
    }
    if (cause instanceof SQLException sqlEx) {
      return UNIQUE_VIOLATION_SQLSTATE.equals(sqlEx.getSQLState());
    }
    return false;
  }

  private static RuntimeException asUnchecked(Throwable ex) {
    return ex instanceof RuntimeException re ? re : new RuntimeException(ex);
  }
}
