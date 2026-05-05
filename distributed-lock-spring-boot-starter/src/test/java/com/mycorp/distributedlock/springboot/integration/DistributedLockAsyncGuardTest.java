package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.spi.BackendProvider;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedLockAsyncGuardTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(TestApplication.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=in-memory"
        );

    @Test
    void shouldRejectCompletionStageReturnTypes() {
        contextRunner.run(context -> {
            AsyncService service = context.getBean(AsyncService.class);

            assertThatThrownBy(() -> service.processAsync("42"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("CompletionStage");

            assertThat(service.wasInvoked()).isFalse();
        });
    }

    @Test
    void shouldRejectFutureReturnTypes() {
        contextRunner.run(context -> {
            AsyncService service = context.getBean(AsyncService.class);

            assertThatThrownBy(() -> service.processFuture("42"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("Future");

            assertThat(service.wasInvoked()).isFalse();
        });
    }

    @Test
    void shouldRejectAsyncVoidBeforeInvocation() {
        contextRunner.run(context -> {
            AsyncService service = context.getBean(AsyncService.class);

            assertThatThrownBy(() -> service.processAsyncVoid("42"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("@Async");
            assertThat(service.wasInvoked()).isFalse();
        });
    }

    @Test
    void objectReturnTypeShouldBeRejectedBeforeInvocationByDefault() {
        contextRunner.run(context -> {
            AsyncService service = context.getBean(AsyncService.class);

            assertThatThrownBy(() -> service.processObjectAsync("42"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("Object");
            assertThat(service.wasInvoked()).isFalse();
        });
    }

    @Test
    void objectReturnTypeCompatibilityModeShouldKeepExecutorDefenseInDepth() {
        contextRunner
            .withPropertyValues("distributed.lock.spring.annotation.allow-dynamic-return-type=true")
            .run(context -> {
                AsyncService service = context.getBean(AsyncService.class);

                assertThatThrownBy(() -> service.processObjectAsync("42"))
                    .isInstanceOf(LockConfigurationException.class)
                    .hasMessageContaining("CompletionStage");
                assertThat(service.wasInvoked()).isTrue();
            });
    }

    @Test
    void kotlinCoroutineSignatureShouldBeRejectedBeforeInvocation() {
        contextRunner.run(context -> {
            AsyncService service = context.getBean(AsyncService.class);

            assertThatThrownBy(() -> service.processCoroutine("42", null))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("Kotlin coroutine");
            assertThat(service.wasInvoked()).isFalse();
        });
    }

    @Test
    void shouldRejectInterfaceAsyncAnnotationBeforeInvocation() {
        contextRunner.withPropertyValues("spring.aop.proxy-target-class=true").run(context -> {
            InterfaceAsyncServiceImpl service = context.getBean(InterfaceAsyncServiceImpl.class);

            assertThatThrownBy(() -> service.processInterfaceAsync("42"))
                .isInstanceOf(LockConfigurationException.class)
                .hasMessageContaining("@Async");
            assertThat(service.wasInvoked()).isFalse();
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApplication {

        @Bean
        BackendProvider<TestBackends.Configuration> inMemoryBackendProvider() {
            return new TestBackends.Provider("in-memory");
        }

        @Bean
        TestBackends.Configuration inMemoryBackendConfiguration() {
            return new TestBackends.Configuration();
        }

        @Bean
        AsyncService asyncService() {
            return new AsyncService();
        }

        @Bean
        InterfaceAsyncServiceImpl interfaceAsyncService() {
            return new InterfaceAsyncServiceImpl();
        }
    }

    interface InterfaceAsyncService {
        @DistributedLock(key = "job:#{#p0}")
        @org.springframework.scheduling.annotation.Async
        void processInterfaceAsync(String jobId);
    }

    static class InterfaceAsyncServiceImpl implements InterfaceAsyncService {

        private final AtomicBoolean invoked = new AtomicBoolean();

        public void processInterfaceAsync(String jobId) {
            invoked.set(true);
        }

        boolean wasInvoked() {
            return invoked.get();
        }
    }

    static class AsyncService {

        private final AtomicBoolean invoked = new AtomicBoolean();

        @DistributedLock(key = "job:#{#p0}")
        public CompletionStage<String> processAsync(String jobId) {
            invoked.set(true);
            return CompletableFuture.completedFuture("processed-" + jobId);
        }

        @DistributedLock(key = "job:#{#p0}")
        public Future<String> processFuture(String jobId) {
            invoked.set(true);
            return CompletableFuture.completedFuture("processed-" + jobId);
        }

        @DistributedLock(key = "job:#{#p0}")
        @org.springframework.scheduling.annotation.Async
        public void processAsyncVoid(String jobId) {
            invoked.set(true);
        }

        @DistributedLock(key = "job:#{#p0}")
        public Object processObjectAsync(String jobId) {
            invoked.set(true);
            return CompletableFuture.completedFuture("processed-" + jobId);
        }

        @DistributedLock(key = "job:#{#p0}")
        public Object processCoroutine(String jobId, kotlin.coroutines.Continuation<String> continuation) {
            invoked.set(true);
            return "processed-" + jobId;
        }

        boolean wasInvoked() {
            return invoked.get();
        }
    }
}
