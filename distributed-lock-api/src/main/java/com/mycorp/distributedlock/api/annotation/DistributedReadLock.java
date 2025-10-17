package com.mycorp.distributedlock.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

/**
 * 分布式读锁注解，用于方法级或类级读锁定。
 * 
 * 该注解应用于方法或类，表示在执行时获取分布式读锁。
 * - value(): 锁键前缀，默认空字符串。
 * - timeout(): 获取锁的超时时间，默认30秒。
 * 
 * 示例：@DistributedReadLock(value = "cache:", timeout = Duration.ofSeconds(10))
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedReadLock {
    /**
     * 锁键前缀，用于生成唯一的锁键。
     * 如果为空，将使用方法名和参数生成默认键。
     * 
     * @return 锁键前缀
     */
    String value() default "";

    /**
     * 获取锁的超时时间（秒）。
     *
     * @return 超时秒数
     */
    long timeoutSeconds() default 30L;
}