package com.pingo.herald.api.error;

import com.pingo.core.api.error.RegisterErrorMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Error key -> HTTP status code, quét tự động bởi {@code HttpStatusErrorMapping.scanAndCreate} —
 * cùng pattern {@code HallErrorKeys}.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HeraldErrorKeys {

  @RegisterErrorMapper(400)
  public static final String VALIDATION = "herald.error.validation";

  @RegisterErrorMapper(401)
  public static final String UNAUTHORIZED = "herald.error.unauthorized";

  @RegisterErrorMapper(503)
  public static final String DRAINING = "herald.error.draining";
}
