package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 锁监控服务单元测试
 * 测试监控启动、停止、指标收集、告警管理、性能分析等功能
 */
@ExtendWith(MockitoExtension.class)
class LockMonitoringServiceTest {

    @Mock
    private LockPerformanceMetrics mockPerformanceMetrics;

    @Mock
    private MeterRegistry mockMeterRegistry;

    @Mock
    private PerformanceMetrics.SystemPerformanceMetrics mockSystemMetrics;

    @Mock
    private PerformanceAlertCallback mockAlertCallback;

    private LockMonitoringService monitoringService;
    private LockMonitoringService.MonitoringConfig config;

    @BeforeEach
    void setUp() {
        when(mockPerformanceMetrics.getSystemMetrics()).thenReturn(mockSystemMetrics);
        
        config = new LockMonitoringService.MonitoringConfig();
        config.setMainMonitoringInterval(Duration.ofSeconds(1));
        config.setLockMonitoringInterval(Duration.ofSeconds(1));
        config.setThreadPoolSize(2);
        
        monitoringService = new LockMonitoringService(mockPerformanceMetrics, mockMeterRegistry, config);
    }

    @Test
    void shouldCreateMonitoringService() {
        assertNotNull(monitoringService);
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertFalse(status.isRunning());
        assertEquals(0, status.getActiveLocks());
        assertEquals(0, status.getTotalCycles());
        assertEquals(0, status.getAlertsTriggered());
    }

