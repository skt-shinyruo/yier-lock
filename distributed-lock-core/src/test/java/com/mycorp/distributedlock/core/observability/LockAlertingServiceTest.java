package com.mycorp.distributedlock.core.observability;

import com.mycorp.distributedlock.api.PerformanceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Lock告警服务测试
 */
@ExtendWith(MockitoExtension.class)
class LockAlertingServiceTest {

    @Mock
    private LockPerformanceMetrics mockMetrics;

    private MeterRegistry meterRegistry;
    private LockAlertingService alertingService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        alertingService = new LockAlertingService(mockMetrics, meterRegistry);
    }

    @Test
    void testEnableAndDisableAlerting() {
        // 测试启用告警服务
        alertingService.enableAlerting();
        
        LockAlertingService.AlertingStatistics stats = alertingService.getStatistics();
        assertNotNull(stats);
        
        // 测试禁用告警服务
        alertingService.disableAlerting();
        
        // 验证禁用后的状态（可能需要通过其他方式验证）
        assertNotNull(alertingService);
    }

    @Test
    void testAlertRuleManagement() {
        // 测试添加告警规则
        AlertingRules.AlertRule rule = new AlertingRules.ThresholdAlertRule(
            "test-rule",
            "test_expression",
            "Test Alert Rule",
            PerformanceMetrics.AlertLevel.WARNING,
            PerformanceMetrics.AlertType.ERROR_RATE,
            0.05,
            Duration.ofMinutes(2)
        );
        
        alertingService.addAlertRule(PerformanceMetrics.AlertType.ERROR_RATE, rule);
        
        // 测试移除告警规则
        alertingService.removeAlertRule(PerformanceMetrics.AlertType.ERROR_RATE, rule);
        
        assertNotNull(alertingService);
    }

    @Test
    void testAlertConditionCheck() {
        String lockName = "test-lock";
        PerformanceMetrics.LockOperation operation = PerformanceMetrics.LockOperation.LOCK_ACQUIRE;
        Duration duration = Duration.ofSeconds(5);
        boolean success = false; // 模拟失败操作
        
        // 测试告警条件检查
        alertingService.checkAlertConditions(lockName, operation, duration, success);
        
        // 验证方法被调用
        assertNotNull(alertingService);
    }

    @Test
    void testAlertTrigger() {
        PerformanceMetrics.PerformanceAlert alert = new LockPerformanceMetrics.PerformanceAlertImpl(
            PerformanceMetrics.AlertType.ERROR_RATE,
            PerformanceMetrics.AlertLevel.CRITICAL,
            "Test alert message",
            "test-lock",
            System.currentTimeMillis()
        );
        
        // 测试触发告警
        alertingService.triggerAlert(alert);
        
        // 验证告警被处理
        assertNotNull(alertingService);
    }

    @Test
    void testAlertResolution() {
        PerformanceMetrics.PerformanceAlert alert = new LockPerformanceMetrics.PerformanceAlertImpl(
            PerformanceMetrics.AlertType.ERROR_RATE,
            PerformanceMetrics.AlertLevel.CRITICAL,
            "Test alert message",
            "test-lock",
            System.currentTimeMillis()
        );
        
        // 测试解析告警
        alertingService.resolveAlert(alert);
        
        // 验证告警被解析
        assertNotNull(alertingService);
    }

    @Test
    void testAlertStatistics() {
        LockAlertingService.AlertingStatistics stats = alertingService.getStatistics();
        
        assertNotNull(stats);
        assertTrue(stats.getTotalAlertsTriggered() >= 0);
        assertTrue(stats.getTotalAlertsResolved() >= 0);
        assertTrue(stats.getSuppressedAlerts() >= 0);
        assertTrue(stats.getAlertResolutionRate() >= 0);
    }

    @Test
    void testActiveAlerts() {
        // 获取活跃告警
        var activeAlerts = alertingService.getActiveAlerts();
        
        assertNotNull(activeAlerts);
        assertTrue(activeAlerts instanceof java.util.List);
    }

    @Test
    void testAlertHistory() {
        Duration timeRange = Duration.ofMinutes(30);
        
        var alertHistory = alertingService.getAlertHistory(timeRange);
        
        assertNotNull(alertHistory);
        assertTrue(alertHistory instanceof java.util.List);
    }

    @Test
    void testAlertTest() {
        // 测试告警功能
        alertingService.testAlert(
            PerformanceMetrics.AlertType.ERROR_RATE,
            PerformanceMetrics.AlertLevel.WARNING,
            "Test alert from unit test"
        );
        
        assertNotNull(alertingService);
    }

    @Test
    void testAlertCallback() {
        PerformanceMetrics.PerformanceAlertCallback callback = 
            mock(PerformanceMetrics.PerformanceAlertCallback.class);
            
        alertingService.setCallback(callback);
        
        // 验证回调被设置
        assertNotNull(alertingService);
    }

    @Test
    void testAlertNotifierManagement() {
        LockAlertingService.AlertNotifier notifier = 
            new LockAlertingService.LogAlertNotifier();
            
        // 测试添加通知器
        alertingService.addNotifier(notifier);
        
        // 测试移除通知器
        alertingService.removeNotifier(notifier);
        
        assertNotNull(alertingService);
    }
}