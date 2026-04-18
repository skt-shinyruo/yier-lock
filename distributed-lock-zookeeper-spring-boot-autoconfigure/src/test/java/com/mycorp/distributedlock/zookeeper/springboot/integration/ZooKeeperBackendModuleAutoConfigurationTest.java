package com.mycorp.distributedlock.zookeeper.springboot.integration;

import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.zookeeper.ZooKeeperBackendModule;
import com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockAutoConfiguration;
import com.mycorp.distributedlock.zookeeper.springboot.config.ZooKeeperDistributedLockProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ZooKeeperBackendModuleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ZooKeeperDistributedLockAutoConfiguration.class));

    @Test
    void shouldBindZooKeeperPropertiesAndExposeBackendModule() {
        contextRunner
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2281",
                "distributed.lock.zookeeper.base-path=/test-locks"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ZooKeeperDistributedLockProperties.class);
                assertThat(context).hasSingleBean(BackendModule.class);
                assertThat(context.getBean(BackendModule.class)).isInstanceOf(ZooKeeperBackendModule.class);

                ZooKeeperDistributedLockProperties properties = context.getBean(ZooKeeperDistributedLockProperties.class);
                assertThat(properties.getConnectString()).isEqualTo("127.0.0.1:2281");
                assertThat(properties.getBasePath()).isEqualTo("/test-locks");
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
                assertThat(context).doesNotHaveBean(BackendModule.class);
                assertThat(context).doesNotHaveBean(ZooKeeperDistributedLockProperties.class);
            });
    }

    @Test
    void shouldBackOffForUserSuppliedBackendModuleOverrideByBeanName() {
        contextRunner
            .withUserConfiguration(UserZooKeeperBackendOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BackendModule.class);
                assertThat(context.getBean("zooKeeperBackendModule")).isInstanceOf(NamedBackendModule.class);
                assertThat(context.getBeansOfType(BackendModule.class)).hasSize(1);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserZooKeeperBackendOverrideConfiguration {

        @Bean
        BackendModule zooKeeperBackendModule() {
            return new NamedBackendModule("zookeeper");
        }
    }

    private static final class NamedBackendModule implements BackendModule {

        private final String id;

        private NamedBackendModule(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BackendCapabilities capabilities() {
            return BackendCapabilities.standard();
        }

        @Override
        public LockBackend createBackend() {
            return new LockBackend() {
                @Override
                public BackendSession openSession() {
                    throw new UnsupportedOperationException("not used in test");
                }
            };
        }
    }
}
