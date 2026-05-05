package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntimeDecorator;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import com.mycorp.distributedlock.springboot.key.SpelLockKeyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@AutoConfiguration
@EnableConfigurationProperties(DistributedLockProperties.class)
@ConditionalOnProperty(prefix = "distributed.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DistributedLockAutoConfiguration {

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
        ObjectProvider<SynchronousLockExecutor> lockExecutor,
        ObjectProvider<LockKeyResolver> lockKeyResolver,
        DistributedLockProperties properties
    ) {
        return new DistributedLockAspect(lockExecutor, lockKeyResolver, properties);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(LockRuntime.class)
    static class DefaultLockRuntimeConfiguration {

        @Bean(destroyMethod = "close")
        LockRuntime lockRuntime(
            DistributedLockProperties properties,
            Map<String, BackendProvider<?>> backendProviders,
            Map<String, BackendConfiguration> backendConfigurations,
            ObjectProvider<LockRuntimeDecorator> decorators
        ) {
            LockRuntimeBuilder builder = LockRuntimeBuilder.create()
                .backend(properties.getBackend())
                .backendProviders(backendProviders.values().stream().toList())
                .decorators(decorators.orderedStream().toList());

            for (BackendConfiguration configuration : backendConfigurations.values()) {
                builder.backendConfiguration(configuration);
            }

            return builder.build();
        }
    }
}
