package com.mycorp.distributedlock.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * 健康检查接口
 * 提供分布式锁系统的全方位健康监控和诊断能力
 */
public interface HealthCheck {

    /**
     * 执行系统级健康检查
     * 
     * @return 系统健康状态
     */
    SystemHealthStatus checkSystemHealth();

    /**
     * 异步执行系统级健康检查
     * 
     * @return 异步系统健康状态
     */
    CompletableFuture<SystemHealthStatus> checkSystemHealthAsync();

    /**
     * 检查指定锁的健康状态
     * 
     * @param lockName 锁名称
     * @return 锁健康状态
     */
    LockHealthStatus checkLockHealth(String lockName);

    /**
     * 异步检查指定锁的健康状态
     * 
     * @param lockName 锁名称
     * @return 异步锁健康状态
     */
    CompletableFuture<LockHealthStatus> checkLockHealthAsync(String lockName);

    /**
     * 批量检查锁健康状态
     * 
     * @param lockNames 锁名称列表
     * @return 锁健康状态映射
     */
    Map<String, LockHealthStatus> checkMultipleLocksHealth(List<String> lockNames);

    /**
     * 异步批量检查锁健康状态
     * 
     * @param lockNames 锁名称列表
     * @return 异步锁健康状态映射
     */
    CompletableFuture<Map<String, LockHealthStatus>> checkMultipleLocksHealthAsync(List<String> lockNames);

    /**
     * 检查连接健康状态
     * 
     * @return 连接健康状态
     */
    ConnectionHealthStatus checkConnectionHealth();

    /**
     * 异步检查连接健康状态
     * 
     * @return 异步连接健康状态
     */
    CompletableFuture<ConnectionHealthStatus> checkConnectionHealthAsync();

    /**
     * 检查性能指标
     * 
     * @return 性能健康状态
     */
    PerformanceHealthStatus checkPerformanceHealth();

    /**
     * 异步检查性能指标
     * 
     * @return 异步性能健康状态
     */
    CompletableFuture<PerformanceHealthStatus> checkPerformanceHealthAsync();

    /**
     * 执行深度健康诊断
     * 
     * @param depth 诊断深度
     * @return 诊断报告
     */
    DiagnosticReport performDeepDiagnosis(DiagnosisDepth depth);

    /**
     * 异步执行深度健康诊断
     * 
     * @param depth 诊断深度
     * @return 异步诊断报告
     */
    CompletableFuture<DiagnosticReport> performDeepDiagnosisAsync(DiagnosisDepth depth);

    /**
     * 启动健康监控
     * 
     * @param interval 监控间隔
     * @param unit 时间单位
     * @return 监控任务句柄
     */
    ScheduledFuture<?> startHealthMonitoring(Duration interval, java.util.concurrent.TimeUnit unit);

    /**
     * 停止健康监控
     * 
     * @param monitoringTask 监控任务
     * @return 是否成功停止
     */
    boolean stopHealthMonitoring(ScheduledFuture<?> monitoringTask);

    /**
     * 设置健康检查回调
     * 
     * @param callback 健康检查回调
     */
    void setHealthCheckCallback(HealthCheckCallback callback);

    /**
     * 清理健康检查缓存
     */
    void clearHealthCheckCache();

    /**
     * 获取健康检查历史记录
     * 
     * @param limit 记录数量限制
     * @return 健康检查历史记录
     */
    List<HealthCheckRecord> getHealthCheckHistory(int limit);

    /**
     * 获取健康检查配置
     * 
     * @return 健康检查配置
     */
    HealthCheckConfiguration getConfiguration();

    /**
     * 更新健康检查配置
     * 
     * @param configuration 新配置
     */
    void updateConfiguration(HealthCheckConfiguration configuration);

    /**
     * 系统健康状态
     */
    interface SystemHealthStatus {
        /**
         * 是否整体健康
         */
        boolean isHealthy();

        /**
         * 获取系统状态
         */
        SystemStatus getOverallStatus();

        /**
         * 获取健康评分
         */
        double getHealthScore();

        /**
         * 获取组件健康状态
         */
        Map<String, ComponentHealthStatus> getComponentStatuses();

        /**
         * 获取警告信息
         */
        List<String> getWarnings();

        /**
         * 获取错误信息
         */
        List<String> getErrors();

        /**
         * 获取检查时间
         */
        long getCheckTime();

        /**
         * 获取详细描述
         */
        String getDescription();
    }

    /**
     * 锁健康状态
     */
    interface LockHealthStatus {
        /**
         * 是否健康
         */
        boolean isHealthy();

        /**
         * 获取锁状态
         */
        LockHealthStatusType getStatus();

