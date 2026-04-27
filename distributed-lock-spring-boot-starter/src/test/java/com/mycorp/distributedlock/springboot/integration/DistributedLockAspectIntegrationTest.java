package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.FencingToken;
import com.mycorp.distributedlock.api.LeaseMode;
import com.mycorp.distributedlock.api.LeaseState;
import com.mycorp.distributedlock.api.LockContext;
import com.mycorp.distributedlock.api.LockKey;
import com.mycorp.distributedlock.api.LockMode;
import com.mycorp.distributedlock.api.LockRequest;
import com.mycorp.distributedlock.api.SessionState;
import com.mycorp.distributedlock.api.WaitMode;
import com.mycorp.distributedlock.api.exception.LockAcquisitionTimeoutException;
import com.mycorp.distributedlock.core.backend.BackendLockLease;
import com.mycorp.distributedlock.core.backend.BackendSession;
import com.mycorp.distributedlock.core.backend.LockBackend;
import com.mycorp.distributedlock.runtime.spi.BackendCapabilities;
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

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void shouldMapTryOnceFixedLeaseAndExposePublicLockContext() {
        capturingContextRunner().run(context -> {
            PolicyService service = context.getBean(PolicyService.class);
            CapturingBackend backend = context.getBean(CapturingBackend.class);

            assertThat(service.updateWithLease("42")).isEqualTo("ok");

            LockRequest request = backend.lastRequest();
            assertThat(request.key()).isEqualTo(new LockKey("order:42"));
            assertThat(request.mode()).isEqualTo(LockMode.MUTEX);
            assertThat(request.waitPolicy().mode()).isEqualTo(WaitMode.TRY_ONCE);
            assertThat(request.waitPolicy().timeout()).isEqualTo(Duration.ZERO);
            assertThat(request.leasePolicy().mode()).isEqualTo(LeaseMode.FIXED);
            assertThat(request.leasePolicy().duration()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void shouldMapBlankWaitAndLeaseToIndefiniteAndBackendDefault() {
        capturingContextRunner()
            .withPropertyValues("distributed.lock.spring.annotation.default-timeout=1s")
            .run(context -> {
                PolicyService service = context.getBean(PolicyService.class);
                CapturingBackend backend = context.getBean(CapturingBackend.class);

                assertThat(service.defaultPolicies("42")).isEqualTo("ok");

                LockRequest request = backend.lastRequest();
                assertThat(request.waitPolicy().mode()).isEqualTo(WaitMode.INDEFINITE);
                assertThat(request.waitPolicy().timeout()).isEqualTo(Duration.ZERO);
                assertThat(request.leasePolicy().mode()).isEqualTo(LeaseMode.BACKEND_DEFAULT);
                assertThat(request.leasePolicy().duration()).isEqualTo(Duration.ZERO);
            });
    }

    @Test
    void shouldFailFastForInvalidLeaseDurations() {
        capturingContextRunner().run(context -> {
            PolicyService service = context.getBean(PolicyService.class);

            assertThatThrownBy(() -> service.zeroLease("42"))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.negativeLease("42"))
                .isInstanceOf(IllegalArgumentException.class);
        });
    }

    private ApplicationContextRunner capturingContextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, DistributedLockAutoConfiguration.class))
            .withUserConfiguration(CapturingApplication.class)
            .withPropertyValues(
                "distributed.lock.enabled=true",
                "distributed.lock.backend=capturing"
            );
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
            long token = LockContext.requireCurrentFencingToken().value();
            lastObservedFencingToken.set(token);
            return "processed-" + orderId;
        }

        long lastObservedFencingToken() {
            return lastObservedFencingToken.get();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CapturingApplication {

        @Bean
        CapturingBackend capturingBackend() {
            return new CapturingBackend();
        }

        @Bean
        BackendModule capturingBackendModule(CapturingBackend backend) {
            return new CapturingBackendModule(backend);
        }

        @Bean
        PolicyService policyService() {
            return new PolicyService();
        }
    }

    static class PolicyService {

        @DistributedLock(key = "order:#{#p0}", waitFor = "0s", leaseFor = "10s")
        public String updateWithLease(String id) {
            return LockContext.requireCurrentFencingToken().value() > 0 ? "ok" : "bad";
        }

        @DistributedLock(key = "order:#{#p0}")
        public String defaultPolicies(String id) {
            return LockContext.requireCurrentFencingToken().value() > 0 ? "ok" : "bad";
        }

        @DistributedLock(key = "order:#{#p0}", leaseFor = "0s")
        public void zeroLease(String id) {
        }

        @DistributedLock(key = "order:#{#p0}", leaseFor = "-1s")
        public void negativeLease(String id) {
        }
    }

    static final class CapturingBackendModule implements BackendModule {

        private final CapturingBackend backend;

        CapturingBackendModule(CapturingBackend backend) {
            this.backend = backend;
        }

        @Override
        public String id() {
            return "capturing";
        }

        @Override
        public BackendCapabilities capabilities() {
            return BackendCapabilities.standard();
        }

        @Override
        public LockBackend createBackend() {
            return backend;
        }
    }

    static final class CapturingBackend implements LockBackend {

        private final AtomicReference<LockRequest> lastRequest = new AtomicReference<>();
        private final AtomicLong fencingCounter = new AtomicLong();

        @Override
        public BackendSession openSession() {
            return new CapturingSession(this);
        }

        LockRequest lastRequest() {
            return lastRequest.get();
        }
    }

    static final class CapturingSession implements BackendSession {

        private final CapturingBackend backend;

        CapturingSession(CapturingBackend backend) {
            this.backend = backend;
        }

        @Override
        public BackendLockLease acquire(LockRequest request) {
            backend.lastRequest.set(request);
            return new CapturingLease(
                request.key(),
                request.mode(),
                new FencingToken(backend.fencingCounter.incrementAndGet()),
                new AtomicReference<>(LeaseState.ACTIVE)
            );
        }

        @Override
        public SessionState state() {
            return SessionState.ACTIVE;
        }

        @Override
        public void close() {
        }
    }

    record CapturingLease(
        LockKey key,
        LockMode mode,
        FencingToken fencingToken,
        AtomicReference<LeaseState> leaseState
    ) implements BackendLockLease {

        @Override
        public LeaseState state() {
            return leaseState.get();
        }

        @Override
        public boolean isValid() {
            return leaseState.get() == LeaseState.ACTIVE;
        }

        @Override
        public void release() {
            leaseState.set(LeaseState.RELEASED);
        }
    }
}
