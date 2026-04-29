package com.mycorp.distributedlock.springboot.annotation;

import com.mycorp.distributedlock.api.LockMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {

    String key();

    LockMode mode() default LockMode.MUTEX;

    String waitFor() default "";

    String leaseFor() default "";
}