        /**
         * 获取响应时间
         */
        long getResponseTime();

        /**
         * 获取最后检查时间
         */
        long getLastCheckTime();

        /**
         * 获取错误信息
         */
        String getErrorMessage();

        /**
         * 获取详细信息
         */
        String getDetails();

        /**
         * 获取锁名称
         */
        String getLockName();

        /**
         * 获取性能指标
         */
        LockPerformanceMetrics getPerformanceMetrics();
    }

    /**
     * 连接健康状态
     */
    interface ConnectionHealthStatus {
        /**
         * 是否健康
         */
        boolean isHealthy();

        /**
         * 获取连接状态
         */
        ConnectionStatus getStatus();

        /**
         * 获取活跃连接数
         */
        int getActiveConnections();

        /**
         * 获取连接池状态
         */
        ConnectionPoolStatus getConnectionPoolStatus();

        /**
         * 获取网络延迟
         */
        long getNetworkLatency();

        /**
         * 获取错误率
         */
        double getErrorRate();

        /**
         * 获取详细信息
         */
        String getDetails();
    }

    /**
     * 性能健康状态
     */
    interface PerformanceHealthStatus {
        /**
         * 是否性能良好
         */
        boolean isPerformanceGood();

        /**
         * 获取性能等级
         */
        PerformanceLevel getPerformanceLevel();

        /**
         * 获取吞吐量
         */
        double getThroughput();

        /**
         * 获取平均响应时间
         */
        long getAverageResponseTime();

        /**
         * 获取95分位响应时间
         */
        long getP95ResponseTime();

        /**
         * 获取99分位响应时间
         */
        long getP99ResponseTime();

        /**
         * 获取CPU使用率
         */
        double getCpuUsage();

        /**
         * 获取内存使用率
         */
        double getMemoryUsage();

        /**
         * 获取并发数
         */
        int getConcurrency();

        /**
         * 获取详细信息
         */
        String getDetails();
    }

    /**
     * 诊断报告
     */
    interface DiagnosticReport {
        /**
         * 是否发现严重问题
         */
        boolean hasCriticalIssues();

        /**
         * 获取问题严重性统计
         */
        Map<IssueSeverity, Integer> getIssueStatistics();

        /**
         * 获取详细问题列表
         */
        List<DiagnosticIssue> getIssues();

        /**
         * 获取建议措施
         */
        List<String> getRecommendations();

        /**
         * 获取系统拓扑
         */
        SystemTopology getSystemTopology();

        /**
         * 获取报告生成时间
         */
        long getReportTime();
    }

    /**
     * 健康检查记录
     */
    interface HealthCheckRecord {
        /**
         * 获取检查类型
         */
        HealthCheckType getCheckType();

        /**
         * 获取检查结果
         */
        boolean getResult();

        /**
         * 获取检查时间
         */
        long getCheckTime();

        /**
         * 获取响应时间
         */
        long getResponseTime();

        /**
         * 获取错误信息
         */
        String getErrorMessage();

        /**
         * 获取详细信息
         */
        String getDetails();

        /**
         * 创建Builder
         */
        Builder builder();

        /**
         * Builder类
         */
        class Builder {
            private HealthCheckType checkType;
            private boolean result;
            private long checkTime;
            private long responseTime;
            private String errorMessage;
            private String details;

            public Builder checkType(HealthCheckType checkType) {
                this.checkType = checkType;
                return this;
            }

            public Builder result(boolean result) {
                this.result = result;
                return this;
            }

            public Builder checkTime(long checkTime) {
                this.checkTime = checkTime;
                return this;
            }

            public Builder responseTime(long responseTime) {
                this.responseTime = responseTime;
                return this;
            }

            public Builder errorMessage(String errorMessage) {
                this.errorMessage = errorMessage;
                return this;
            }

            public Builder details(String details) {
                this.details = details;
                return this;
            }

            public HealthCheckRecord build() {
                return new DefaultHealthCheckRecord(checkType, result, checkTime, responseTime, errorMessage, details);
            }
        }

        /**
         * 默认实现
         */
        class DefaultHealthCheckRecord implements HealthCheckRecord {
            private final HealthCheckType checkType;
            private final boolean result;
            private final long checkTime;
            private final long responseTime;
            private final String errorMessage;
            private final String details;

            public DefaultHealthCheckRecord(HealthCheckType checkType, boolean result, long checkTime,
                                          long responseTime, String errorMessage, String details) {
                this.checkType = checkType;
                this.result = result;
                this.checkTime = checkTime;
                this.responseTime = responseTime;
                this.errorMessage = errorMessage;
                this.details = details;
            }

