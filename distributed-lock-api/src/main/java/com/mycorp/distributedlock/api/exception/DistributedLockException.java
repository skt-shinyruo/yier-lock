package com.mycorp.distributedlock.api.exception;

import java.util.Map;
import java.util.Optional;

/**
 * 增强的分布式锁异常基类
 * 提供详细的错误信息、错误代码和上下文信息
 */
public class DistributedLockException extends RuntimeException {
    
    private final ErrorCode errorCode;
    private final String lockName;
    private final long timestamp;
    private final Map<String, Object> context;
    private final String recommendation;

    // 保持向后兼容性的基础构造函数
    public DistributedLockException(String message) {
        this(message, (Throwable) null);
    }

    public DistributedLockException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = ErrorCode.GENERAL_ERROR;
        this.lockName = null;
        this.timestamp = System.currentTimeMillis();
        this.context = Map.of();
        this.recommendation = null;
    }

    // 增强的构造函数
    public DistributedLockException(String message, String lockName) {
        this(message, null, lockName);
    }

    public DistributedLockException(String message, Throwable cause, String lockName) {
        this(message, cause, lockName, ErrorCode.GENERAL_ERROR);
    }

    public DistributedLockException(String message, Throwable cause, String lockName, ErrorCode errorCode) {
        this(message, cause, lockName, errorCode, null);
    }

    public DistributedLockException(String message, Throwable cause, String lockName, 
                                  ErrorCode errorCode, Map<String, Object> context) {
        this(message, cause, lockName, errorCode, context, null);
    }

    public DistributedLockException(String message, Throwable cause, String lockName, 
                                  ErrorCode errorCode, Map<String, Object> context,
                                  String recommendation) {
        super(message, cause);
        this.errorCode = errorCode;
        this.lockName = lockName;
        this.timestamp = System.currentTimeMillis();
        this.context = context != null ? Map.copyOf(context) : Map.of();
        this.recommendation = recommendation;
    }

    /**
     * 获取错误代码
     */
    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取锁名称
     */
    public String getLockName() {
        return lockName;
    }

    /**
     * 获取时间戳
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 获取上下文信息
     */
    public Map<String, Object> getContext() {
        return context;
    }

    /**
     * 获取建议信息
     */
    public String getRecommendation() {
        return recommendation;
    }

    /**
     * 获取指定上下文的值
     */
    public <T> Optional<T> getContextValue(String key, Class<T> type) {
        Object value = context.get(key);
        if (value != null && type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return Optional.empty();
    }

    /**
     * 创建带有上下文的构建器
     */
    public Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DistributedLockException{");
        sb.append("errorCode=").append(errorCode);
        if (lockName != null) {
            sb.append(", lockName='").append(lockName).append("'");
        }
        sb.append(", message='").append(getMessage()).append("'");
        if (recommendation != null) {
            sb.append(", recommendation='").append(recommendation).append("'");
        }
        if (!context.isEmpty()) {
            sb.append(", context=").append(context);
        }
        sb.append(", timestamp=").append(timestamp);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 异常构建器
     */
    public static class Builder {
        protected String message;
        protected Throwable cause;
        protected String lockName;
        protected ErrorCode errorCode = ErrorCode.GENERAL_ERROR;
        protected Map<String, Object> context;
        protected String recommendation;

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        public Builder lockName(String lockName) {
            this.lockName = lockName;
            return this;
        }

        public Builder errorCode(ErrorCode errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public Builder recommendation(String recommendation) {
            this.recommendation = recommendation;
            return this;
        }

        public DistributedLockException build() {
            return new DistributedLockException(message, cause, lockName, errorCode, context, recommendation);
        }
    }

    /**
     * 错误代码枚举
     */
    public enum ErrorCode {
        /** 通用错误 */
        GENERAL_ERROR,
        /** 锁获取失败 */
        LOCK_ACQUISITION_FAILED,
        /** 锁释放失败 */
        LOCK_RELEASE_FAILED,
        /** 锁续期失败 */
        LOCK_RENEWAL_FAILED,
        /** 锁超时 */
        LOCK_TIMEOUT,
        /** 锁已被占用 */
        LOCK_ALREADY_HELD,
        /** 锁不可用 */
        LOCK_UNAVAILABLE,
        /** 锁不存在 */
        LOCK_NOT_FOUND,
        /** 锁已过期 */
        LOCK_EXPIRED,
        /** 锁重入失败 */
        LOCK_REENTRANT_FAILED,
        /** 锁升级失败 */
        LOCK_UPGRADE_FAILED,
        /** 锁降级失败 */
        LOCK_DOWNGRADE_FAILED,
        /** 批量操作失败 */
        BATCH_OPERATION_FAILED,
        /** 配置错误 */
        CONFIGURATION_ERROR,
        /** 网络错误 */
        NETWORK_ERROR,
        /** 连接错误 */
        CONNECTION_ERROR,
        /** 超时错误 */
        TIMEOUT_ERROR,
        /** 资源不足 */
        RESOURCE_EXHAUSTED,
        /** 权限错误 */
        PERMISSION_DENIED,
        /** 不支持的操作 */
        OPERATION_NOT_SUPPORTED,
        /** 健康检查失败 */
        HEALTH_CHECK_FAILED,
        /** 优雅关闭失败 */
        GRACEFUL_SHUTDOWN_FAILED,
        /** 性能指标错误 */
        METRICS_ERROR,
        /** 事件监听错误 */
        EVENT_LISTENER_ERROR,
        /** 初始化错误 */
        INITIALIZATION_ERROR,
        /** 销毁错误 */
        DISPOSAL_ERROR
    }
}