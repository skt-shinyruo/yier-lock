package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.LockClient;
import com.mycorp.distributedlock.api.LockExecutor;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.key.LockKeyResolver;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedLockAutoConfigurationIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(TestBackendConfiguration.class);

    @Test
    void shouldRegisterLockRuntimeCoreBeansAndAspect() {
        contextRunner
            .withPropertyValues("distributed.lock.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context).hasSingleBean(LockClient.class);
                assertThat(context).hasSingleBean(LockExecutor.class);
                assertThat(context).hasSingleBean(LockKeyResolver.class);
                assertThat(context).hasSingleBean(DistributedLockAspect.class);
                assertThat(context).doesNotHaveBean("lockManager");
            });
    }

    @Test
    void shouldBackOffWhenDisabled() {
        contextRunner
            .withPropertyValues("distributed.lock.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(LockRuntime.class);
                assertThat(context).doesNotHaveBean(LockClient.class);
                assertThat(context).doesNotHaveBean(LockExecutor.class);
                assertThat(context).doesNotHaveBean(DistributedLockAspect.class);
                assertThat(context).doesNotHaveBean("lockManager");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestBackendConfiguration {

        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }
    }
}