            @Override
            public HealthCheckType getCheckType() {
                return checkType;
            }

            @Override
            public boolean getResult() {
                return result;
            }

            @Override
            public long getCheckTime() {
                return checkTime;
            }

            @Override
            public long getResponseTime() {
                return responseTime;
            }

            @Override
            public String getErrorMessage() {
                return errorMessage;
            }

            @Override
            public String getDetails() {
                return details;
            }

            @Override
            public Builder builder() {
                return new Builder()
                    .checkType(checkType)
                    .result(result)
                    .checkTime(checkTime)
                    .responseTime(responseTime)
                    .errorMessage(errorMessage)
                    .details(details);
            }
        }
    }

    /**
     * 组件健康状态
     */
    interface ComponentHealthStatus {
        /**
         * 是否健康
         */
        boolean isHealthy();

        /**
         * 获取组件状态
         */
        ComponentStatus getStatus();

        /**
         * 获取组件名称
         */
        String getComponentName();

        /**
         * 获取详细信息
         */
        String getDetails();
    }

    /**
     * 锁性能指标
     */
    interface LockPerformanceMetrics {
        /**
         * 获取平均获取时间
         */
        long getAverageAcquisitionTime();

        /**
         * 获取成功率
         */
        double getSuccessRate();

        /**
         * 获取并发度
         */
        int getConcurrencyLevel();

        /**
         * 获取锁持有时间统计
         */
        LockHoldTimeStatistics getHoldTimeStatistics();
    }

    /**
     * 锁持有时间统计
     */
    interface LockHoldTimeStatistics {
        /**
         * 获取平均持有时间
         */
        long getAverageHoldTime();

        /**
         * 获取最小持有时间
         */
        long getMinHoldTime();

        /**
         * 获取最大持有时间
         */
        long getMaxHoldTime();

        /**
         * 获取95分位持有时间
         */
        long getP95HoldTime();
    }

    /**
     * 连接池状态
     */
    interface ConnectionPoolStatus {
        /**
         * 获取池大小
         */
        int getPoolSize();

        /**
         * 获取活跃连接数
         */
        int getActiveConnections();

        /**
         * 获取空闲连接数
         */
        int getIdleConnections();

        /**
         * 获取等待队列大小
         */
        int getWaitingQueueSize();

        /**
         * 获取连接使用率
         */
        double getConnectionUtilization();
    }

    /**
     * 系统拓扑
     */
    interface SystemTopology {
        /**
         * 获取节点列表
         */
        List<SystemNode> getNodes();

        /**
         * 获取连接关系
         */
        List<NodeConnection> getConnections();
    }

    /**
     * 系统节点
     */
    interface SystemNode {
        /**
         * 获取节点ID
         */
        String getNodeId();

        /**
         * 获取节点类型
         */
        NodeType getNodeType();

        /**
         * 获取节点状态
         */
        NodeStatus getStatus();

        /**
         * 获取节点地址
         */
        String getAddress();
    }

    /**
     * 节点连接
     */
    interface NodeConnection {
        /**
         * 获取源节点ID
         */
        String getSourceNodeId();

        /**
         * 获取目标节点ID
         */
        String getTargetNodeId();

        /**
         * 获取连接状态
         */
        ConnectionStatus getStatus();

        /**
         * 获取延迟
         */
        long getLatency();
    }

    /**
     * 诊断问题
     */
    interface DiagnosticIssue {
        /**
         * 获取问题ID
         */
        String getIssueId();

        /**
         * 获取问题描述
         */
        String getDescription();

        /**
         * 获取问题严重性
         */
        IssueSeverity getSeverity();

        /**
         * 获取影响范围
         */
        String getAffectedComponent();

        /**
         * 获取解决建议
         */
        String getSolution();

        /**
         * 创建Builder
         */
        Builder builder();

        /**
         * Builder类
         */
        class Builder {
            private String issueId;
            private String description;
            private IssueSeverity severity;
            private String affectedComponent;
            private String solution;

            public Builder issueId(String issueId) {
                this.issueId = issueId;
                return this;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder severity(IssueSeverity severity) {
                this.severity = severity;
                return this;
            }

            public Builder affectedComponent(String affectedComponent) {
                this.affectedComponent = affectedComponent;
                return this;
            }

            public Builder solution(String solution) {
                this.solution = solution;
                return this;
            }

            public DiagnosticIssue build() {
                return new DefaultDiagnosticIssue(issueId, description, severity, affectedComponent, solution);
            }
        }

        /**
         * 默认实现
         */
        class DefaultDiagnosticIssue implements DiagnosticIssue {
            private final String issueId;
            private final String description;
            private final IssueSeverity severity;
            private final String affectedComponent;
            private final String solution;

