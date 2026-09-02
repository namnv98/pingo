package com.lego.namnv.core.api.support;

import com.lego.namnv.core.api.error.RegisterErrorMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class IConsts {

    public static final String DEFAULT_SCANNED_PACKAGE = "com.namnv98";

    public static final int UNKNOWN_ERROR_CODE = 500;

    @RegisterErrorMapper(IConsts.UNKNOWN_ERROR_CODE)
    public static final String UNKNOWN_ERROR = "com.namnv98.error.unknown";

}
