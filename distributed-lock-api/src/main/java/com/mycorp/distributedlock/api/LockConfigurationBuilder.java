package com.mycorp.distributedlock.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 锁配置构建器接口
 * 提供流式API来构建和配置分布式锁的各种参数
 * 
 * @param <T> 构建器类型，返回类型
 */
public interface LockConfigurationBuilder<T extends LockConfigurationBuilder<T>> {

    /**
     * 设置锁的租约时间
     * 
     * @param leaseTime 租约时间
     * @param timeUnit 时间单位
     * @return 构建器实例
     */
    T leaseTime(long leaseTime, java.util.concurrent.TimeUnit timeUnit);

    /**
     * 设置锁的租约时间
     * 
     * @param leaseTime 租约时间
     * @return 构建器实例
     */
    default T leaseTime(Duration leaseTime) {
        return leaseTime(leaseTime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 设置锁的等待时间
     * 
     * @param waitTime 等待时间
     * @param timeUnit 时间单位
     * @return 构建器实例
     */
    T waitTime(long waitTime, java.util.concurrent.TimeUnit timeUnit);

    /**
     * 设置锁的等待时间
     * 
     * @param waitTime 等待时间
     * @return 构建器实例
     */
    default T waitTime(Duration waitTime) {
        return waitTime(waitTime.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 设置锁名称
     * 
     * @param name 锁名称
     * @return 构建器实例
     */
    T name(String name);

    /**
     * 设置锁类型
     * 
     * @param lockType 锁类型
     * @return 构建器实例
     */
    T lockType(LockType lockType);

    /**
     * 设置是否公平锁
     * 
     * @param fairLock 是否公平锁
     * @return 构建器实例
     */
    T fairLock(boolean fairLock);

    /**
     * 设置是否启用重试机制
     * 
     * @param retryEnabled 是否启用重试
     * @return 构建器实例
     */
    T retryEnabled(boolean retryEnabled);

    /**
     * 设置重试次数
     * 
     * @param retryCount 重试次数
     * @return 构建器实例
     */
    T retryCount(int retryCount);

    /**
     * 设置重试间隔
     * 
     * @param retryInterval 间隔时间
     * @param timeUnit 时间单位
     * @return 构建器实例
     */
    T retryInterval(long retryInterval, java.util.concurrent.TimeUnit timeUnit);

    /**
     * 设置重试间隔
     * 
     * @param retryInterval 间隔时间
     * @return 构建器实例
     */
    default T retryInterval(Duration retryInterval) {
        return retryInterval(retryInterval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 设置是否启用续期机制
     * 
     * @param autoRenew 是否启用续期
     * @return 构建器实例
     */
    T autoRenew(boolean autoRenew);

    /**
     * 设置续期间隔
     * 
     * @param renewInterval 续期间隔
     * @param timeUnit 时间单位
     * @return 构建器实例
     */
    T renewInterval(long renewInterval, java.util.concurrent.TimeUnit timeUnit);

    /**
     * 设置续期间隔
     * 
     * @param renewInterval 续期间隔
     * @return 构建器实例
     */
    default T renewInterval(Duration renewInterval) {
        return renewInterval(renewInterval.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 设置续期比例（相对于剩余时间的比例）
     * 
     * @param renewRatio 续期比例 (0.1-0.9)
     * @return 构建器实例
     */
    T renewRatio(double renewRatio);

    /**
     * 设置锁超时策略
     * 
     * @param timeoutStrategy 超时策略
     * @return 构建器实例
     */
    T timeoutStrategy(TimeoutStrategy timeoutStrategy);

    /**
     * 设置是否启用死锁检测
     * 
     * @param deadlockDetectionEnabled 是否启用死锁检测
     * @return 构建器实例
     */
    T deadlockDetectionEnabled(boolean deadlockDetectionEnabled);

    /**
     * 设置死锁检测超时时间
     * 
     * @param deadlockDetectionTimeout 检测超时时间
     * @param timeUnit 时间单位
     * @return 构建器实例
     */
    T deadlockDetectionTimeout(long deadlockDetectionTimeout, java.util.concurrent.TimeUnit timeUnit);

    /**
     * 设置死锁检测超时时间
     * 
     * @param deadlockDetectionTimeout 检测超时时间
     * @return 构建器实例
     */
    default T deadlockDetectionTimeout(Duration deadlockDetectionTimeout) {
        return deadlockDetectionTimeout(deadlockDetectionTimeout.toMillis(), 
                                       java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 设置是否启用事件监听
     * 
     * @param eventListeningEnabled 是否启用事件监听
     * @return 构建器实例
     */
    T eventListeningEnabled(boolean eventListeningEnabled);

    /**
     * 添加事件监听器
     * 
     * @param listener 事件监听器
     * @return 构建器实例
     */
    <L extends DistributedLock> T addEventListener(LockEventListener<L> listener);

    /**
     * 设置高可用策略
     * 
     * @param haStrategy 高可用策略
     * @return 构建器实例
     */
    T highAvailabilityStrategy(HighAvailabilityStrategy<?> haStrategy);

    /**
     * 设置批量操作配置
     * 
     * @param batchConfig 批量操作配置
     * @return 构建器实例
     */
    T batchConfig(BatchOperationConfig batchConfig);

    /**
     * 设置监控配置
     * 
     * @param monitoringConfig 监控配置
     * @return 构建器实例
     */
    T monitoringConfig(MonitoringConfig monitoringConfig);

    /**
     * 设置自定义属性
     * 
     * @param key 属性键
     * @param value 属性值
     * @return 构建器实例
     */
    T withProperty(String key, String value);

    /**
     * 设置自定义属性
     * 
     * @param properties 属性映射
     * @return 构建器实例
     */
    T withProperties(java.util.Map<String, String> properties);

    /**
     * 验证配置
     * 
     * @return 验证结果
     */
    ConfigurationValidationResult validate();

    /**
     * 构建锁配置对象
     * 
     * @return 锁配置对象
     */
    LockConfiguration build();

    /**
     * 应用构建器配置到消费者
     * 
     * @param consumer 配置消费者
     * @return 构建器实例
     */
    T apply(Consumer<LockConfigurationBuilder<T>> consumer);

    /**
     * 复制当前配置
     * 
     * @return 配置副本
     */
    T copy();

    /**
     * 获取构建器类型
     * 
     * @return 构建器类型
     */
    Class<T> getBuilderType();

    /**
     * 锁类型枚举
     */
    enum LockType {
        /** 互斥锁 */
        MUTEX,
        /** 读写锁 */
        READ_WRITE,
        /** 可重入锁 */
        REENTRANT,
        /** 公平锁 */
        FAIR,
        /** 信号量 */
        SEMAPHORE,
        /** 计数信号量 */
        COUNTING_SEMAPHORE
    }

    /**
     * 超时策略枚举
     */
    enum TimeoutStrategy {
        /** 立即失败 */
        IMMEDIATE_FAIL,
        /** 重试后失败 */
        RETRY_THEN_FAIL,
        /** 阻塞直到超时 */
        BLOCK_UNTIL_TIMEOUT,
        /** 忽略超时 */
        IGNORE_TIMEOUT
    }

    /**
     * 批量操作配置
     */
    interface BatchOperationConfig {
        /**
         * 获取批量大小限制
         * 
         * @return 批量大小限制
         */
        int getBatchSizeLimit();

        /**
         * 获取批处理超时时间
         * 
         * @return 批处理超时时间（毫秒）
         */
        long getBatchTimeoutMs();

        /**
         * 获取事务超时时间
         * 
         * @return 事务超时时间（毫秒）
         */
        long getTransactionTimeoutMs();

        /**
         * 是否启用事务模式
         * 
         * @return 是否启用事务模式
         */
        boolean isTransactionModeEnabled();

        /**
         * 是否启用批处理优化
         * 
         * @return 是否启用批处理优化
         */
        boolean isBatchOptimizationEnabled();
    }

    /**
     * 监控配置
     */
    interface MonitoringConfig {
        /**
         * 是否启用性能指标收集
         * 
         * @return 是否启用性能指标收集
         */
        boolean isMetricsEnabled();

        /**
         * 是否启用链路追踪
         * 
         * @return 是否启用链路追踪
         */
        boolean isTracingEnabled();

        /**
         * 获取指标收集间隔
         * 
         * @return 指标收集间隔（毫秒）
         */
        long getMetricsIntervalMs();

        /**
         * 获取指标标签
         * 
         * @return 指标标签映射
         */
        java.util.Map<String, String> getTags();
    }

    /**
     * 配置验证结果
     */
    interface ConfigurationValidationResult {
        /**
         * 是否有效
         * 
         * @return 是否有效
         */
        boolean isValid();

        /**
         * 获取验证错误列表
         * 
         * @return 验证错误列表
         */
        List<String> getErrors();

        /**
         * 获取警告列表
         * 
         * @return 警告列表
         */
        List<String> getWarnings();

        /**
         * 获取建议列表
         * 
         * @return 建议列表
         */
        List<String> getSuggestions();
    }

    /**
     * 锁配置对象
     */
    interface LockConfiguration {
        /**
         * 获取锁名称
         * 
         * @return 锁名称
         */
        String getName();

        /**
         * 获取锁类型
         * 
         * @return 锁类型
         */
        LockType getLockType();

        /**
         * 获取租约时间
         * 
         * @return 租约时间
         */
        long getLeaseTime(java.util.concurrent.TimeUnit timeUnit);

        /**
         * 获取租约时间
         * 
         * @return 租约时间
         */
        default Duration getLeaseTime() {
            return Duration.ofMillis(getLeaseTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        /**
         * 获取等待时间
         * 
         * @return 等待时间
         */
        long getWaitTime(java.util.concurrent.TimeUnit timeUnit);

        /**
         * 获取等待时间
         * 
         * @return 等待时间
         */
        default Duration getWaitTime() {
            return Duration.ofMillis(getWaitTime(java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        /**
         * 是否公平锁
         * 
         * @return 是否公平锁
         */
        boolean isFairLock();

        /**
         * 是否启用重试
         * 
         * @return 是否启用重试
         */
        boolean isRetryEnabled();

        /**
         * 获取重试次数
         * 
         * @return 重试次数
         */
        int getRetryCount();

        /**
         * 获取重试间隔
         * 
         * @return 重试间隔
         */
        long getRetryInterval(java.util.concurrent.TimeUnit timeUnit);

        /**
         * 获取重试间隔
         * 
         * @return 重试间隔
         */
        default Duration getRetryInterval() {
            return Duration.ofMillis(getRetryInterval(java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        /**
         * 是否启用续期
         * 
         * @return 是否启用续期
         */
        boolean isAutoRenew();

        /**
         * 获取续期间隔
         * 
         * @return 续期间隔
         */
        long getRenewInterval(java.util.concurrent.TimeUnit timeUnit);

        /**
         * 获取续期间隔
         * 
         * @return 续期间隔
         */
        default Duration getRenewInterval() {
            return Duration.ofMillis(getRenewInterval(java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        /**
         * 获取续期比例
         * 
         * @return 续期比例
         */
        double getRenewRatio();

        /**
         * 获取超时策略
         * 
         * @return 超时策略
         */
        TimeoutStrategy getTimeoutStrategy();

        /**
         * 是否启用死锁检测
         * 
         * @return 是否启用死锁检测
         */
        boolean isDeadlockDetectionEnabled();

        /**
         * 获取死锁检测超时时间
         * 
         * @return 死锁检测超时时间
         */
        long getDeadlockDetectionTimeout(java.util.concurrent.TimeUnit timeUnit);

        /**
         * 获取死锁检测超时时间
         * 
         * @return 死锁检测超时时间
         */
        default Duration getDeadlockDetectionTimeout() {
            return Duration.ofMillis(getDeadlockDetectionTimeout(java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        /**
         * 是否启用事件监听
         * 
         * @return 是否启用事件监听
         */
        boolean isEventListeningEnabled();

        /**
         * 获取自定义属性
         * 
         * @return 自定义属性映射
         */
        java.util.Map<String, String> getProperties();

        /**
         * 获取指定属性值
         * 
         * @param key 属性键
         * @return 属性值
         */
        default Optional<String> getProperty(String key) {
            return Optional.ofNullable(getProperties().get(key));
        }
    }
}