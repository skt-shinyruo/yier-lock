package com.mycorp.distributedlock.core.observability.monitoring;

import com.mycorp.distributedlock.api.DistributedLock;
import com.mycorp.distributedlock.api.DistributedLockFactory;
import com.mycorp.distributedlock.core.observability.*;
import com.mycorp.distributedlock.core.monitoring.PerformanceMonitor;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 监控和指标测试
 * 
 * 测试分布式锁的监控、指标收集、性能分析和告警功能
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("监控和指标测试")
class MonitoringAndMetricsIntegrationTest {

    @Mock
    private LockProvider mockLockProvider;

    @Mock
    private DistributedLockFactory mockLockFactory;

    @Mock
    private DistributedLock mockLock;

    private MeterRegistry meterRegistry;
    private LockPerformanceMetrics performanceMetrics;
    private LockMonitoringService monitoringService;
    private LockMetricsCollector metricsCollector;
    private MicrometerMetricsAdapter micrometerAdapter;
    private PrometheusMetricsExporter prometheusExporter;
    private PerformanceMonitor performanceMonitor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        
        performanceMetrics = new LockPerformanceMetrics(
            meterRegistry,
            null, // OpenTelemetry
            true, // 启用指标
            true  // 启用追踪
        );
        
        metricsCollector = new LockMetricsCollector(
            meterRegistry,
            null, // OpenTelemetry
            true
        );
        
        micrometerAdapter = new MicrometerMetricsAdapter(
            meterRegistry,
            true, // 启用Micrometer
            "test-app",
            "test-instance"
        );
        
        prometheusExporter = new PrometheusMetricsExporter(
            performanceMetrics,
            null, // MicrometerMetricsAdapter
            null, // LockMetricsCollector
            createPrometheusConfig()
        );
        
