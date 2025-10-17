package com.mycorp.distributedlock.springboot.config;

import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * 启用分布式锁支持的注解。
 * 在 Spring Boot 应用配置类上使用此注解来启用自动配置。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(DistributedLockAutoConfiguration.class)
public @interface EnableDistributedLock {
}