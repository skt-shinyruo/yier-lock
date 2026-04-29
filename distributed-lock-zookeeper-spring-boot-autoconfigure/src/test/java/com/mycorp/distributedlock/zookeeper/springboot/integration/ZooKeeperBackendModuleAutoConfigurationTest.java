package com.mycorp.distributedlock.zookeeper.springboot.integration;

import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.LockRuntime;
import com.mycorp.distributedlock.spi.BackendCapabilities;
import com.mycorp.distributedlock.spi.BackendModule;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
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

    private final ApplicationContextRunner starterContextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            DistributedLockAutoConfiguration.class,
            ZooKeeperDistributedLockAutoConfiguration.class
        ));

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
                assertThat(context).hasBean("zooKeeperDistributedLockBackendModule");

                ZooKeeperDistributedLockProperties properties = context.getBean(ZooKeeperDistributedLockProperties.class);
                assertThat(properties.getConnectString()).isEqualTo("127.0.0.1:2281");
                assertThat(properties.getBasePath()).isEqualTo("/test-locks");
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
                assertThat(context).doesNotHaveBean(BackendModule.class);
                assertThat(context).doesNotHaveBean(ZooKeeperDistributedLockProperties.class);
            });
    }

    @Test
    void shouldBackOffForUserSuppliedDefaultBackendModuleBeanName() {
        contextRunner
            .withUserConfiguration(UserZooKeeperBackendOverrideConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BackendModule.class);
                assertThat(context.getBean("zooKeeperDistributedLockBackendModule")).isInstanceOf(NamedBackendModule.class);
            });
    }

    @Test
    void shouldAutoConfigureDefaultBackendModuleWhenUnrelatedBackendModuleExists() {
        contextRunner
            .withUserConfiguration(UnrelatedBackendModuleConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2281",
                "distributed.lock.zookeeper.base-path=/test-locks"
            )
            .run(context -> {
                assertThat(context.getBeansOfType(BackendModule.class)).hasSize(2);
                assertThat(context.getBean("unrelatedBackendModule")).isInstanceOf(NamedBackendModule.class);
                assertThat(context.getBean("zooKeeperDistributedLockBackendModule")).isInstanceOf(ZooKeeperBackendModule.class);
            });
    }

    @Test
    void shouldUseSameIdCustomBackendModuleInsteadOfAutoConfiguredDefault() {
        starterContextRunner
            .withUserConfiguration(SameIdZooKeeperBackendModuleConfiguration.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=zookeeper",
                "distributed.lock.zookeeper.connect-string=127.0.0.1:2281",
                "distributed.lock.zookeeper.base-path=/test-locks"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(LockRuntime.class);
                assertThat(context.getBean(LockRuntime.class).backendId()).isEqualTo("zookeeper");
                assertThat(context.getBeansOfType(BackendModule.class)).hasSize(2);
                assertThat(context.getBean("customZooKeeperBackendModule")).isInstanceOf(NamedBackendModule.class);
                assertThat(context.getBean("zooKeeperDistributedLockBackendModule")).isInstanceOf(ZooKeeperBackendModule.class);
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
                assertThat(context).doesNotHaveBean("zooKeeperDistributedLockBackendModule");
                assertThat(context).doesNotHaveBean(BackendModule.class);
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class UserZooKeeperBackendOverrideConfiguration {

        @Bean
        BackendModule zooKeeperDistributedLockBackendModule() {
            return new NamedBackendModule("zookeeper");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnrelatedBackendModuleConfiguration {

        @Bean
        BackendModule unrelatedBackendModule() {
            return new NamedBackendModule("unrelated");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SameIdZooKeeperBackendModuleConfiguration {

        @Bean
        BackendModule customZooKeeperBackendModule() {
            return new NamedBackendModule("zookeeper");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserLockRuntimeOverrideConfiguration {

        @Bean
        LockRuntime userLockRuntime() {
            return new StubLockRuntime();
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

    private static final class StubLockRuntime implements LockRuntime {

        @Override
        public com.mycorp.distributedlock.api.LockClient lockClient() {
            return null;
        }

        @Override
        public com.mycorp.distributedlock.api.SynchronousLockExecutor synchronousLockExecutor() {
            return null;
        }

        @Override
        public String backendId() {
            return "zookeeper";
        }

        @Override
        public BackendCapabilities capabilities() {
            return BackendCapabilities.standard();
        }

        @Override
        public void close() {
        }
    }
}
