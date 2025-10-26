package com.mycorp.distributedlock.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * 性能指标接口
 * 提供分布式锁系统的全面性能监控和分析能力
 */
public interface PerformanceMetrics {

    /**
     * 记录锁操作性能指标
     * 
     * @param operation 锁操作类型
     * @param lockName 锁名称
     * @param duration 操作耗时
     * @param success 是否成功
     * @param metadata 额外元数据
     */
    void recordLockOperation(LockOperation operation, String lockName, Duration duration, 
                           boolean success, Map<String, Object> metadata);

    /**
     * 批量记录锁操作指标
     * 
     * @param metrics 指标列表
     */
    void recordLockOperations(List<LockMetric> metrics);

    /**
     * 获取系统级性能指标
     * 
     * @return 系统性能指标
     */
    SystemPerformanceMetrics getSystemMetrics();

    /**
     * 获取指定锁的性能指标
     * 
     * @param lockName 锁名称
     * @return 锁性能指标
     */
    LockPerformanceMetrics getLockMetrics(String lockName);

    /**
     * 获取多个锁的性能指标
     * 
     * @param lockNames 锁名称列表
     * @return 锁性能指标映射
     */
    Map<String, LockPerformanceMetrics> getMultipleLockMetrics(List<String> lockNames);

    /**
     * 获取性能统计数据
     * 
     * @param timeRange 时间范围
     * @return 性能统计数据
     */
    PerformanceStatistics getStatistics(Duration timeRange);

    /**
     * 获取实时性能快照
     * 
     * @return 实时性能快照
     */
    RealTimePerformanceSnapshot getRealTimeSnapshot();

    /**
     * 异步获取性能指标
     * 
     * @return 异步系统性能指标
     */
    CompletableFuture<SystemPerformanceMetrics> getSystemMetricsAsync();

    /**
     * 异步获取锁性能指标
     * 
     * @param lockName 锁名称
     * @return 异步锁性能指标
     */
    CompletableFuture<LockPerformanceMetrics> getLockMetricsAsync(String lockName);

    /**
     * 启动性能监控
     * 
     * @param interval 监控间隔
     * @return 监控任务句柄
     */
    ScheduledFuture<?> startPerformanceMonitoring(Duration interval);

    /**
     * 停止性能监控
     * 
     * @param monitoringTask 监控任务
     * @return 是否成功停止
     */
    boolean stopPerformanceMonitoring(ScheduledFuture<?> monitoringTask);

    /**
     * 设置性能告警回调
     * 
     * @param callback 告警回调
     */
    void setPerformanceAlertCallback(PerformanceAlertCallback callback);

    /**
     * 获取性能趋势分析
     * 
     * @param lockName 锁名称
     * @param timeRange 时间范围
     * @return 趋势分析结果
     */
    PerformanceTrendAnalysis getTrendAnalysis(String lockName, Duration timeRange);

    /**
     * 生成性能报告
     * 
     * @param timeRange 时间范围
     * @param format 报告格式
     * @return 性能报告
     */
    PerformanceReport generateReport(Duration timeRange, ReportFormat format);

    /**
     * 导出性能数据
     * 
     * @param lockNames 锁名称列表
     * @param timeRange 时间范围
     * @param format 导出格式
     * @return 导出的数据
     */
    String exportData(List<String> lockNames, Duration timeRange, ExportFormat format);

    /**
     * 重置性能指标
     */
    void resetMetrics();

    /**
     * 获取性能配置
     * 
     * @return 性能配置
     */
    PerformanceConfiguration getConfiguration();

    /**
     * 更新性能配置
     * 
     * @param configuration 新配置
     */
    void updateConfiguration(PerformanceConfiguration configuration);