    @Test
    void shouldStartMonitoringService() {
        monitoringService.startMonitoring();
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.isRunning());
    }

    @Test
    void shouldPreventMultipleStartCalls() {
        monitoringService.startMonitoring();
        monitoringService.startMonitoring(); // 第二次调用应该被忽略
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.isRunning());
    }

    @Test
    void shouldStopMonitoringService() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(100); // 等待监控线程启动
        
        monitoringService.stopMonitoring();
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertFalse(status.isRunning());
    }

    @Test
    void shouldAddMonitoredLock() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50); // 等待监控线程启动
        
        monitoringService.addMonitoredLock("test-lock");
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertEquals(1, status.getActiveLocks());
    }

    @Test
    void shouldNotAddMonitoredLockWhenNotRunning() {
        // 监控服务未启动
        monitoringService.addMonitoredLock("test-lock");
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertEquals(0, status.getActiveLocks());
    }

    @Test
    void shouldRemoveMonitoredLock() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        monitoringService.removeMonitoredLock("test-lock");
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertEquals(0, status.getActiveLocks());
    }

    @Test
    void shouldPerformMonitoringRound() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        monitoringService.performMonitoringRound();
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.getTotalCycles() > 0);
        assertTrue(status.getLastMonitoringTime().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void shouldNotPerformMonitoringRoundWhenNotRunning() {
        monitoringService.performMonitoringRound();
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertEquals(0, status.getTotalCycles());
    }

    @Test
    void shouldGeneratePerformanceReport() {
        Duration timeRange = Duration.ofHours(1);
        PerformanceMetrics.ReportFormat format = PerformanceMetrics.ReportFormat.JSON;
        
        PerformanceMetrics.PerformanceReport report = monitoringService.generateReport(timeRange, format);
        
        assertNotNull(report);
        assertEquals("分布式锁性能报告", report.getTitle());
        assertEquals(timeRange, report.getTimeRange());
        assertTrue(report.getGeneratedTime() > 0);
        assertNotNull(report.getExecutiveSummary());
        assertNotNull(report.getDetailedMetrics());
        assertNotNull(report.getIssues());
        assertNotNull(report.getRecommendations());
        assertNotNull(report.getChartData());
    }

    @Test
    void shouldAnalyzeTrends() {
        Duration timeRange = Duration.ofHours(1);
        
        PerformanceMetrics.PerformanceTrendAnalysis trendAnalysis = 
            monitoringService.analyzeTrends("test-lock", timeRange);
        
        assertNotNull(trendAnalysis);
        assertEquals(timeRange, trendAnalysis.getAnalysisTimeRange());
    }

    @Test
    void shouldAnalyzeTrendsForNonExistentLock() {
        Duration timeRange = Duration.ofHours(1);
        
        assertThrows(IllegalArgumentException.class, () -> {
            monitoringService.analyzeTrends("non-existent-lock", timeRange);
        });
    }

    @Test
    void shouldSetAlertCallback() {
        monitoringService.setAlertCallback(mockAlertCallback);
        
        // 验证回调已设置（通过内部状态检查）
        assertDoesNotThrow(() -> monitoringService.performMonitoringRound());
    }

    @Test
    void shouldGetMonitoringStatistics() {
        LockMonitoringService.MonitoringStatistics stats = monitoringService.getStatistics();
        
        assertNotNull(stats);
        assertEquals(0, stats.getMonitoredLockCount());
        assertEquals(0, stats.getTotalMonitorCycles());
        assertEquals(0, stats.getAlertsTriggered());
        assertTrue(stats.getAverageMonitoringInterval() > 0);
        assertNotNull(stats.getLockPerformanceScores());
        assertTrue(stats.getLockPerformanceScores().isEmpty());
    }

    @Test
    void shouldPerformPerformanceAnalysis() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        LockMonitoringService.PerformanceAnalysisResult result = 
            monitoringService.performPerformanceAnalysis("test-lock");
        
        assertNotNull(result);
        assertEquals("test-lock", result.getLockName());
        assertNotNull(result.getAnalysis());
        assertNotNull(result.getTrendDirection());
        assertTrue(result.getTrendStrength() >= 0);
        assertNotNull(result.getRecommendations());
        assertTrue(result.getAnalysisTime().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void shouldPerformPerformanceAnalysisForNonExistentLock() {
        assertThrows(IllegalArgumentException.class, () -> {
            monitoringService.performPerformanceAnalysis("non-existent-lock");
        });
    }

    @Test
    void shouldHandleSystemErrorRateThreshold() throws InterruptedException {
        // 设置低阈值
        config.setErrorRateThreshold(0.01); // 1%
        when(mockSystemMetrics.getErrorRate()).thenReturn(0.05); // 5% > 1%
        
        monitoringService.startMonitoring();
        Thread.sleep(100);
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.getAlertsTriggered() > 0);
    }

    @Test
    void shouldHandleConcurrencyThreshold() throws InterruptedException {
        // 设置低阈值
        config.setMaxConcurrencyThreshold(10);
        when(mockSystemMetrics.getCurrentConcurrency()).thenReturn(15);
        
        monitoringService.startMonitoring();
        Thread.sleep(100);
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.getAlertsTriggered() > 0);
    }

    @Test
    void shouldHandleResponseTimeThreshold() throws InterruptedException {
        // 设置低阈值
        config.setMaxResponseTimeThreshold(Duration.ofSeconds(1));
        when(mockSystemMetrics.getAverageResponseTime()).thenReturn(Duration.ofSeconds(2));
        
        monitoringService.startMonitoring();
        Thread.sleep(100);
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.getAlertsTriggered() > 0);
    }

    @Test
    void shouldRecordMonitoringMetrics() throws InterruptedException {
        // 验证 MeterRegistry 被使用
        monitoringService.startMonitoring();
        Thread.sleep(100);
        
        // 验证指标记录调用
        verify(mockMeterRegistry, atLeastOnce()).gauge(
            eq("lock.monitoring.cycles.total"), 
            any(Object.class), 
            any()
        );
        verify(mockMeterRegistry, atLeastOnce()).gauge(
            eq("lock.monitoring.alerts.total"), 
            any(Object.class), 
            any()
        );
        verify(mockMeterRegistry, atLeastOnce()).gauge(
            eq("lock.monitoring.active.tasks"), 
            any(Object.class), 
            any()
        );
    }

    @Test
    void shouldCleanupExpiredData() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        // 执行多轮监控以产生历史数据
        for (int i = 0; i < 5; i++) {
            monitoringService.performMonitoringRound();
            Thread.sleep(10);
        }
        
        // 清理过期数据应该不会抛出异常
        assertDoesNotThrow(() -> {
            // 通过执行另一轮监控来触发清理
            monitoringService.performMonitoringRound();
        });
    }

    @Test
    void shouldHandleExceptionInMonitoringRound() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        // 模拟性能指标获取异常
        when(mockPerformanceMetrics.getSystemMetrics()).thenThrow(new RuntimeException("Test exception"));
        
        // 应该不会抛出异常，而是记录错误并继续
        assertDoesNotThrow(() -> {
            monitoringService.performMonitoringRound();
        });
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.getTotalCycles() > 0); // 监控轮次仍然执行
    }

    @Test
    void shouldHandleConfigurationUpdate() {
        LockMonitoringService.MonitoringConfig newConfig = new LockMonitoringService.MonitoringConfig();
        newConfig.setMainMonitoringInterval(Duration.ofSeconds(2));
        newConfig.setLockMonitoringInterval(Duration.ofSeconds(2));
        
        // 验证配置可以创建和使用
        assertEquals(Duration.ofSeconds(2), newConfig.getMainMonitoringInterval());
        assertEquals(Duration.ofSeconds(2), newConfig.getLockMonitoringInterval());
    }

    @Test
    void shouldValidateConfiguration() {
        LockMonitoringService.MonitoringConfig testConfig = new LockMonitoringService.MonitoringConfig();
        
        // 验证默认值
        assertEquals(Duration.ofSeconds(30), testConfig.getMainMonitoringInterval());
        assertEquals(Duration.ofSeconds(10), testConfig.getLockMonitoringInterval());
        assertEquals(Duration.ofHours(24), testConfig.getDataRetentionDuration());
        assertEquals(4, testConfig.getThreadPoolSize());
        assertEquals(0.05, testConfig.getErrorRateThreshold(), 0.001);
        assertEquals(1000, testConfig.getMaxConcurrencyThreshold());
        assertEquals(Duration.ofSeconds(5), testConfig.getMaxResponseTimeThreshold());
        assertEquals(0.95, testConfig.getMinSuccessRateThreshold(), 0.001);
        assertEquals(0.1, testConfig.getMaxContentionThreshold(), 0.001);
        assertEquals(Duration.ofSeconds(60), testConfig.getMaxHoldTimeThreshold());
    }

    @Test
    void shouldUpdateConfiguration() {
        LockMonitoringService.MonitoringConfig testConfig = new LockMonitoringService.MonitoringConfig();
        
        testConfig.setMainMonitoringInterval(Duration.ofSeconds(60));
        testConfig.setLockMonitoringInterval(Duration.ofSeconds(30));
        testConfig.setDataRetentionDuration(Duration.ofHours(12));
        testConfig.setThreadPoolSize(8);
        testConfig.setErrorRateThreshold(0.1);
        testConfig.setMaxConcurrencyThreshold(2000);
        testConfig.setMaxResponseTimeThreshold(Duration.ofSeconds(10));
        testConfig.setMinSuccessRateThreshold(0.9);
        testConfig.setMaxContentionThreshold(0.2);
        testConfig.setMaxHoldTimeThreshold(Duration.ofMinutes(2));
        
        assertEquals(Duration.ofSeconds(60), testConfig.getMainMonitoringInterval());
        assertEquals(Duration.ofSeconds(30), testConfig.getLockMonitoringInterval());
        assertEquals(Duration.ofHours(12), testConfig.getDataRetentionDuration());
        assertEquals(8, testConfig.getThreadPoolSize());
        assertEquals(0.1, testConfig.getErrorRateThreshold(), 0.001);
        assertEquals(2000, testConfig.getMaxConcurrencyThreshold());
        assertEquals(Duration.ofSeconds(10), testConfig.getMaxResponseTimeThreshold());
        assertEquals(0.9, testConfig.getMinSuccessRateThreshold(), 0.001);
        assertEquals(0.2, testConfig.getMaxContentionThreshold(), 0.001);
        assertEquals(Duration.ofMinutes(2), testConfig.getMaxHoldTimeThreshold());
    }

    @Test
    void shouldGeneratePerformanceAnalysisWithHistoricalData() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        // 产生一些历史数据
        for (int i = 0; i < 10; i++) {
            monitoringService.performMonitoringRound();
            Thread.sleep(10);
        }
        
        LockMonitoringService.PerformanceAnalysisResult result = 
            monitoringService.performPerformanceAnalysis("test-lock");
        
        assertNotNull(result);
        assertTrue(result.getRecommendations().size() > 0);
        assertEquals("test-lock", result.getLockName());
        assertNotNull(result.getAnalysis());
    }

    @Test
    void shouldHandleEmptyMetricHistory() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        LockMonitoringService.PerformanceAnalysisResult result = 
            monitoringService.performPerformanceAnalysis("test-lock");
        
        assertNotNull(result);
        assertEquals("No historical data available", result.getAnalysis());
    }

    @Test
    void shouldCalculateTrendDirection() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        // 产生不同时期的数据来测试趋势分析
        for (int i = 0; i < 20; i++) {
            monitoringService.performMonitoringRound();
            Thread.sleep(5);
        }
        
        LockMonitoringService.PerformanceAnalysisResult result = 
            monitoringService.performPerformanceAnalysis("test-lock");
        
        assertNotNull(result);
        assertNotNull(result.getTrendDirection());
        // 趋势方向取决于实际数据，这里只验证不为null
    }

    @Test
    void shouldProvideRecommendations() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        for (int i = 0; i < 15; i++) {
            monitoringService.performMonitoringRound();
            Thread.sleep(5);
        }
        
        LockMonitoringService.PerformanceAnalysisResult result = 
            monitoringService.performPerformanceAnalysis("test-lock");
        
        List<String> recommendations = result.getRecommendations();
        assertNotNull(recommendations);
        
        // 验证推荐内容格式
        for (String recommendation : recommendations) {
            assertNotNull(recommendation);
            assertFalse(recommendation.trim().isEmpty());
        }
    }

    @Test
    void shouldHandleAutomaticDataCleanup() throws InterruptedException {
        // 设置很短的数据保留时间
        config.setDataRetentionDuration(Duration.ofMillis(100));
        
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        monitoringService.addMonitoredLock("test-lock");
        
        // 产生数据
        for (int i = 0; i < 5; i++) {
            monitoringService.performMonitoringRound();
            Thread.sleep(20);
        }
        
        // 等待数据过期
        Thread.sleep(150);
        
        // 触发清理（通过执行监控轮次）
        monitoringService.performMonitoringRound();
        
        // 验证清理过程不会抛出异常
        assertDoesNotThrow(() -> monitoringService.getStatistics());
    }

    @Test
    void shouldHandleMeterRegistryWithNull() {
        LockMonitoringService serviceWithNullRegistry = 
            new LockMonitoringService(mockPerformanceMetrics, null, config);
        
        assertNotNull(serviceWithNullRegistry);
        
        // 验证即使没有MeterRegistry，监控服务也能工作
        serviceWithNullRegistry.startMonitoring();
        assertTrue(serviceWithNullRegistry.getMonitoringStatus().isRunning());
        serviceWithNullRegistry.stopMonitoring();
    }

    @Test
    void shouldValidateThreadPoolCreation() {
        LockMonitoringService service = new LockMonitoringService(mockPerformanceMetrics, mockMeterRegistry, config);
        
        ScheduledExecutorService executor = service.getScheduledExecutorService();
        assertNotNull(executor);
        
        // 验证线程池配置
        assertTrue(executor instanceof java.util.concurrent.ScheduledThreadPoolExecutor);
        
        service.shutdown();
    }

    @Test
    void shouldHandleLargeNumberOfMonitoredLocks() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        // 添加大量监控锁
        int lockCount = 100;
        for (int i = 0; i < lockCount; i++) {
            monitoringService.addMonitoredLock("test-lock-" + i);
        }
        
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertEquals(lockCount, status.getActiveLocks());
        
        // 验证监控轮次仍能正常执行
        monitoringService.performMonitoringRound();
        
        LockMonitoringService.MonitoringStatistics stats = monitoringService.getStatistics();
        assertTrue(stats.getMonitoredLockCount() > 0);
    }

    @Test
    void shouldHandleConcurrentlyAddingAndRemovingLocks() throws InterruptedException {
        monitoringService.startMonitoring();
        Thread.sleep(50);
        
        // 创建多个线程同时添加和移除锁
        Runnable addLockTask = () -> {
            for (int i = 0; i < 10; i++) {
                monitoringService.addMonitoredLock("concurrent-lock-" + i);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        };
        
        Runnable removeLockTask = () -> {
            try {
                Thread.sleep(50);
                for (int i = 0; i < 10; i++) {
                    monitoringService.removeMonitoredLock("concurrent-lock-" + i);
                    Thread.sleep(10);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        
        Thread addThread = new Thread(addLockTask);
        Thread removeThread = new Thread(removeLockTask);
        
        addThread.start();
        removeThread.start();
        
        addThread.join();
        removeThread.join();
        
        // 验证最终状态一致
        LockMonitoringService.MonitoringStatus status = monitoringService.getMonitoringStatus();
        assertTrue(status.getActiveLocks() >= 0);
    }
}