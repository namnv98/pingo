package com.lego.namnv.core.api.annotaion;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD})
public @interface RegisterIApi {

    int version() default 1;

    ApiMethod method() default ApiMethod.UNKNOWN;

    String endpoint();

    boolean rpc() default false;

    Type type();
}
