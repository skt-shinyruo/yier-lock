package com.mycorp.distributedlock.zookeeper.springboot.integration;

import com.mycorp.distributedlock.api.LockRuntime;
import com.mycorp.distributedlock.spi.BackendConfiguration;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendConfiguration;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendProvider;
import com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperBackendProviderAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ZooKeeperDistributedLockAutoConfiguration.class));

    private final ApplicationContextRunner starterContextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DistributedLockAutoConfiguration.class,
            ZooKeeperDistributedLockAutoConfiguration.class
        ));

    @Test
    void shouldBindZooKeeperPropertiesAndExposeProviderAndConfiguration() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2281",
                "distributed.lock.zookeeper.base-path=/test-locks"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ZooKeeperDistributedLockProperties.class);
                assertThat(context).hasSingleBean(BackendProvider.class);
                assertThat(context.getBean(BackendProvider.class)).isInstanceOf(ZooKeeperBackendProvider.class);
                assertThat(context).hasBean("zooKeeperDistributedLockBackendProvider");
                assertThat(context).hasSingleBean(BackendConfiguration.class);
                assertThat(context.getBean(BackendConfiguration.class)).isInstanceOf(ZooKeeperBackendConfiguration.class);

                ZooKeeperDistributedLockProperties properties = context.getBean(ZooKeeperDistributedLockProperties.class);
                assertThat(properties.getConnectString()).isEqualTo("127.0.0.1:2281");
                assertThat(properties.getBasePath()).isEqualTo("/test-locks");

                ZooKeeperBackendConfiguration configuration = context.getBean(ZooKeeperBackendConfiguration.class);
                assertThat(configuration.connectString()).isEqualTo("127.0.0.1:2281");
                assertThat(configuration.basePath()).isEqualTo("/test-locks");
            });
    }

    @Test
    void shouldFailWhenZooKeeperConnectStringIsMissing() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.base-path=/test-locks"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("BindValidationException")
                    .hasStackTraceContaining("distributed.lock.zookeeper.connectString");
            });
    }

    @Test
    void shouldFailWhenZooKeeperBasePathIsMissing() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2281"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("BindValidationException")
                    .hasStackTraceContaining("distributed.lock.zookeeper.basePath");
            });
    }

    @Test
    void shouldBackOffWhenBackendSelectionDoesNotMatch() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=redis"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(BackendProvider.class);
                assertThat(context).doesNotHaveBean(BackendConfiguration.class);
                assertThat(context).doesNotHaveBean(ZooKeeperDistributedLockProperties.class);
            });
    }

    @Test
    void shouldBackOffForUserSuppliedDefaultProviderBeanName() {
        contextRunner
            .withUserConfiguration(UserZooKeeperBackendOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BackendProvider.class);
                assertThat(context.getBean("zooKeeperDistributedLockBackendProvider")).isInstanceOf(NamedBackendProvider.class);
            });
    }

    @Test
    void shouldAutoConfigureDefaultProviderWhenUnrelatedProviderExists() {
        contextRunner
            .withUserConfiguration(UnrelatedBackendProviderConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2281",
                "distributed.lock.zookeeper.base-path=/test-locks"
            )
            .run(context -> {
                assertThat(context.getBeansOfType(BackendProvider.class)).hasSize(2);
                assertThat(context.getBean("unrelatedBackendProvider")).isInstanceOf(NamedBackendProvider.class);
                assertThat(context.getBean("zooKeeperDistributedLockBackendProvider")).isInstanceOf(ZooKeeperBackendProvider.class);
            });
    }

    @Test
    void shouldBackOffWhenLockRuntimeIsUserSupplied() {
        contextRunner
            .withUserConfiguration(UserLockRuntimeOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context).doesNotHaveBean("zooKeeperDistributedLockBackendProvider");
                assertThat(context).doesNotHaveBean(BackendProvider.class);
                assertThat(context).doesNotHaveBean(BackendConfiguration.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserZooKeeperBackendOverrideConfiguration {

        @Bean
        BackendProvider<?> zooKeeperDistributedLockBackendProvider() {
            return new NamedBackendProvider("zookeeper");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrelatedBackendProviderConfiguration {

        @Bean
        BackendProvider<?> unrelatedBackendProvider() {
            return new NamedBackendProvider("unrelated");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserLockRuntimeOverrideConfiguration {

        @Bean
        LockRuntime userLockRuntime() {
            return TestRuntimes.stub("zookeeper");
        }
    }

    private static final class NamedBackendProvider extends TestBackendProviderSupport {
        private NamedBackendProvider(String id) {
            super(id);
        }
    }
}
