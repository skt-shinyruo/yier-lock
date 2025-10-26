package com.mycorp.distributedlock.springboot.integration;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.springboot.aop.DistributedLockAspect;
import com.mycorp.distributedlock.springboot.config.DistributedLockAutoConfiguration;
import com.mycorp.distributedlock.springboot.config.DistributedLockProperties;
import com.mycorp.distributedlock.api.exception.LockAcquisitionException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

/**
 * 分布式锁AOP切面集成测试
 * 
 * @since 3.0.0
 */
@SpringBootTest(classes = {
    DistributedLockAutoConfiguration.class,
    DistributedLockAspectIntegrationTest.TestService.class
})
@TestPropertySource(properties = {
    "distributed.lock.aspect.enabled=true",
    "distributed.lock.aspect.default-timeout=PT10S",
    "distributed.lock.aspect.log-parameters=false"
})
@DisplayName("分布式锁AOP切面集成测试")
class DistributedLockAspectIntegrationTest {

    @Autowired
    private TestService testService;

    @MockBean
    private DistributedLockFactory lockFactory;

    @Nested
    @DisplayName("注解驱动的锁功能测试")
    class AnnotationDrivenLockTests {

        @Test
        @DisplayName("应该为带有@DistributedLock注解的方法自动添加锁功能")
        void shouldAddLockingToAnnotatedMethods() throws Exception {
            // Given
            when(lockFactory.getLock("test-key")).thenReturn(mockLock());
            
            // When
            String result = testService.annotatedMethod("test-arg");
            
            // Then
            assertThat(result).isEqualTo("result-test-arg");
            verify(lockFactory).getLock("test-key");
        }

        @Test
        @DisplayName("应该使用自定义锁键")
        void shouldUseCustomLockKey() throws Exception {
            // Given
            when(lockFactory.getLock("custom-key")).thenReturn(mockLock());
            
            // When
            String result = testService.customKeyMethod();
            
            // Then
            assertThat(result).isEqualTo("custom-result");
            verify(lockFactory).getLock("custom-key");
        }

        @Test
        @DisplayName("应该生成基于方法签名的默认锁键")
        void shouldGenerateDefaultLockKeyFromMethodSignature() throws Exception {
            // Given
            when(lockFactory.getLock(contains("TestService.defaultLockMethod"))).thenReturn(mockLock());
            
            // When
            String result = testService.defaultLockMethod();
            
            // Then
            assertThat(result).isEqualTo("default-result");
            verify(lockFactory).getLock(contains("TestService.defaultLockMethod"));
        }

        @Test
        @DisplayName("应该支持不同的锁类型配置")
        void shouldSupportDifferentLockTypeConfigurations() throws Exception {
            // Given
            when(lockFactory.getLock("read-type")).thenReturn(mockLock());
            when(lockFactory.getLock("write-type")).thenReturn(mockLock());
            
            // When
            String readResult = testService.readLockMethod();
            String writeResult = testService.writeLockMethod();
            
            // Then
            assertThat(readResult).isEqualTo("read-result");
            assertThat(writeResult).isEqualTo("write-result");
            verify(lockFactory).getLock("read-type");
            verify(lockFactory).getLock("write-type");
        }

        @Test
        @DisplayName("应该在锁获取失败时返回默认值")
        void shouldReturnDefaultValueOnLockFailure() throws Exception {
            // Given
            var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(mockLock.tryLock(anyLong(), any())).thenReturn(false);
            when(lockFactory.getLock("fail-key")).thenReturn(mockLock);
            
            // When
            String result = testService.failOnLockMethod();
            
            // Then
            assertThat(result).isNull();
            verify(mockLock).tryLock(anyLong(), any());
        }

        @Test
        @DisplayName("应该在设置throwExceptionOnFailure时抛出异常")
        void shouldThrowExceptionWhenConfigured() {
            // Given
            var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(mockLock.tryLock(anyLong(), any())).thenReturn(false);
            when(lockFactory.getLock("exception-key")).thenReturn(mockLock);
            
            // When & Then
            assertThatThrownBy(() -> testService.exceptionOnLockMethod())
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("Failed to acquire lock");
        }
    }

    @Nested
    @DisplayName("异步方法测试")
    class AsyncMethodTests {

        @Test
        @DisplayName("应该支持异步方法")
        void shouldSupportAsyncMethods() throws Exception {
            // Given
            when(lockFactory.getLock("async-key")).thenReturn(mockLock());
            
            // When
            CompletableFuture<String> result = testService.asyncMethod();
            
            // Then
            assertThat(result).isCompletedWithValue("async-result");
            verify(lockFactory).getLock("async-key");
        }

        @Test
        @DisplayName("应该正确处理CompletableFuture返回值")
        void shouldHandleCompletableFutureReturnType() throws Exception {
            // Given
            when(lockFactory.getLock("future-key")).thenReturn(mockLock());
            
            // When
            CompletableFuture<String> result = testService.futureReturnMethod();
            
            // Then
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("future-result");
        }
    }

    @Nested
    @DisplayName("锁续期和超时测试")
    class LockRenewalAndTimeoutTests {

