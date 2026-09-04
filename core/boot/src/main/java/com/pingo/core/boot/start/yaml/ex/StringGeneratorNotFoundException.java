package com.pingo.core.boot.start.yaml.ex;

import com.pingo.core.common.exception.LegoException;

public class StringGeneratorNotFoundException extends LegoException {

    private static final long serialVersionUID = -669646132116058713L;

    public StringGeneratorNotFoundException(String name) {
        super("String generator not found for name: " + name);
    }
}
