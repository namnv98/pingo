package com.pingo.core.api.annotaion;


import com.pingo.core.api.decoration.dummy.DummyErrorDecorator;
import com.pingo.core.api.decoration.dummy.DummyRequestDecorator;
import com.pingo.core.api.decoration.dummy.DummyResponseDecorator;
import com.pingo.core.api.decoration.error.ErrorDecorator;
import com.pingo.core.api.decoration.request.RequestDecorator;
import com.pingo.core.api.decoration.response.ResponseDecorator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface RegisterHandler {

    RegisterIApi[] apis();

    Class<? extends RequestDecorator<?>>[] onRequest() default DummyRequestDecorator.class;

    Class<? extends ResponseDecorator<?>>[] onResponse() default DummyResponseDecorator.class;

    Class<? extends ErrorDecorator> onError() default DummyErrorDecorator.class;
}

