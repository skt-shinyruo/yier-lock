package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import com.mycorp.distributedlock.springboot.key.SpelLockKeyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(DistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public LockRuntime lockRuntime(
        DistributedLockProperties properties,
        ObjectProvider<BackendModule> backendModules
    ) {
        String backendId = properties.getBackend();
        LockRuntimeBuilder builder = LockRuntimeBuilder.create()
            .backend(backendId);
        List<BackendModule> modules = backendModules.orderedStream().toList();
        if (modules.isEmpty()) {
            if (backendId == null || backendId.isBlank()) {
                throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
            }
            throw new LockConfigurationException("Requested backend not found: " + backendId);
        }
        builder.backendModules(modules);
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    public LockClient lockClient(LockRuntime runtime) {
        return runtime.lockClient();
    }

    @Bean
    @ConditionalOnMissingBean
    public SynchronousLockExecutor synchronousLockExecutor(LockRuntime runtime) {
        return runtime.synchronousLockExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public LockKeyResolver lockKeyResolver() {
        return new SpelLockKeyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "distributed.lock.spring.annotation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    public DistributedLockAspect distributedLockAspect(
        SynchronousLockExecutor lockExecutor,
        LockKeyResolver lockKeyResolver
    ) {
        return new DistributedLockAspect(lockExecutor, lockKeyResolver);
    }
}