    /**
     * 锁操作类型
     */
    enum LockOperation {
        /** 获取锁 */
        LOCK_ACQUIRE,
        /** 释放锁 */
        LOCK_RELEASE,
        /** 续期锁 */
        LOCK_RENEW,
        /** 尝试获取锁 */
        TRY_LOCK_ACQUIRE,
        /** 锁升级 */
        LOCK_UPGRADE,
        /** 锁降级 */
        LOCK_DOWNGRADE,
        /** 批量获取锁 */
        BATCH_LOCK_ACQUIRE,
        /** 异步获取锁 */
        ASYNC_LOCK_ACQUIRE
    }

    /**
     * 锁操作指标
     */
    interface LockMetric {
        /**
         * 获取操作类型
         */
        LockOperation getOperation();

        /**
         * 获取锁名称
         */
        String getLockName();

        /**
         * 获取操作耗时
         */
        Duration getDuration();

        /**
         * 是否成功
         */
        boolean isSuccess();

        /**
         * 获取时间戳
         */
        long getTimestamp();

        /**
         * 获取元数据
         */
        Map<String, Object> getMetadata();
    }

    /**
     * 系统性能指标
     */
    interface SystemPerformanceMetrics {
        /**
         * 获取总操作数
         */
        long getTotalOperations();

        /**
         * 获取成功操作数
         */
        long getSuccessfulOperations();

        /**
         * 获取失败操作数
         */
        long getFailedOperations();

        /**
         * 获取当前并发数
         */
        int getCurrentConcurrency();

        /**
         * 获取峰值并发数
         */
        int getPeakConcurrency();

        /**
         * 获取平均响应时间
         */
        Duration getAverageResponseTime();

        /**
         * 获取95分位响应时间
         */
        Duration getP95ResponseTime();

        /**
         * 获取99分位响应时间
         */
        Duration getP99ResponseTime();

        /**
         * 获取吞吐量（操作/秒）
         */
        double getThroughput();

        /**
         * 获取错误率
         */
        double getErrorRate();

        /**
         * 获取资源使用情况
         */
        ResourceUsage getResourceUsage();

        /**
         * 获取活跃锁数量
         */
        int getActiveLockCount();

        /**
         * 获取更新时间
         */
        long getUpdateTime();
    }

    /**
     * 锁性能指标
     */
    interface LockPerformanceMetrics {
        /**
         * 获取锁名称
         */
        String getLockName();

        /**
         * 获取操作统计
         */
        Map<LockOperation, OperationStatistics> getOperationStatistics();

        /**
         * 获取平均持有时间
         */
        Duration getAverageHoldTime();

        /**
         * 获取锁竞争度
         */
        double getContentionLevel();

        /**
         * 获取获取成功率
         */
        double getAcquisitionSuccessRate();

        /**
         * 获取队列等待统计
         */
        QueueWaitStatistics getQueueWaitStatistics();

        /**
         * 获取热点分析
         */
        HotspotAnalysis getHotspotAnalysis();

        /**
         * 获取最后更新时间
         */
        long getLastUpdateTime();
    }

    /**
     * 操作统计
     */
    interface OperationStatistics {
        /**
         * 获取操作总数
         */
        long getTotalCount();

        /**
         * 获取成功数量
         */
        long getSuccessCount();

        /**
         * 获取失败数量
         */
        long getFailureCount();

        /**
         * 获取平均操作时间
         */
        Duration getAverageTime();

        /**
         * 获取最小操作时间
         */
        Duration getMinTime();

        /**
         * 获取最大操作时间
         */
        Duration getMaxTime();

        /**
         * 获取95分位操作时间
         */
        Duration getP95Time();

        /**
         * 获取99分位操作时间
         */
        Duration getP99Time();
    }

    /**
     * 性能统计数据
     */
    interface PerformanceStatistics {
        /**
         * 获取时间范围
         */
        Duration getTimeRange();

        /**
         * 获取操作分布统计
         */
        Map<LockOperation, DistributionStatistics> getOperationDistribution();

        /**
         * 获取错误分析
         */
        ErrorAnalysis getErrorAnalysis();

        /**
         * 获取性能趋势
         */
        List<PerformanceTrendPoint> getPerformanceTrends();