            public DefaultDiagnosticIssue(String issueId, String description, IssueSeverity severity,
                                        String affectedComponent, String solution) {
                this.issueId = issueId;
                this.description = description;
                this.severity = severity;
                this.affectedComponent = affectedComponent;
                this.solution = solution;
            }

            @Override
            public String getIssueId() {
                return issueId;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public IssueSeverity getSeverity() {
                return severity;
            }

            @Override
            public String getAffectedComponent() {
                return affectedComponent;
            }

            @Override
            public String getSolution() {
                return solution;
            }

            @Override
            public Builder builder() {
                return new Builder()
                    .issueId(issueId)
                    .description(description)
                    .severity(severity)
                    .affectedComponent(affectedComponent)
                    .solution(solution);
            }
        }
    }

    /**
     * 健康检查回调
     */
    @FunctionalInterface
    interface HealthCheckCallback {
        /**
         * 健康检查完成回调
         * 
         * @param status 健康状态
         */
        void onHealthCheckComplete(SystemHealthStatus status);
    }

    /**
     * 健康检查配置
     */
    interface HealthCheckConfiguration {
        /**
         * 获取检查间隔
         */
        Duration getCheckInterval();

        /**
         * 获取超时时间
         */
        Duration getTimeout();

        /**
         * 获取重试次数
         */
        int getRetryCount();

        /**
         * 是否启用深度诊断
         */
        boolean isDeepDiagnosisEnabled();

        /**
         * 获取性能阈值
         */
        PerformanceThresholds getPerformanceThresholds();

        /**
         * 设置检查间隔
         */
        HealthCheckConfiguration setCheckInterval(Duration interval);

        /**
         * 设置超时时间
         */
        HealthCheckConfiguration setTimeout(Duration timeout);

        /**
         * 设置重试次数
         */
        HealthCheckConfiguration setRetryCount(int retryCount);

        /**
         * 设置是否启用深度诊断
         */
        HealthCheckConfiguration setDeepDiagnosisEnabled(boolean enabled);

        /**
         * 设置性能阈值
         */
        HealthCheckConfiguration setPerformanceThresholds(PerformanceThresholds thresholds);
    }

    /**
     * 性能阈值
     */
    interface PerformanceThresholds {
        /**
         * 获取最大响应时间
         */
        long getMaxResponseTime();

        /**
         * 获取最小成功率
         */
        double getMinSuccessRate();

        /**
         * 获取最大CPU使用率
         */
        double getMaxCpuUsage();

        /**
         * 获取最大内存使用率
         */
        double getMaxMemoryUsage();

        /**
         * 获取最大错误率
         */
        double getMaxErrorRate();

        /**
         * 设置最大响应时间
         */
        PerformanceThresholds setMaxResponseTime(long responseTime);

        /**
         * 设置最小成功率
         */
        PerformanceThresholds setMinSuccessRate(double rate);

        /**
         * 设置最大CPU使用率
         */
        PerformanceThresholds setMaxCpuUsage(double usage);

        /**
         * 设置最大内存使用率
         */
        PerformanceThresholds setMaxMemoryUsage(double usage);

        /**
         * 设置最大错误率
         */
        PerformanceThresholds setMaxErrorRate(double rate);
    }

    // 枚举定义

    /**
     * 系统状态枚举
     */
    enum SystemStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }

    /**
     * 锁健康状态类型枚举
     */
    enum LockHealthStatusType {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }

    /**
     * 连接状态枚举
     */
    enum ConnectionStatus {
        CONNECTED, DISCONNECTED, DEGRADED, RECONNECTING, UNKNOWN
    }

    /**
     * 性能等级枚举
     */
    enum PerformanceLevel {
        EXCELLENT, GOOD, FAIR, POOR, CRITICAL
    }

    /**
     * 诊断深度枚举
     */
    enum DiagnosisDepth {
        BASIC, STANDARD, DEEP, COMPREHENSIVE
    }

    /**
     * 问题严重性枚举
     */
    enum IssueSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    /**
     * 健康检查类型枚举
     */
    enum HealthCheckType {
        SYSTEM, LOCK, CONNECTION, PERFORMANCE
    }

    /**
     * 组件状态枚举
     */
    enum ComponentStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }

    /**
     * 节点类型枚举
     */
    enum NodeType {
        MASTER, SLAVE, COORDINATOR, CLIENT
    }

    /**
     * 节点状态枚举
     */
    enum NodeStatus {
        ONLINE, OFFLINE, DEGRADED, MAINTENANCE
    }
}