package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.client.CurrentLockContext;
import com.mycorp.distributedlock.runtime.spi.BackendModule;
import com.mycorp.distributedlock.springboot.annotation.DistributedLock;
import com.mycorp.distributedlock.springboot.annotation.DistributedLockMode;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.testkit.support.InMemoryBackendModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedLockAspectIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
        .withUserConfiguration(TestApplication.class)
        .withPropertyValues(
            "distributed.lock.enabled=true",
            "distributed.lock.backend=in-memory"
        );

    @Test
    void shouldSerializeAnnotatedMethodCallsByKey() {
        contextRunner.run(context -> {
            TestService service = context.getBean(TestService.class);
            GuardedResource guardedResource = context.getBean(GuardedResource.class);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);

                Future<String> firstCall = executor.submit(() -> service.process("42", entered, release));
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

                Future<String> secondCall = executor.submit(() -> service.process("42", new CountDownLatch(0), new CountDownLatch(0)));

                assertThatThrownBy(secondCall::get)
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(LockAcquisitionTimeoutException.class)
                    .hasMessageContaining("order:42");

                release.countDown();
                assertThat(firstCall.get(1, TimeUnit.SECONDS)).isEqualTo("processed-42");
                assertThat(service.maxConcurrentInvocations()).isEqualTo(1);
                assertThat(guardedResource.lastObservedFencingToken()).isPositive();
            } finally {
                executor.shutdownNow();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestApplication {

        @Bean
        BackendModule inMemoryBackendModule() {
            return new InMemoryBackendModule("in-memory");
        }

        @Bean
        TestService testService(GuardedResource guardedResource) {
            return new TestService(guardedResource);
        }

        @Bean
        GuardedResource guardedResource() {
            return new GuardedResource();
        }
    }

    static class TestService {
        private final GuardedResource guardedResource;

        private final AtomicInteger concurrentInvocations = new AtomicInteger();
        private final AtomicInteger maxConcurrentInvocations = new AtomicInteger();

        TestService(GuardedResource guardedResource) {
            this.guardedResource = guardedResource;
        }

        @DistributedLock(key = "order:#{#p0}", mode = DistributedLockMode.MUTEX, waitFor = "50ms")
        public String process(String jobId, CountDownLatch entered, CountDownLatch release) throws InterruptedException {
            int concurrent = concurrentInvocations.incrementAndGet();
            maxConcurrentInvocations.updateAndGet(previous -> Math.max(previous, concurrent));
            entered.countDown();
            try {
                release.await();
                return guardedResource.writeAndReturn(jobId);
            } finally {
                concurrentInvocations.decrementAndGet();
            }
        }

        int maxConcurrentInvocations() {
            return maxConcurrentInvocations.get();
        }
    }

    static class GuardedResource {

        private final AtomicLong lastObservedFencingToken = new AtomicLong();

        String writeAndReturn(String orderId) {
            long token = CurrentLockContext.requireCurrentFencingToken().value();
            lastObservedFencingToken.set(token);
            return "processed-" + orderId;
        }

        long lastObservedFencingToken() {
            return lastObservedFencingToken.get();
        }
    }
}