        /**
         * 获取TOP性能问题
         */
        List<PerformanceIssue> getTopPerformanceIssues();
    }

    /**
     * 实时性能快照
     */
    interface RealTimePerformanceSnapshot {
        /**
         * 获取当前时间
         */
        long getCurrentTime();

        /**
         * 获取每秒操作数
         */
        double getOperationsPerSecond();

        /**
         * 获取当前平均响应时间
         */
        Duration getCurrentAverageResponseTime();

        /**
         * 获取当前并发数
         */
        int getCurrentConcurrency();

        /**
         * 获取当前错误率
         */
        double getCurrentErrorRate();

        /**
         * 获取系统负载
         */
        double getSystemLoad();

        /**
         * 获取实时指标
         */
        Map<String, Double> getRealtimeMetrics();
    }

    /**
     * 性能趋势分析
     */
    interface PerformanceTrendAnalysis {
        /**
         * 获取分析时间范围
         */
        Duration getAnalysisTimeRange();

        /**
         * 获取趋势方向
         */
        TrendDirection getTrendDirection();

        /**
         * 获取趋势强度
         */
        double getTrendStrength();

        /**
         * 获取预测数据
         */
        List<PerformancePrediction> getPredictions();

        /**
         * 获取异常点
         */
        List<AnomalyPoint> getAnomalies();

        /**
         * 获取建议
         */
        List<String> getRecommendations();
    }

    /**
     * 性能报告
     */
    interface PerformanceReport {
        /**
         * 获取报告标题
         */
        String getTitle();

        /**
         * 获取报告时间范围
         */
        Duration getTimeRange();

        /**
         * 获取报告生成时间
         */
        long getGeneratedTime();

        /**
         * 获取执行摘要
         */
        String getExecutiveSummary();

        /**
         * 获取详细指标
         */
        SystemPerformanceMetrics getDetailedMetrics();

        /**
         * 获取问题分析
         */
        List<PerformanceIssue> getIssues();

        /**
         * 获取优化建议
         */
        List<String> getRecommendations();

        /**
         * 获取图表数据
         */
        Map<String, Object> getChartData();
    }

    /**
     * 分布统计
     */
    interface DistributionStatistics {
        /**
         * 获取总数
         */
        long getTotal();

        /**
         * 获取平均值
         */
        double getMean();

        /**
         * 获取中位数
         */
        double getMedian();

        /**
         * 获取标准差
         */
        double getStandardDeviation();

        /**
         * 获取百分位数据
         */
        Map<String, Double> getPercentiles();
    }

    /**
     * 错误分析
     */
    interface ErrorAnalysis {
        /**
         * 获取错误总数
         */
        long getTotalErrors();

        /**
         * 获取错误分布
         */
        Map<String, Long> getErrorDistribution();

        /**
         * 获取错误率趋势
         */
        List<ErrorTrendPoint> getErrorTrends();

        /**
         * 获取主要错误原因
         */
        List<String> getTopErrorReasons();
    }

    /**
     * 队列等待统计
     */
    interface QueueWaitStatistics {
        /**
         * 获取平均等待时间
         */
        Duration getAverageWaitTime();

        /**
         * 获取最大等待时间
         */
        Duration getMaxWaitTime();

        /**
         * 获取当前队列长度
         */
        int getCurrentQueueLength();

        /**
         * 获取队列溢出次数
         */
        long getQueueOverflowCount();
    }

    /**
     * 热点分析
     */
    interface HotspotAnalysis {
        /**
         * 是否为热点锁
         */
        boolean isHotspot();

        /**
         * 获取热点分数
         */
        double getHotspotScore();

        /**
         * 获取访问频率
         */
        double getAccessFrequency();

        /**
         * 获取竞争激烈程度
         */
        double getContentionIntensity();
    }

    /**
     * 资源使用情况
     */
    interface ResourceUsage {
        /**
         * 获取CPU使用率
         */
        double getCpuUsage();