        monitoringService = createMonitoringService();
        performanceMonitor = new PerformanceMonitor(performanceMetrics);
    }

    @Nested
    @DisplayName("基础指标收集测试")
    class BasicMetricsCollectionTest {

        @Test
        @DisplayName("应该记录锁获取时间指标")
        void shouldRecordLockAcquisitionTimeMetrics() {
            // Given
            when(mockLockFactory.getLock("test-lock")).thenReturn(mockLock);
            when(mockLock.tryLock(anyLong(), any())).thenReturn(true);
            
            String lockName = "test-lock";
            
            // When
            performanceMetrics.recordLockAcquisitionStart(lockName);
            simulateWork(100); // 模拟100ms的工作时间
            performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            
            // Then
            List<Timer> timers = meterRegistry.find("distributed.lock.acquisition.time").timers();
            assertThat(timers).hasSize(1);
            
            Timer timer = timers.get(0);
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(100);
        }

        @Test
        @DisplayName("应该记录锁持有时间指标")
        void shouldRecordLockHoldTimeMetrics() {
            // Given
            when(mockLockFactory.getLock("hold-test-lock")).thenReturn(mockLock);
            
            String lockName = "hold-test-lock";
            
            // When
            performanceMetrics.recordLockHoldStart(lockName);
            simulateWork(150); // 模拟150ms的锁持有时间
            performanceMetrics.recordLockHoldEnd(lockName);
            
            // Then
            List<Timer> timers = meterRegistry.find("distributed.lock.hold.time").timers();
            assertThat(timers).hasSize(1);
            
            Timer timer = timers.get(0);
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(150);
        }

        @Test
        @DisplayName("应该记录锁竞争指标")
        void shouldRecordLockContentionMetrics() {
            // Given
            List<DistributedLock> locks = createContendedLocks();
            
            // When - 模拟并发竞争
            performContendedOperations(locks);
            
            // Then
            List<Counter> counters = meterRegistry.find("distributed.lock.contention").counters();
            assertThat(counters).isNotEmpty();
            
            // 验证总的竞争次数
            long totalContentions = counters.stream()
                .mapToLong(Counter::count)
                .sum();
            assertThat(totalContentions).isGreaterThan(0);
        }

        @Test
        @DisplayName("应该记录锁重试指标")
        void shouldRecordLockRetryMetrics() {
            // Given
            String lockName = "retry-test-lock";
            DistributedLock retryableLock = createRetryableLock();
            
            when(mockLockFactory.getLock(lockName)).thenReturn(retryableLock);
            
            // When - 模拟重试获取锁
            boolean acquired = acquireWithRetry(lockName, 3, 50);
            
            // Then
            List<Counter> retryCounters = meterRegistry.find("distributed.lock.retry").counters();
            assertThat(retryCounters).isNotEmpty();
            
            // 验证重试次数被正确记录
            long totalRetries = retryCounters.stream()
                .mapToLong(Counter::count)
                .sum();
            assertThat(totalRetries).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("应该记录锁获取成功率")
        void shouldRecordLockAcquisitionSuccessRate() {
            // Given
            List<DistributedLock> locks = createMixedSuccessLocks();
            
            // When
            performMixedAcquisitionOperations(locks);
            
            // Then
            List<Counter> successCounters = meterRegistry.find("distributed.lock.acquisition.success").counters();
            List<Counter> failureCounters = meterRegistry.find("distributed.lock.acquisition.failure").counters();
            
            double successRate = successCounters.stream().mapToDouble(Counter::count).sum() /
                               (successCounters.stream().mapToDouble(Counter::count).sum() +
                                failureCounters.stream().mapToDouble(Counter::count).sum());
            
            assertThat(successRate).isGreaterThan(0.0);
            assertThat(successRate).isLessThanOrEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("性能指标分析测试")
    class PerformanceMetricsAnalysisTest {

        @Test
        @DisplayName("应该计算平均锁获取时间")
        void shouldCalculateAverageLockAcquisitionTime() {
            // Given
            List<String> lockNames = Arrays.asList("perf-lock-1", "perf-lock-2", "perf-lock-3", "perf-lock-4", "perf-lock-5");
            
            // When - 生成不同时间的锁获取
            for (String lockName : lockNames) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(50 + new Random().nextInt(100)); // 随机50-150ms
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
            
            // Then
            LockPerformanceMetrics.PerformanceStats stats = performanceMetrics.getAcquisitionStats("perf-lock-1");
            assertThat(stats.getMeanTime(TimeUnit.MILLISECONDS)).isGreaterThan(50);
            assertThat(stats.getCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("应该识别性能热点")
        void shouldIdentifyPerformanceHotspots() {
            // Given
            List<String> lockNames = createLockNamesWithDifferentPatterns();
            
            // When
            simulateMixedWorkload(lockNames);
            
            // Then
            LockPerformanceMetrics.HotspotAnalysis hotspots = performanceMetrics.analyzeHotspots();
            assertThat(hotspots).isNotNull();
            assertThat(hotspots.getHotLockNames()).isNotEmpty();
        }

        @Test
        @DisplayName("应该计算锁使用率")
        void shouldCalculateLockUtilization() {
            // Given
            String lockName = "utilization-test-lock";
            
            // When - 模拟高使用率
            for (int i = 0; i < 10; i++) {
                performanceMetrics.recordLockHoldStart(lockName);
                simulateWork(100);
                performanceMetrics.recordLockHoldEnd(lockName);
                
                // 模拟短暂间隔
                simulateWork(50);
            }
            
            // Then
            double utilization = performanceMetrics.calculateLockUtilization(lockName);
            assertThat(utilization).isGreaterThan(0.0);
            assertThat(utilization).isLessThanOrEqualTo(1.0);
        }

        @Test
        @DisplayName("应该检测性能退化")
        void shouldDetectPerformanceDegradation() {
            // Given
            String lockName = "degradation-test-lock";
            
            // 模拟正常性能基线
            for (int i = 0; i < 5; i++) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(50); // 50ms基准
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
            
            // 模拟性能退化
            for (int i = 0; i < 3; i++) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(200); // 200ms退化
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
            
            // Then
            boolean isDegraded = performanceMetrics.isPerformanceDegraded(lockName, 2.0); // 2倍阈值
            assertThat(isDegraded).isTrue();
        }

        @Test
        @DisplayName("应该生成性能报告")
        void shouldGeneratePerformanceReport() {
            // Given
            List<String> lockNames = Arrays.asList("report-lock-1", "report-lock-2", "report-lock-3");
            
            // When
            generateSamplePerformanceData(lockNames);
            LockPerformanceMetrics.PerformanceReport report = performanceMetrics.generateReport();
            
            // Then
            assertThat(report).isNotNull();
            assertThat(report.getLockNames()).containsAll(lockNames);
            assertThat(report.getTotalOperations()).isGreaterThan(0);
            assertThat(report.getOverallStats()).isNotNull();
        }
    }

    @Nested
    @DisplayName("监控服务测试")
    class MonitoringServiceTest {

        @Test
        @DisplayName("应该定期收集监控指标")
        void shouldCollectMetricsPeriodically() throws InterruptedException {
            // Given
            CountDownLatch metricsCollectedLatch = new CountDownLatch(3);
            
            // 监控服务配置
            monitoringService.start();
            
            // 模拟锁操作
            performMonitoringOperations();
            
            // When
            boolean collected = metricsCollectedLatch.await(10, TimeUnit.SECONDS);
            
            // Then
            assertThat(collected).isTrue();
            monitoringService.stop();
        }

        @Test
        @DisplayName("应该监控锁健康状态")
        void shouldMonitorLockHealthStatus() {
            // Given
            DistributedLock healthyLock = createHealthyLock();
            DistributedLock unhealthyLock = createUnhealthyLock();
            
            // When
            LockHealthMetrics.HealthStatus healthyStatus = monitoringService.checkLockHealth(healthyLock);
            LockHealthMetrics.HealthStatus unhealthyStatus = monitoringService.checkLockHealth(unhealthyLock);
            
            // Then
            assertThat(healthyStatus).isEqualTo(LockHealthMetrics.HealthStatus.HEALTHY);
            assertThat(unhealthyStatus).isEqualTo(LockHealthMetrics.HealthStatus.UNHEALTHY);
        }

        @Test
        @DisplayName("应该检测锁泄漏")
        void shouldDetectLockLeaks() throws InterruptedException {
            // Given
            String leakLockName = "leak-test-lock";
            AtomicLong leakDetectionTime = new AtomicLong();
            
            // When - 模拟锁泄漏
            DistributedLock leakedLock = mock(DistributedLock.class);
            when(leakedLock.tryLock(anyLong(), any())).thenReturn(true);
            when(leakedLock.isHeldByCurrentThread()).thenReturn(true); // 永远不释放
            
            // 启动监控服务
            monitoringService.start();
            
            // 模拟长时间不释放的锁
            Thread leakThread = new Thread(() -> {
                try {
                    performanceMetrics.recordLockAcquisitionStart(leakLockName);
                    leakedLock.tryLock(5, TimeUnit.SECONDS);
                    simulateWork(10000); // 长时间持有但不释放
                    leakDetectionTime.set(System.currentTimeMillis());
                } catch (Exception e) {
                    // 忽略异常
                }
            });
            
            leakThread.start();
            
            // Then
            Thread.sleep(2000); // 等待监控检测
            monitoringService.stop();
            leakThread.interrupt();
            
            assertThat(leakDetectionTime.get()).isGreaterThan(0);
        }

        @Test
        @DisplayName("应该监控锁数量和资源使用")
        void shouldMonitorLockCountAndResourceUsage() {
            // Given
            List<String> lockNames = createLockNamesList(10);
            
            // When
            performMultipleLockOperations(lockNames);
            
            // Then
            LockMonitoringService.ResourceMetrics resourceMetrics = monitoringService.getResourceMetrics();
            assertThat(resourceMetrics).isNotNull();
            assertThat(resourceMetrics.getActiveLockCount()).isGreaterThanOrEqualTo(0);
            assertThat(resourceMetrics.getTotalLockCount()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("告警系统测试")
    class AlertingSystemTest {

        private LockAlertingService alertingService;
        private List<String> alertEvents;

        @BeforeEach
        void setUp() {
            alertEvents = new ArrayList<>();
            alertingService = createAlertingService();
        }

        @Test
        @DisplayName("应该在性能超过阈值时触发告警")
        void shouldTriggerAlertWhenPerformanceExceedsThreshold() {
            // Given
            String lockName = "alert-test-lock";
            double threshold = 100.0; // 100ms
            
            // When - 生成超过阈值的性能数据
            for (int i = 0; i < 5; i++) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(150); // 超过阈值
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
            
            // Then
            List<LockAlertingService.Alert> alerts = alertingService.getActiveAlerts();
            assertThat(alerts).isNotEmpty();
            
            // 验证告警级别和内容
            boolean hasPerformanceAlert = alerts.stream()
                .anyMatch(alert -> alert.getType() == LockAlertingService.AlertType.PERFORMANCE);
            assertThat(hasPerformanceAlert).isTrue();
        }

        @Test
        @DisplayName("应该在锁获取失败率过高时触发告警")
        void shouldTriggerAlertForHighFailureRate() {
            // Given
            String failureLockName = "failure-test-lock";
            DistributedLock failingLock = createFailingLock();
            
            // When - 生成高失败率
            for (int i = 0; i < 20; i++) {
                boolean success = failingLock.tryLock(100, TimeUnit.MILLISECONDS);
                if (success) {
                    failingLock.unlock();
                }
                performanceMetrics.recordLockAcquisitionEnd(failureLockName, success);
            }
            
            // Then
            List<LockAlertingService.Alert> alerts = alertingService.getActiveAlerts();
            boolean hasFailureRateAlert = alerts.stream()
                .anyMatch(alert -> alert.getType() == LockAlertingService.AlertType.FAILURE_RATE);
            assertThat(hasFailureRateAlert).isTrue();
        }

        @Test
        @DisplayName("应该支持告警抑制机制")
        void shouldSupportAlertSuppression() {
            // Given
            String suppressLockName = "suppress-test-lock";
            
            // 第一次告警
            alertingService.triggerAlert(LockAlertingService.AlertType.PERFORMANCE, suppressLockName, "Test alert");
            
            // When - 尝试重复告警
            alertingService.triggerAlert(LockAlertingService.AlertType.PERFORMANCE, suppressLockName, "Duplicate alert");
            alertingService.triggerAlert(LockAlertingService.AlertType.PERFORMANCE, suppressLockName, "Another duplicate");
            
            // Then
            List<LockAlertingService.Alert> alerts = alertingService.getActiveAlerts();
            long performanceAlertCount = alerts.stream()
                .filter(alert -> alert.getType() == LockAlertingService.AlertType.PERFORMANCE)
                .count();
            
            // 由于抑制机制，应该只有一次告警
            assertThat(performanceAlertCount).isEqualTo(1);
        }

        @Test
        @DisplayName("应该支持告警恢复")
        void shouldSupportAlertRecovery() {
            // Given
            String recoveryLockName = "recovery-test-lock";
            LockAlertingService.Alert alert = alertingService.triggerAlert(
                LockAlertingService.AlertType.PERFORMANCE, 
                recoveryLockName, 
                "Initial alert"
            );
            
            // When - 模拟问题恢复
            simulateRecovery(recoveryLockName);
            
            // Then
            assertThat(alert.isResolved()).isTrue();
        }
    }

    @Nested
    @DisplayName("Prometheus指标导出测试")
    class PrometheusMetricsExportTest {

        @Test
        @DisplayName("应该生成Prometheus格式的指标")
        void shouldGeneratePrometheusFormattedMetrics() {
            // Given
            String lockName = "prometheus-test-lock";
            
            // When
            generateSampleMetrics(lockName);
            String prometheusMetrics = prometheusExporter.exportMetrics();
            
            // Then
            assertThat(prometheusMetrics).isNotEmpty();
            assertThat(prometheusMetrics).contains("distributed_lock_");
            assertThat(prometheusMetrics).contains("# HELP");
            assertThat(prometheusMetrics).contains("# TYPE");
        }

        @Test
        @DisplayName("应该支持指标标签")
        void shouldSupportMetricsLabels() {
            // Given
            Map<String, String> labels = Map.of(
                "instance", "test-instance",
                "environment", "test",
                "version", "1.0.0"
            );
            
            // When
            prometheusExporter.addLabels(labels);
            generateSampleMetrics("label-test-lock");
            String prometheusMetrics = prometheusExporter.exportMetrics();
            
            // Then
            assertThat(prometheusMetrics).contains("instance=\"test-instance\"");
            assertThat(prometheusMetrics).contains("environment=\"test\"");
            assertThat(prometheusMetrics).contains("version=\"1.0.0\"");
        }

        @Test
        @DisplayName("应该支持指标聚合")
        void shouldSupportMetricsAggregation() {
            // Given
            List<String> lockNames = createLockNamesList(5);
            
            // When
            performAggregationTest(lockNames);
            
            // Then
            String aggregatedMetrics = prometheusExporter.exportAggregatedMetrics();
            assertThat(aggregatedMetrics).isNotEmpty();
            
            // 验证聚合指标
            assertThat(aggregatedMetrics).contains("distributed_lock_total_operations");
            assertThat(aggregatedMetrics).contains("distributed_lock_avg_acquisition_time");
        }
    }

    @Nested
    @DisplayName("JMX监控测试")
    class JMXMonitoringTest {

        @Test
        @DisplayName("应该注册JMX MBean")
        void shouldRegisterJMXMBean() {
            // Given
            LockJMXManager jmxManager = createJMXManager();
            
            // When
            jmxManager.start();
            
            // Then
            assertThat(jmxManager.isRegistered()).isTrue();
            
            jmxManager.stop();
        }

        @Test
        @DisplayName("应该暴露性能指标")
        void shouldExposePerformanceMetrics() {
            // Given
            LockJMXManager jmxManager = createJMXManager();
            String lockName = "jmx-test-lock";
            
            // When
            generateSampleMetrics(lockName);
            jmxManager.registerLockMetrics(lockName, performanceMetrics);
            
            // Then
            Map<String, Object> jmxAttributes = jmxManager.getLockAttributes(lockName);
            assertThat(jmxAttributes).isNotEmpty();
            assertThat(jmxAttributes).containsKey("acquisition_count");
            assertThat(jmxAttributes).containsKey("average_acquisition_time");
        }
    }

    // 辅助方法和工厂方法

    private void simulateWork(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<DistributedLock> createContendedLocks() {
        List<DistributedLock> locks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            DistributedLock lock = mock(DistributedLock.class);
            when(lock.tryLock(anyLong(), any())).thenReturn(false, true); // 第一次失败，第二次成功
            locks.add(lock);
        }
        return locks;
    }

    private void performContendedOperations(List<DistributedLock> locks) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(locks.size() * 2);
        ExecutorService executor = Executors.newFixedThreadPool(locks.size() * 2);
        
        for (DistributedLock lock : locks) {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        performanceMetrics.recordLockContentionStart("contention-lock");
                        lock.tryLock(100, TimeUnit.MILLISECONDS);
                        performanceMetrics.recordLockContentionEnd("contention-lock");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }
        }
        
        startLatch.countDown();
        completionLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();
    }

    private DistributedLock createRetryableLock() {
        DistributedLock lock = mock(DistributedLock.class);
        AtomicInteger attempts = new AtomicInteger(0);
        
        when(lock.tryLock(anyLong(), any())).thenAnswer(invocation -> {
            int attempt = attempts.incrementAndGet();
            return attempt >= 2; // 第二次尝试成功
        });
        
        return lock;
    }

    private boolean acquireWithRetry(String lockName, int maxRetries, long retryInterval) throws InterruptedException {
        for (int i = 0; i < maxRetries; i++) {
            if (mockLock.tryLock(100, TimeUnit.MILLISECONDS)) {
                return true;
            }
            Thread.sleep(retryInterval);
        }
        return false;
    }

    private List<DistributedLock> createMixedSuccessLocks() {
        List<DistributedLock> locks = new ArrayList<>();
        
        // 70%成功率
        for (int i = 0; i < 7; i++) {
            DistributedLock lock = mock(DistributedLock.class);
            when(lock.tryLock(anyLong(), any())).thenReturn(true);
            locks.add(lock);
        }
        
        // 30%失败率
        for (int i = 0; i < 3; i++) {
            DistributedLock lock = mock(DistributedLock.class);
            when(lock.tryLock(anyLong(), any())).thenReturn(false);
            locks.add(lock);
        }
        
        return locks;
    }

    private void performMixedAcquisitionOperations(List<DistributedLock> locks) {
        for (DistributedLock lock : locks) {
            boolean success = lock.tryLock(100, TimeUnit.MILLISECONDS);
            if (success) {
                lock.unlock();
            }
            performanceMetrics.recordLockAcquisitionEnd("mixed-lock", success);
        }
    }

    private List<String> createLockNamesWithDifferentPatterns() {
        return Arrays.asList(
            "hot-lock-1", "hot-lock-2", // 热点锁
            "cold-lock-1", "cold-lock-2", // 冷门锁
            "normal-lock-1", "normal-lock-2", "normal-lock-3" // 正常锁
        );
    }

    private void simulateMixedWorkload(List<String> lockNames) {
        // 热点锁：高频访问
        for (int i = 0; i < 10; i++) {
            performanceMetrics.recordLockAcquisitionStart("hot-lock-1");
            simulateWork(10);
            performanceMetrics.recordLockAcquisitionEnd("hot-lock-1", true);
        }
        
        // 冷门锁：低频访问
        for (int i = 0; i < 2; i++) {
            performanceMetrics.recordLockAcquisitionStart("cold-lock-1");
            simulateWork(100);
            performanceMetrics.recordLockAcquisitionEnd("cold-lock-1", true);
        }
        
        // 正常锁：中等频率
        for (String lockName : lockNames) {
            if (!lockName.startsWith("hot-") && !lockName.startsWith("cold-")) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(50);
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
        }
    }

    private void generateSamplePerformanceData(List<String> lockNames) {
        for (String lockName : lockNames) {
            for (int i = 0; i < 3; i++) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(50 + i * 10);
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
        }
    }

    private void performMonitoringOperations() {
        for (int i = 0; i < 5; i++) {
            performanceMetrics.recordLockAcquisitionStart("monitor-lock-" + i);
            simulateWork(100);
            performanceMetrics.recordLockAcquisitionEnd("monitor-lock-" + i, true);
        }
    }

    private DistributedLock createHealthyLock() {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.healthCheck()).thenReturn(
            new com.mycorp.distributedlock.api.HealthCheck.HealthCheckResult(
                com.mycorp.distributedlock.api.HealthCheck.ComponentStatus.UP,
                "healthy-lock", "All systems operational"
            )
        );
        return lock;
    }

    private DistributedLock createUnhealthyLock() {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.healthCheck()).thenReturn(
            new com.mycorp.distributedlock.api.HealthCheck.HealthCheckResult(
                com.mycorp.distributedlock.api.HealthCheck.ComponentStatus.DOWN,
                "unhealthy-lock", "Connection failed"
            )
        );
        return lock;
    }

    private void performMultipleLockOperations(List<String> lockNames) {
        for (String lockName : lockNames) {
            performanceMetrics.recordLockAcquisitionStart(lockName);
            simulateWork(50);
            performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            performanceMetrics.recordLockHoldStart(lockName);
            simulateWork(100);
            performanceMetrics.recordLockHoldEnd(lockName);
        }
    }

    private LockAlertingService createAlertingService() {
        return new LockAlertingService(
            performanceMetrics,
            null,
            new LockAlertingService.AlertingConfig()
        );
    }

    private DistributedLock createFailingLock() {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(anyLong(), any())).thenReturn(false); // 总是失败
        return lock;
    }

    private void simulateRecovery(String lockName) {
        // 模拟性能恢复到正常水平
        for (int i = 0; i < 10; i++) {
            performanceMetrics.recordLockAcquisitionStart(lockName);
            simulateWork(30); // 恢复到30ms
            performanceMetrics.recordLockAcquisitionEnd(lockName, true);
        }
    }

    private void generateSampleMetrics(String lockName) {
        performanceMetrics.recordLockAcquisitionStart(lockName);
        simulateWork(100);
        performanceMetrics.recordLockAcquisitionEnd(lockName, true);
        
        performanceMetrics.recordLockHoldStart(lockName);
        simulateWork(200);
        performanceMetrics.recordLockHoldEnd(lockName);
    }

    private void performAggregationTest(List<String> lockNames) {
        for (String lockName : lockNames) {
            for (int i = 0; i < 3; i++) {
                performanceMetrics.recordLockAcquisitionStart(lockName);
                simulateWork(50 + i * 10);
                performanceMetrics.recordLockAcquisitionEnd(lockName, true);
            }
        }
    }

    private List<String> createLockNamesList(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add("lock-" + i);
        }
        return names;
    }

    private LockJMXManager createJMXManager() {
        return new LockJMXManager(
            performanceMetrics,
            null,
            new LockJMXManager.JMXConfig()
        );
    }

    private PrometheusMetricsExporter.PrometheusExporterConfig createPrometheusConfig() {
        PrometheusMetricsExporter.PrometheusExporterConfig config = 
            new PrometheusMetricsExporter.PrometheusExporterConfig();
        config.setPrometheusEnabled(true);
        config.setApplicationName("test-app");
        config.setInstanceId("test-instance");
        return config;
    }

    private LockMonitoringService createMonitoringService() {
        LockMonitoringService.MonitoringConfig config = new LockMonitoringService.MonitoringConfig();
        config.setMainMonitoringInterval(Duration.ofSeconds(1));
        config.setThreadPoolSize(2);
        
        return new LockMonitoringService(performanceMetrics, null, config);
    }
}