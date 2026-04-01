package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.exception.LockConfigurationException;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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

    @Configuration(proxyBeanMethods = false)
    static class TestApplication {

        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }

        @Bean
        AsyncService asyncService() {
            return new AsyncService();
        }
    }

    static class AsyncService {

        private final AtomicBoolean invoked = new AtomicBoolean();

        @DistributedLock(key = "job:#{#p0}")
        public CompletionStage<String> processAsync(String jobId) {
            invoked.set(true);
            return CompletableFuture.completedFuture("processed-" + jobId);
        }

        boolean wasInvoked() {
            return invoked.get();
        }
    }
}
