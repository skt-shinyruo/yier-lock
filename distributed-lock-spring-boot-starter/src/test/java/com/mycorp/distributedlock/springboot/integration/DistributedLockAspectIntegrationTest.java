package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.api.LockConfigurationBuilder;
import com.mycorp.distributedlock.api.annotation.DistributedLock;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {
    DistributedLockAspectIntegrationTest.AspectTestConfiguration.class,
    DistributedLockAspectIntegrationTest.AspectTestService.class
})
@TestPropertySource(properties = "distributed.lock.aspect.default-timeout=PT9S")
@DisplayName("Distributed lock aspect integration")
class DistributedLockAspectIntegrationTest {

    @Autowired
    private AspectTestService testService;

    @MockBean
    private DistributedLockFactory lockFactory;

    @Test
    @DisplayName("uses the annotation value and timeoutSeconds with the real tryLock signature")
    void shouldAcquireLockUsingAnnotationValueAndTimeoutSeconds() throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mockLock(true);
        when(lockFactory.getLock("annotated-key")).thenReturn(lock);

        assertThat(testService.annotatedMethod()).isEqualTo("annotated");

        verify(lockFactory).getLock("annotated-key");
        verify(lock).tryLock(7L, 7L, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("falls back to the configured aspect timeout when the annotation timeout is non-positive")
    void shouldUseConfiguredDefaultTimeoutWhenAnnotationTimeoutIsNonPositive() throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mockLock(true);
        when(lockFactory.getLock("default-timeout-key")).thenReturn(lock);

        assertThat(testService.defaultTimeoutMethod()).isEqualTo("default-timeout");

        verify(lock).tryLock(9L, 9L, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("generates a default key from the target type and method name when annotation value is empty")
    void shouldGenerateDefaultLockKeyWhenAnnotationValueIsEmpty() throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mockLock(true);
        when(lockFactory.getLock("AspectTestService.defaultKeyMethod")).thenReturn(lock);

        assertThat(testService.defaultKeyMethod()).isEqualTo("default-key");

        verify(lockFactory).getLock("AspectTestService.defaultKeyMethod");
        verify(lock).tryLock(2L, 2L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("routes fair annotations through configured lock acquisition")
    void shouldUseConfiguredLockWhenFairLockIsRequested() throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mockLock(true);
        when(lockFactory.getConfiguredLock(eq("fair-key"), any())).thenReturn(lock);

        assertThat(testService.fairMethod()).isEqualTo("fair");

        verify(lockFactory).getConfiguredLock(
            eq("fair-key"),
            argThat(configuration ->
                configuration != null
                    && "fair-key".equals(configuration.getName())
                    && configuration.isFairLock()
                    && configuration.getWaitTime(TimeUnit.SECONDS) == 5L
                    && configuration.getLeaseTime(TimeUnit.SECONDS) == 5L
            )
        );
        verify(lock).tryLock(5L, 5L, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("throws LockAcquisitionException when the lock cannot be obtained")
    void shouldThrowWhenLockCannotBeAcquired() throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mockLock(false);
        when(lockFactory.getLock("failing-key")).thenReturn(lock);

        assertThatThrownBy(() -> testService.failingMethod())
            .isInstanceOf(LockAcquisitionException.class)
            .hasMessageContaining("failing-key");

        verify(lock).tryLock(3L, 3L, TimeUnit.SECONDS);
        verify(lockFactory).getLock("failing-key");
    }

    @Test
    @DisplayName("releases the lock when the intercepted method throws")
    void shouldReleaseLockWhenInterceptedMethodThrows() throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mockLock(true);
        when(lockFactory.getLock("exception-key")).thenReturn(lock);

        assertThatThrownBy(() -> testService.exceptionMethod())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        verify(lock).tryLock(4L, 4L, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Configuration
    @EnableAspectJAutoProxy
    @EnableConfigurationProperties(DistributedLockProperties.class)
    static class AspectTestConfiguration {

        @Bean
        DistributedLockAspect distributedLockAspect(
            DistributedLockFactory lockFactory,
            DistributedLockProperties properties
        ) {
            return new DistributedLockAspect(lockFactory, properties);
        }
    }

    @Service
    static class AspectTestService {

        @DistributedLock(value = "annotated-key", timeoutSeconds = 7)
        String annotatedMethod() {
            return "annotated";
        }

        @DistributedLock(value = "default-timeout-key", timeoutSeconds = 0)
        String defaultTimeoutMethod() {
            return "default-timeout";
        }

        @DistributedLock(timeoutSeconds = 2)
        String defaultKeyMethod() {
            return "default-key";
        }

        @DistributedLock(value = "fair-key", timeoutSeconds = 5, fair = true)
        String fairMethod() {
            return "fair";
        }

        @DistributedLock(value = "failing-key", timeoutSeconds = 3)
        String failingMethod() {
            return "unreachable";
        }

        @DistributedLock(value = "exception-key", timeoutSeconds = 4)
        String exceptionMethod() {
            throw new IllegalStateException("boom");
        }
    }

    private static com.mycorp.distributedlock.api.DistributedLock mockLock(boolean acquired) throws Exception {
        com.mycorp.distributedlock.api.DistributedLock lock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(acquired);
        when(lock.isHeldByCurrentThread()).thenReturn(acquired);
        return lock;
    }
}
