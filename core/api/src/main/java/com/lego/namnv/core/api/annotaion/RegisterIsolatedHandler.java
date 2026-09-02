package com.lego.namnv.core.api.annotaion;




import com.lego.namnv.core.api.decoration.dummy.DummyErrorDecorator;
import com.lego.namnv.core.api.decoration.dummy.DummyRequestDecorator;
import com.lego.namnv.core.api.decoration.dummy.DummyResponseDecorator;
import com.lego.namnv.core.api.decoration.error.ErrorDecorator;
import com.lego.namnv.core.api.decoration.request.RequestDecorator;
import com.lego.namnv.core.api.decoration.response.ResponseDecorator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface RegisterIsolatedHandler {

    RegisterIApi[] apis();

    Class<? extends RequestDecorator<?>>[] onRequest() default DummyRequestDecorator.class;

    Class<? extends ResponseDecorator<?>>[] onResponse() default DummyResponseDecorator.class;

    Class<? extends ErrorDecorator> onError() default DummyErrorDecorator.class;
}