        /**
         * 获取内存使用率
         */
        double getMemoryUsage();

        /**
         * 获取网络使用率
         */
        double getNetworkUsage();

        /**
         * 获取磁盘使用率
         */
        double getDiskUsage();
    }

    // 简化的辅助类

    /**
     * 性能趋势点
     */
    interface PerformanceTrendPoint {
        /**
         * 获取时间戳
         */
        long getTimestamp();

        /**
         * 获取指标值
         */
        double getValue();
    }

    /**
     * 性能问题
     */
    interface PerformanceIssue {
        /**
         * 获取问题描述
         */
        String getDescription();

        /**
         * 获取严重程度
         */
        IssueSeverity getSeverity();

        /**
         * 获取影响范围
         */
        String getImpact();
    }

    /**
     * 性能预测
     */
    interface PerformancePrediction {
        /**
         * 获取预测时间
         */
        long getPredictedTime();

        /**
         * 获取预测值
         */
        double getPredictedValue();

        /**
         * 获取置信度
         */
        double getConfidence();
    }

    /**
     * 异常点
     */
    interface AnomalyPoint {
        /**
         * 获取时间戳
         */
        long getTimestamp();

        /**
         * 获取异常值
         */
        double getAnomalousValue();

        /**
         * 获取异常类型
         */
        String getAnomalyType();
    }

    /**
     * 错误趋势点
     */
    interface ErrorTrendPoint {
        /**
         * 获取时间戳
         */
        long getTimestamp();

        /**
         * 获取错误数
         */
        long getErrorCount();

        /**
         * 获取错误率
         */
        double getErrorRate();
    }

    /**
     * 性能告警回调
     */
    @FunctionalInterface
    interface PerformanceAlertCallback {
        /**
         * 性能告警触发
         * 
         * @param alert 告警信息
         */
        void onPerformanceAlert(PerformanceAlert alert);
    }

    /**
     * 性能告警
     */
    interface PerformanceAlert {
        /**
         * 获取告警类型
         */
        AlertType getAlertType();

        /**
         * 获取告警级别
         */
        AlertLevel getAlertLevel();

        /**
         * 获取告警消息
         */
        String getMessage();

        /**
         * 获取关联锁名称
         */
        String getLockName();

        /**
         * 获取触发时间
         */
        long getTriggerTime();
    }

    /**
     * 性能配置
     */
    interface PerformanceConfiguration {
        /**
         * 获取采样率
         */
        double getSamplingRate();

        /**
         * 获取保留时间
         */
        Duration getRetentionTime();

        /**
         * 获取聚合间隔
         */
        Duration getAggregationInterval();

        /**
         * 是否启用实时监控
         */
        boolean isRealTimeMonitoringEnabled();

        /**
         * 设置采样率
         */
        PerformanceConfiguration setSamplingRate(double rate);

        /**
         * 设置保留时间
         */
        PerformanceConfiguration setRetentionTime(Duration retention);

        /**
         * 设置聚合间隔
         */
        PerformanceConfiguration setAggregationInterval(Duration interval);

        /**
         * 设置是否启用实时监控
         */
        PerformanceConfiguration setRealTimeMonitoringEnabled(boolean enabled);
    }

    /**
     * 告警类型枚举
     */
    enum AlertType {
        RESPONSE_TIME, THROUGHPUT, ERROR_RATE, CONCURRENCY, RESOURCE_USAGE
    }

    /**
     * 告警级别枚举
     */
    enum AlertLevel {
        INFO, WARNING, CRITICAL
    }

    /**
     * 趋势方向枚举
     */
    enum TrendDirection {
        INCREASING, DECREASING, STABLE, VOLATILE
    }

    /**
     * 问题严重程度枚举
     */
    enum IssueSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 报告格式枚举
     */
    enum ReportFormat {
        JSON, XML, HTML, PDF
    }

    /**
     * 导出格式枚举
     */
    enum ExportFormat {
        CSV, JSON, XML
    }
}