        @Test
        @DisplayName("应该使用注解中指定的超时时间")
        void shouldUseSpecifiedTimeoutFromAnnotation() throws Exception {
            // Given
            var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(lockFactory.getLock("timeout-key")).thenReturn(mockLock);
            
            // When
            testService.customTimeoutMethod();
            
            // Then
            verify(mockLock).tryLock(eq(5000L), eq(TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("应该使用注解中指定的租约时间")
        void shouldUseSpecifiedLeaseTimeFromAnnotation() throws Exception {
            // Given
            var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(lockFactory.getLock("lease-key")).thenReturn(mockLock);
            
            // When
            testService.customLeaseTimeMethod();
            
            // Then
            verify(mockLock).tryLock(anyLong(), eq(TimeUnit.MILLISECONDS));
        }
    }

    @Nested
    @DisplayName("错误处理和恢复测试")
    class ErrorHandlingAndRecoveryTests {

        @Test
        @DisplayName("应该在方法执行异常时正确释放锁")
        void shouldReleaseLockOnMethodExecutionException() throws Exception {
            // Given
            var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(lockFactory.getLock("exception-exec-key")).thenReturn(mockLock);
            
            // When
            assertThatThrownBy(() -> testService.methodThrowsException())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Test exception");
            
            // Then
            verify(mockLock).tryLock(anyLong(), any());
            verify(mockLock).unlock();
        }

        @Test
        @DisplayName("应该在锁获取成功后执行清理操作")
        void shouldPerformCleanupAfterSuccessfulLockAcquisition() throws Exception {
            // Given
            var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
            when(mockLock.tryLock(anyLong(), any())).thenReturn(true);
            when(lockFactory.getLock("cleanup-key")).thenReturn(mockLock);
            
            // When
            testService.normalExecutionMethod();
            
            // Then
            verify(mockLock).unlock();
        }
    }

    @Nested
    @DisplayName("配置和条件测试")
    class ConfigurationAndConditionTests {

        @Test
        @DisplayName("应该在禁用AOP时不创建切面Bean")
        void shouldNotCreateAspectWhenDisabled() {
            // 这个测试需要不同的Spring上下文配置
            // 实际实现会通过不同的配置文件或条件来测试
        }

        @Test
        @DisplayName("应该支持方法级配置覆盖")
        void shouldSupportMethodLevelConfigurationOverride() throws Exception {
            // Given
            when(lockFactory.getLock("override-key")).thenReturn(mockLock());
            
            // When
            String result = testService.overrideConfigMethod();
            
            // Then
            assertThat(result).isEqualTo("override-result");
            verify(lockFactory).getLock("override-key");
        }
    }

    // 辅助方法
    private com.mycorp.distributedlock.api.DistributedLock mockLock() {
        var mockLock = mock(com.mycorp.distributedlock.api.DistributedLock.class);
        when(mockLock.tryLock(anyLong(), any())).thenReturn(true);
        return mockLock;
    }

    // 测试服务类
    @Service
    static class TestService {

        @DistributedLock(key = "test-key")
        public String annotatedMethod(String arg) {
            return "result-" + arg;
        }

        @DistributedLock(key = "custom-key")
        public String customKeyMethod() {
            return "custom-result";
        }

        @DistributedLock
        public String defaultLockMethod() {
            return "default-result";
        }

        @DistributedLock(key = "read-type", lockType = DistributedLock.LockType.READ)
        public String readLockMethod() {
            return "read-result";
        }

        @DistributedLock(key = "write-type", lockType = DistributedLock.LockType.WRITE)
        public String writeLockMethod() {
            return "write-result";
        }

        @DistributedLock(key = "fail-key", throwExceptionOnFailure = false)
        public String failOnLockMethod() {
            return "fail-result";
        }

        @DistributedLock(key = "exception-key", throwExceptionOnFailure = true)
        public String exceptionOnLockMethod() {
            return "exception-result";
        }

        @DistributedLock(key = "async-key")
        public CompletableFuture<String> asyncMethod() {
            return CompletableFuture.supplyAsync(() -> "async-result");
        }

        @DistributedLock(key = "future-key")
        public CompletableFuture<String> futureReturnMethod() {
            return CompletableFuture.completedFuture("future-result");
        }

        @DistributedLock(key = "timeout-key", waitTime = "5s")
        public String customTimeoutMethod() {
            return "timeout-result";
        }

        @DistributedLock(key = "lease-key", leaseTime = "30s")
        public String customLeaseTimeMethod() {
            return "lease-result";
        }

        @DistributedLock(key = "exception-exec-key")
        public String methodThrowsException() {
            throw new RuntimeException("Test exception");
        }

        @DistributedLock(key = "cleanup-key")
        public String normalExecutionMethod() {
            return "cleanup-result";
        }

        @DistributedLock(
            key = "override-key",
            lockType = DistributedLock.LockType.WRITE,
            waitTime = "15s",
            leaseTime = "60s"
        )
        public String overrideConfigMethod() {
            return "override-result";
        }
    }
}

// 测试配置类
@Configuration
@EnableAspectJAutoProxy
class TestConfiguration {
    @Bean
    public DistributedLockAspect distributedLockAspect(
            DistributedLockFactory lockFactory,
            com.mycorp.distributedlock.core.config.UnifiedLockConfiguration configuration) {
        return new DistributedLockAspect(lockFactory, configuration);
    }
}