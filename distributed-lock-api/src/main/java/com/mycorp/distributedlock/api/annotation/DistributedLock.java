package com.mycorp.distributedlock.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;

/**
 * 分布式锁注解，用于方法级或类级锁定。
 * 
 * 该注解应用于方法或类，表示在执行时获取分布式锁。
 * - value(): 锁键前缀，默认空字符串。
 * - timeout(): 获取锁的超时时间，默认30秒。
 * - fair(): 是否使用公平锁，默认false。
 * 
 * 示例：@DistributedLock(value = "order:", timeout = Duration.ofSeconds(60), fair = true)
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
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

    /**
     * 是否使用公平锁模式。
     * 
     * @return true如果公平锁
     */
    boolean fair() default false;
}