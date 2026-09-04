package com.pingo.core.boot.start.yaml.ex;


import com.pingo.core.boot.start.yaml.StringGenerator;
import com.pingo.core.common.exception.LegoException;

public class DuplicateStringGeneratorException extends LegoException {

    private static final long serialVersionUID = -2867551586541033210L;

    public DuplicateStringGeneratorException(String name, StringGenerator generator) {
        super(
                "Duplicate String generator register on name: " + name + ", existing value: " + generator);
    }
}

