package com.lego.namnv.core.api.error;

import com.lego.namnv.core.common.exception.LegoException;
import com.lego.namnv.core.api.IApiKey;
import com.lego.namnv.core.api.OrgHandlerProvider;

public class DuplicateOrgIsolatedHandlerException extends LegoException {
    public DuplicateOrgIsolatedHandlerException(IApiKey key, OrgHandlerProvider old) {
    }
}
