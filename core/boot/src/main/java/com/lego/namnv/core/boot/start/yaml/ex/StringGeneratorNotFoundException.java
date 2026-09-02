package com.lego.namnv.core.boot.start.yaml.ex;

import com.lego.namnv.core.common.exception.LegoException;

public class StringGeneratorNotFoundException extends LegoException {

    private static final long serialVersionUID = -669646132116058713L;

    public StringGeneratorNotFoundException(String name) {
        super("String generator not found for name: " + name);
    }
}
