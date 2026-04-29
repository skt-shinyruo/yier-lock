package com.mycorp.distributedlock.springboot.config;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.SynchronousLockExecutor;
import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.LockRuntimeBuilder;
import com.mycorp.distributedlock.spi.BackendModule;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import com.mycorp.distributedlock.springboot.key.SpelLockKeyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
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

        private static final Map<String, AutoDefaultBackendModule> AUTO_DEFAULT_BACKEND_MODULES = Map.of(
            "redisDistributedLockBackendModule",
            new AutoDefaultBackendModule(
                "redisBackendModule",
                "com.mycorp.distributedlock.redis.springboot.config.RedisDistributedLockAutoConfiguration$DefaultRedisBackendConfiguration"
            ),
            "zooKeeperDistributedLockBackendModule",
            new AutoDefaultBackendModule(
                "zooKeeperBackendModule",
                "com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockAutoConfiguration$DefaultZooKeeperBackendConfiguration"
            )
        );

        private record AutoDefaultBackendModule(String factoryMethodName, String configurationClassName) {
        }

        @Bean(destroyMethod = "close")
        LockRuntime lockRuntime(
            DistributedLockProperties properties,
            Map<String, BackendModule> backendModules,
            ConfigurableListableBeanFactory beanFactory,
            ObjectProvider<LockRuntimeCustomizer> customizers
        ) {
            String backendId = properties.getBackend();
            LockRuntimeBuilder builder = LockRuntimeBuilder.create()
                .backend(backendId);
            List<BackendModule> modules = backendModulesForRuntime(backendModules, beanFactory);
            if (modules.isEmpty()) {
                if (backendId == null || backendId.isBlank()) {
                    throw new LockConfigurationException("A backend id must be configured before building the lock runtime");
                }
                throw new LockConfigurationException("Requested backend not found: " + backendId);
            }
            builder.backendModules(modules);
            LockRuntime runtime = builder.build();
            for (LockRuntimeCustomizer customizer : customizers.orderedStream().toList()) {
                runtime = customizer.customize(runtime);
            }
            return runtime;
        }

        private List<BackendModule> backendModulesForRuntime(
            Map<String, BackendModule> backendModules,
            ConfigurableListableBeanFactory beanFactory
        ) {
            List<Map.Entry<String, BackendModule>> entries = new ArrayList<>(backendModules.entrySet());
            entries.removeIf(entry -> isAutoDefaultOverridden(entry, entries, beanFactory));
            return entries.stream()
                .map(Map.Entry::getValue)
                .toList();
        }

        private boolean isAutoDefaultOverridden(
            Map.Entry<String, BackendModule> candidate,
            List<Map.Entry<String, BackendModule>> backendModules,
            ConfigurableListableBeanFactory beanFactory
        ) {
            if (!isKnownAutoDefaultBean(candidate.getKey(), beanFactory)) {
                return false;
            }

            String autoDefaultId = candidate.getValue().id();
            return backendModules.stream()
                .anyMatch(entry -> !entry.getKey().equals(candidate.getKey()) && autoDefaultId.equals(entry.getValue().id()));
        }

        private boolean isKnownAutoDefaultBean(String beanName, ConfigurableListableBeanFactory beanFactory) {
            AutoDefaultBackendModule expected = AUTO_DEFAULT_BACKEND_MODULES.get(beanName);
            if (expected == null || !beanFactory.containsBeanDefinition(beanName)) {
                return false;
            }

            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
            if (!expected.factoryMethodName().equals(beanDefinition.getFactoryMethodName())) {
                return false;
            }

            String factoryBeanName = beanDefinition.getFactoryBeanName();
            if (factoryBeanName == null || !beanFactory.containsBeanDefinition(factoryBeanName)) {
                return false;
            }

            String factoryBeanClassName = beanFactory.getBeanDefinition(factoryBeanName).getBeanClassName();
            return expected.configurationClassName().equals(factoryBeanClassName);
        }
    }
}
