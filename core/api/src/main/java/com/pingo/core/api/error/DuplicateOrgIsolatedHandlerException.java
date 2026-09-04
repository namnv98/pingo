package com.pingo.core.api.error;

import com.pingo.core.common.exception.LegoException;
import com.pingo.core.api.IApiKey;
import com.pingo.core.api.OrgHandlerProvider;

public class DuplicateOrgIsolatedHandlerException extends LegoException {
    public DuplicateOrgIsolatedHandlerException(IApiKey key, OrgHandlerProvider old) {
    }
}
