package com.pingo.hall.api.error;

import com.pingo.core.api.error.RegisterErrorMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Error key -> HTTP status code, quét tự động bởi {@code HttpStatusErrorMapping.scanAndCreate}
 * (annotation {@code @RegisterErrorMapper} trên field String) — handler ném
 * {@code LegoBusinessException(<key>, message)}, framework tự tra ra đúng status code qua đây,
 * không cần set status code tay ở từng chỗ như trước.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HallErrorKeys {

  @RegisterErrorMapper(400)
  public static final String VALIDATION = "hall.error.validation";

  @RegisterErrorMapper(401)
  public static final String UNAUTHORIZED = "hall.error.unauthorized";

  @RegisterErrorMapper(409)
  public static final String CONFLICT = "hall.error.conflict";

  @RegisterErrorMapper(503)
  public static final String DRAINING = "hall.error.draining";
}
